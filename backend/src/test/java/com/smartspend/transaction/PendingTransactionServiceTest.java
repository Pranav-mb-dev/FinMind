package com.smartspend.transaction;

import com.smartspend.alert.AlertService;
import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import com.smartspend.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingTransactionServiceTest {

    @Mock
    private PendingTransactionRepository pendingRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AlertService alertService;

    @InjectMocks
    private PendingTransactionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(pendingRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pendingRepository.save(any(PendingTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- computeDedupHash ----------

    @Test
    void computeDedupHash_isCaseAndWhitespaceInsensitive() {
        LocalDate date = LocalDate.of(2025, 3, 4);
        String a = PendingTransactionService.computeDedupHash(date, new BigDecimal("12.50"), "EXPENSE", "Coffee   Shop");
        String b = PendingTransactionService.computeDedupHash(date, new BigDecimal("12.50"), "expense", "  coffee shop ");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void computeDedupHash_treatsEqualAmountsAtDifferentScaleAsSame() {
        LocalDate date = LocalDate.of(2025, 3, 4);
        String a = PendingTransactionService.computeDedupHash(date, new BigDecimal("12"), "EXPENSE", "x");
        String b = PendingTransactionService.computeDedupHash(date, new BigDecimal("12.00"), "EXPENSE", "x");

        assertThat(a).isEqualTo(b);
    }

    // ---------- saveExtracted ----------

    @Test
    void saveExtracted_skipsWhenDocumentAlreadyHasPending() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(true);

        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "EXPENSE", "Coffee", "Food")));

        verify(pendingRepository, never()).saveAll(anyList());
    }

    @Test
    void saveExtracted_matchesCategoryByNameIgnoringCase() {
        Category food = category("Food & Dining");
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(category("Salary"), food));

        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "expense", "Coffee", "food & DINING")));

        List<PendingTransaction> saved = captureSaved();
        assertThat(saved).hasSize(1);
        PendingTransaction p = saved.get(0);
        assertThat(p.getCategoryId()).isEqualTo(food.getId());
        assertThat(p.getUserId()).isEqualTo(userId);
        assertThat(p.getDocumentId()).isEqualTo(documentId);
        assertThat(p.getType()).isEqualTo("EXPENSE");
        assertThat(p.getAmount()).isEqualByComparingTo("10.00");
        assertThat(p.getAmount().scale()).isEqualTo(2);
        assertThat(p.getTransactionDate()).isEqualTo(LocalDate.of(2025, 3, 4));
        assertThat(p.getSuggestedCategory()).isEqualTo("food & DINING");
        assertThat(p.getDedupHash()).isEqualTo(PendingTransactionService.computeDedupHash(
                LocalDate.of(2025, 3, 4), new BigDecimal("10"), "EXPENSE", "Coffee"));
    }

    @Test
    void saveExtracted_prefersUserCategoryOverSystemCategoryWithSameName() {
        Category system = Category.builder().id(UUID.randomUUID()).name("Food").isSystem(true).build();
        Category own = Category.builder().id(UUID.randomUUID()).name("FOOD").userId(userId).build();
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        // System category listed first so a naive "first match wins" would pick it.
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(system, own));

        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "EXPENSE", "Coffee", "food")));

        assertThat(captureSaved().get(0).getCategoryId()).isEqualTo(own.getId());
    }

    @Test
    void saveExtracted_leavesCategoryNullWhenNoMatch() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(category("Salary")));

        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "EXPENSE", "Coffee", "Unknown")));

        List<PendingTransaction> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getCategoryId()).isNull();
        assertThat(saved.get(0).getSuggestedCategory()).isEqualTo("Unknown");
    }

    @Test
    void saveExtracted_dropsRepeatedHashWithinBatch() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());

        service.saveExtracted(userId, documentId, List.of(
                row("2025-03-04", 10, "EXPENSE", "Coffee", null),
                row("2025-03-04", "10.00", "expense", "  COFFEE ", null)));

        assertThat(captureSaved()).hasSize(1);
    }

    @Test
    void saveExtracted_dropsInvalidTypeAndUnparseableDateWithoutThrowing() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());

        service.saveExtracted(userId, documentId, List.of(
                row("2025-03-04", 10, "TRANSFER", "Bad type", null),
                row("03/04/2025", 10, "EXPENSE", "Bad date", null),
                row("2025-03-04", 0, "EXPENSE", "Zero amount", null),
                row("2025-03-04", -5, "EXPENSE", "Negative amount", null),
                row("2025-03-04", "abc", "EXPENSE", "Bad amount", null),
                row("2025-03-05", 20, "INCOME", "Good", null)));

        List<PendingTransaction> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDescription()).isEqualTo("Good");
    }

    @Test
    void saveExtracted_truncatesSuggestedCategoryTo100Chars() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());
        String longCategory = "c".repeat(150);

        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "EXPENSE", "Coffee", longCategory)));

        List<PendingTransaction> saved = captureSaved();
        assertThat(saved.get(0).getSuggestedCategory()).hasSize(100);
    }

    @Test
    void saveExtracted_storesDescriptionWithCollapsedWhitespaceButOriginalCase() {
        when(pendingRepository.existsByDocumentId(documentId)).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());

        service.saveExtracted(userId, documentId, List.of(
                row("2025-03-04", 10, "EXPENSE", "  POS  PURCHASE \t STARBUCKS ", null),
                row("2025-03-05", 11, "EXPENSE", "   ", null)));

        List<PendingTransaction> saved = captureSaved();
        assertThat(saved).extracting(PendingTransaction::getDescription)
                .containsExactly("POS PURCHASE STARBUCKS", null);
    }

    @Test
    void reImportOfSameStatementIsSkippedAsDuplicate() {
        LocalDate date = LocalDate.of(2025, 3, 4);
        UUID secondDocumentId = UUID.randomUUID();
        when(pendingRepository.existsByDocumentId(any())).thenReturn(false);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());

        // First import: extract and confirm.
        service.saveExtracted(userId, documentId, List.of(row("2025-03-04", 10, "EXPENSE", "POS  PURCHASE  STARBUCKS", null)));
        PendingTransaction first = captureSaved().get(0);
        first.setId(UUID.randomUUID());
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(first));
        service.confirm(userId, documentId, List.of(first.getId()));
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        String storedDescription = txCaptor.getValue().getDescription();
        assertThat(storedDescription).isEqualTo("POS PURCHASE STARBUCKS");

        // The repository query compares lower(trim(coalesce(description,''))) with normalizeDescription(...):
        // emulate it against the stored row so the mock answers exactly as the database would.
        String dbSide = storedDescription.trim().toLowerCase(java.util.Locale.ROOT);
        when(transactionRepository.existsDuplicate(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> date.equals(inv.getArgument(1))
                        && txCaptor.getValue().getAmount().compareTo(inv.getArgument(2)) == 0
                        && "EXPENSE".equals(inv.getArgument(3))
                        && dbSide.equals(inv.getArgument(4)));

        // Second import of the same statement.
        org.mockito.Mockito.clearInvocations(pendingRepository);
        service.saveExtracted(userId, secondDocumentId, List.of(row("2025-03-04", 10, "EXPENSE", "POS  PURCHASE  STARBUCKS", null)));
        PendingTransaction second = captureSaved().get(0);
        second.setId(UUID.randomUUID());
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(secondDocumentId, userId))
                .thenReturn(List.of(second));

        ConfirmResult result = service.confirm(userId, secondDocumentId, List.of(second.getId()));

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    // ---------- confirm ----------

    @Test
    void confirm_importsSelectedAndDeletesPending() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        PendingTransaction b = pending(LocalDate.of(2025, 3, 5), "2000.00", "INCOME", "Salary");
        PendingTransaction unselected = pending(LocalDate.of(2025, 3, 6), "5.00", "EXPENSE", "Gum");
        UUID categoryId = UUID.randomUUID();
        a.setCategoryId(categoryId);
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(unselected, b, a));

        ConfirmResult result = service.confirm(userId, documentId, List.of(a.getId(), b.getId()));

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Transaction::getDescription)
                .containsExactlyInAnyOrder("Coffee", "Salary");
        Transaction coffee = captor.getAllValues().stream()
                .filter(t -> t.getDescription().equals("Coffee")).findFirst().orElseThrow();
        assertThat(coffee.getUserId()).isEqualTo(userId);
        assertThat(coffee.getCategoryId()).isEqualTo(categoryId);
        assertThat(coffee.getAmount()).isEqualByComparingTo("10.00");
        assertThat(coffee.getType()).isEqualTo("EXPENSE");
        assertThat(coffee.getTransactionDate()).isEqualTo(LocalDate.of(2025, 3, 4));
        verify(pendingRepository).deleteByDocumentIdAndUserId(documentId, userId);
        // many rows imported, but alerts are evaluated once for the whole batch
        verify(alertService, times(1)).evaluateAfterChange(userId);
    }

    @Test
    void confirm_doesNotEvaluateAlertsWhenNothingImported() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a));
        when(transactionRepository.existsDuplicate(userId, a.getTransactionDate(), a.getAmount(), "EXPENSE", "coffee"))
                .thenReturn(true);

        service.confirm(userId, documentId, List.of(a.getId()));

        verify(alertService, never()).evaluateAfterChange(any());
    }

    @Test
    void confirm_skipsDuplicateOfExistingTransaction() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a));
        when(transactionRepository.existsDuplicate(userId, a.getTransactionDate(), a.getAmount(), "EXPENSE", "coffee"))
                .thenReturn(true);

        ConfirmResult result = service.confirm(userId, documentId, List.of(a.getId()));

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(pendingRepository).deleteByDocumentIdAndUserId(documentId, userId);
    }

    @Test
    void confirm_skipsRowRepeatedWithinBatch() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        PendingTransaction b = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "coffee");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a, b));

        ConfirmResult result = service.confirm(userId, documentId, List.of(a.getId(), b.getId()));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    void confirm_skipsDuplicateWhenDescriptionIsNull() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", null);
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a));
        when(transactionRepository.existsDuplicate(userId, a.getTransactionDate(), a.getAmount(), "EXPENSE", ""))
                .thenReturn(true);

        ConfirmResult result = service.confirm(userId, documentId, List.of(a.getId()));

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository).existsDuplicate(userId, a.getTransactionDate(), a.getAmount(), "EXPENSE", "");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void confirm_skipsDuplicateWhenDescriptionIsBlank() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "   ");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a));
        when(transactionRepository.existsDuplicate(userId, a.getTransactionDate(), a.getAmount(), "EXPENSE", ""))
                .thenReturn(true);

        ConfirmResult result = service.confirm(userId, documentId, List.of(a.getId()));

        assertThat(result.skipped()).isEqualTo(1);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void normalizeDescription_trimsCollapsesAndLowerCases() {
        assertThat(PendingTransactionService.normalizeDescription("  Coffee   SHOP ")).isEqualTo("coffee shop");
        assertThat(PendingTransactionService.normalizeDescription(null)).isEqualTo("");
    }

    @Test
    void confirm_ignoresRowsOfOtherUser() {
        // The user-scoped query only returns this user's rows; the requested IDs (another user's) match none of them.
        PendingTransaction own = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Own row");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(own));

        ConfirmResult result = service.confirm(userId, documentId, List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void confirm_twiceImportsOnce() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a))
                .thenReturn(List.of());

        ConfirmResult first = service.confirm(userId, documentId, List.of(a.getId()));
        ConfirmResult second = service.confirm(userId, documentId, List.of(a.getId()));

        assertThat(first.imported()).isEqualTo(1);
        assertThat(second.imported()).isEqualTo(0);
        assertThat(second.skipped()).isEqualTo(0);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    // ---------- update ----------

    @Test
    void update_rejectsNonPositiveAmount() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 4))
                .amount(BigDecimal.ZERO)
                .type("EXPENSE")
                .description("Coffee")
                .build();

        assertThatThrownBy(() -> service.update(userId, a.getId(), req))
                .isInstanceOf(IllegalArgumentException.class);
        verify(pendingRepository, never()).save(any(PendingTransaction.class));
    }

    @Test
    void update_foreignRowThrowsNotFound() {
        UUID foreignId = UUID.randomUUID();
        when(pendingRepository.findByIdAndUserId(foreignId, userId)).thenReturn(Optional.empty());

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 4))
                .amount(new BigDecimal("5"))
                .type("EXPENSE")
                .build();

        assertThatThrownBy(() -> service.update(userId, foreignId, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_appliesChanges() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        Category food = category("Food");
        when(pendingRepository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(food));

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 7))
                .amount(new BigDecimal("12.345"))
                .type("income")
                .description("Refund")
                .categoryId(food.getId())
                .build();

        PendingTransactionResponse resp = service.update(userId, a.getId(), req);

        assertThat(resp.getId()).isEqualTo(a.getId());
        assertThat(resp.getTransactionDate()).isEqualTo(LocalDate.of(2025, 3, 7));
        assertThat(resp.getAmount()).isEqualByComparingTo("12.35");
        assertThat(resp.getType()).isEqualTo("INCOME");
        assertThat(resp.getDescription()).isEqualTo("Refund");
        assertThat(resp.getCategoryId()).isEqualTo(food.getId());
    }

    @Test
    void update_storesDescriptionWithCollapsedWhitespace() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 4))
                .amount(new BigDecimal("10"))
                .type("EXPENSE")
                .description("  POS   PURCHASE  Starbucks ")
                .build();

        assertThat(service.update(userId, a.getId(), req).getDescription()).isEqualTo("POS PURCHASE Starbucks");

        req.setDescription("   ");
        assertThat(service.update(userId, a.getId(), req).getDescription()).isNull();
    }

    @Test
    void update_rejectsAmountExceedingColumnPrecision() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 4))
                .amount(new BigDecimal("1e12"))
                .type("EXPENSE")
                .build();

        assertThatThrownBy(() -> service.update(userId, a.getId(), req))
                .isInstanceOf(IllegalArgumentException.class);
        verify(pendingRepository, never()).save(any(PendingTransaction.class));
    }

    @Test
    void update_rejectsCategoryNotVisibleToUser() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByIdAndUserId(a.getId(), userId)).thenReturn(Optional.of(a));
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(category("Food")));

        UpdatePendingTransactionRequest req = UpdatePendingTransactionRequest.builder()
                .transactionDate(LocalDate.of(2025, 3, 4))
                .amount(new BigDecimal("5"))
                .type("EXPENSE")
                .categoryId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> service.update(userId, a.getId(), req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(pendingRepository, never()).save(any(PendingTransaction.class));
    }

    // ---------- list ----------

    @Test
    void list_returnsUserScopedRows() {
        PendingTransaction a = pending(LocalDate.of(2025, 3, 4), "10.00", "EXPENSE", "Coffee");
        when(pendingRepository.findByDocumentIdAndUserIdOrderByTransactionDateDesc(documentId, userId))
                .thenReturn(List.of(a));

        List<PendingTransactionResponse> result = service.list(userId, documentId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(a.getId());
        assertThat(result.get(0).getDocumentId()).isEqualTo(documentId);
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private List<PendingTransaction> captureSaved() {
        ArgumentCaptor<List<PendingTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(pendingRepository).saveAll(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static Map<String, Object> row(String date, Object amount, String type, String description, String category) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("amount", amount);
        m.put("type", type);
        m.put("description", description);
        m.put("suggested_category", category);
        return m;
    }

    private static Category category(String name) {
        return Category.builder().id(UUID.randomUUID()).name(name).build();
    }

    private PendingTransaction pending(LocalDate date, String amount, String type, String description) {
        BigDecimal amt = new BigDecimal(amount);
        return PendingTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .documentId(documentId)
                .transactionDate(date)
                .amount(amt)
                .type(type)
                .description(description)
                .dedupHash(PendingTransactionService.computeDedupHash(date, amt, type, description))
                .build();
    }
}
