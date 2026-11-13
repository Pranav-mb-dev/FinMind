package com.smartspend.transaction;

import com.smartspend.alert.AlertService;
import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import com.smartspend.common.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PendingTransactionService {

    static final int SUGGESTED_CATEGORY_MAX_LENGTH = 100;
    /** amount column is NUMERIC(12,2): at most 10 integer digits. */
    static final int MAX_AMOUNT_INTEGER_DIGITS = 10;
    private static final Set<String> VALID_TYPES = Set.of("INCOME", "EXPENSE");

    private final PendingTransactionRepository pendingRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AlertService alertService;

    /**
     * Persists extracted rows as pending transactions. No-op if the document already has pending rows
     * (guards against repeated status polls). Rows that fail to parse are skipped, not thrown.
     */
    @Transactional
    public void saveExtracted(UUID userId, UUID documentId, List<Map<String, Object>> rows) {
        if (pendingRepository.existsByDocumentId(documentId)) {
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // On a name clash (case-insensitive) the user's own category wins over a system category.
        Map<String, UUID> categoriesByName = new HashMap<>();
        Map<String, UUID> systemByName = new HashMap<>();
        for (Category c : categoryRepository.findByUserIdOrIsSystemTrue(userId)) {
            if (c.getName() == null) {
                continue;
            }
            String key = c.getName().trim().toLowerCase(Locale.ROOT);
            if (userId.equals(c.getUserId())) {
                categoriesByName.putIfAbsent(key, c.getId());
            } else {
                systemByName.putIfAbsent(key, c.getId());
            }
        }
        systemByName.forEach(categoriesByName::putIfAbsent);

        Set<String> seenHashes = new HashSet<>();
        List<PendingTransaction> toSave = new ArrayList<>();
        int dropped = 0;

        for (Map<String, Object> row : rows) {
            PendingTransaction p = parseRow(row);
            if (p == null) {
                dropped++;
                continue;
            }
            if (!seenHashes.add(p.getDedupHash())) {
                dropped++;
                continue;
            }
            p.setUserId(userId);
            p.setDocumentId(documentId);
            if (p.getSuggestedCategory() != null) {
                p.setCategoryId(categoriesByName.get(p.getSuggestedCategory().trim().toLowerCase(Locale.ROOT)));
            }
            toSave.add(p);
        }

        if (dropped > 0) {
            log.info("Dropped {} invalid or duplicate extracted rows for document {}", dropped, documentId);
        }
        if (!toSave.isEmpty()) {
            pendingRepository.saveAll(toSave);
        }
    }

    @Transactional(readOnly = true)
    public List<PendingTransactionResponse> list(UUID userId, UUID documentId) {
        return pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId)
                .stream()
                .map(PendingTransactionResponse::from)
                .toList();
    }

    @Transactional
    public PendingTransactionResponse update(UUID userId, UUID id, UpdatePendingTransactionRequest req) {
        PendingTransaction p = pendingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Pending transaction not found"));

        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }
        BigDecimal amount = req.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (amount.precision() - amount.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Amount is too large");
        }
        String type = req.getType() == null ? null : req.getType().trim().toUpperCase(Locale.ROOT);
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("Type must be INCOME or EXPENSE");
        }
        if (req.getTransactionDate() == null) {
            throw new IllegalArgumentException("Transaction date is required");
        }
        if (req.getCategoryId() != null) {
            boolean visible = categoryRepository.findByUserIdOrIsSystemTrue(userId).stream()
                    .anyMatch(c -> req.getCategoryId().equals(c.getId()));
            if (!visible) {
                throw new ResourceNotFoundException("Category not found");
            }
        }

        p.setAmount(amount);
        p.setType(type);
        p.setDescription(cleanDescription(req.getDescription()));
        p.setTransactionDate(req.getTransactionDate());
        p.setCategoryId(req.getCategoryId());
        // dedupHash is intentionally left as computed at extraction time: it guards re-insertion of the
        // same extracted row, and recomputing it could collide with the (document_id, dedup_hash) unique key.

        return PendingTransactionResponse.from(pendingRepository.save(p));
    }

    /**
     * Imports the selected pending rows of a document as real transactions, skipping duplicates of
     * existing transactions and repeats within the selection, then removes all pending rows of the
     * document for this user. Unknown, foreign or already-removed IDs are ignored.
     */
    @Transactional
    public ConfirmResult confirm(UUID userId, UUID documentId, List<UUID> ids) {
        Set<UUID> selected = ids == null ? Set.of() : new HashSet<>(ids);

        List<PendingTransaction> rows = pendingRepository
                .findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId)
                .stream()
                .filter(p -> selected.contains(p.getId()))
                .toList();

        int imported = 0;
        int skipped = 0;
        Set<String> seenHashes = new HashSet<>();

        for (PendingTransaction p : rows) {
            String hash = computeDedupHash(p.getTransactionDate(), p.getAmount(), p.getType(), p.getDescription());
            if (!seenHashes.add(hash)
                    || transactionRepository.existsDuplicate(
                            userId, p.getTransactionDate(), p.getAmount(), p.getType(),
                            normalizeDescription(p.getDescription()))) {
                skipped++;
                continue;
            }
            transactionRepository.save(Transaction.builder()
                    .userId(userId)
                    .categoryId(p.getCategoryId())
                    .amount(p.getAmount())
                    .type(p.getType())
                    .description(p.getDescription())
                    .transactionDate(p.getTransactionDate())
                    .build());
            imported++;
        }

        pendingRepository.deleteByDocumentIdAndUserId(documentId, userId);
        if (imported > 0) {
            // One evaluation per confirm batch, run after this transaction commits.
            alertService.evaluateAfterChange(userId);
        }
        return new ConfirmResult(imported, skipped);
    }

    /**
     * SHA-256 hex of {@code date|amount|type|description}: amount scaled to 2 dp, type upper-cased,
     * description trimmed, lower-cased and whitespace-collapsed (null treated as empty).
     */
    public static String computeDedupHash(LocalDate date, BigDecimal amount, String type, String description) {
        String normDate = date == null ? "" : date.toString();
        String normAmount = amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String normType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        String input = normDate + "|" + normAmount + "|" + normType + "|" + normalizeDescription(description);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Normalised description used for dedup (hash and existing-transaction check):
     * null becomes "", trimmed, inner whitespace collapsed to one space, lower-cased (Locale.ROOT).
     */
    public static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Description as stored: trimmed with inner whitespace collapsed to one space (case preserved),
     * null if null or blank. Keeps stored text consistent with the
     * {@code lower(trim(description))} comparison in {@link TransactionRepository#existsDuplicate}.
     */
    static String cleanDescription(String description) {
        if (description == null) {
            return null;
        }
        String cleaned = description.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Returns a populated entity (without user/document/category) or null if the row is invalid. */
    private static PendingTransaction parseRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        try {
            Object rawDate = row.get("date");
            Object rawAmount = row.get("amount");
            Object rawType = row.get("type");
            if (rawDate == null || rawAmount == null || rawType == null) {
                return null;
            }
            LocalDate date = LocalDate.parse(String.valueOf(rawDate).trim());
            BigDecimal amount = new BigDecimal(String.valueOf(rawAmount).trim()).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                return null;
            }
            String type = String.valueOf(rawType).trim().toUpperCase(Locale.ROOT);
            if (!VALID_TYPES.contains(type)) {
                return null;
            }
            Object rawDesc = row.get("description");
            String description = cleanDescription(rawDesc == null ? null : String.valueOf(rawDesc));
            Object rawCategory = row.get("suggested_category");
            String suggested = rawCategory == null ? null : String.valueOf(rawCategory).trim();
            if (suggested != null && suggested.isEmpty()) {
                suggested = null;
            }
            if (suggested != null && suggested.length() > SUGGESTED_CATEGORY_MAX_LENGTH) {
                suggested = suggested.substring(0, SUGGESTED_CATEGORY_MAX_LENGTH);
            }

            return PendingTransaction.builder()
                    .transactionDate(date)
                    .amount(amount)
                    .type(type)
                    .description(description)
                    .suggestedCategory(suggested)
                    .dedupHash(computeDedupHash(date, amount, type, description))
                    .build();
        } catch (RuntimeException e) {
            // DateTimeParseException, NumberFormatException, ArithmeticException, etc.
            return null;
        }
    }
}
