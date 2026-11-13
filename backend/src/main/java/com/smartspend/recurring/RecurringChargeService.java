package com.smartspend.recurring;

import com.smartspend.transaction.PendingTransactionService;
import com.smartspend.transaction.Transaction;
import com.smartspend.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds monthly subscriptions in expense history without any LLM: same normalized description, at least
 * {@value #MIN_OCCURRENCES} charges, consecutive charges {@value #MIN_GAP_DAYS}-{@value #MAX_GAP_DAYS} days apart,
 * and earlier amounts within 10% of each other. The latest charge may differ (that is a price change).
 */
@Service
@RequiredArgsConstructor
public class RecurringChargeService {

    static final int MIN_OCCURRENCES = 3;
    static final int MIN_GAP_DAYS = 25;
    static final int MAX_GAP_DAYS = 36;
    /** A subscription not seen for this long is treated as cancelled. */
    static final int ACTIVE_WITHIN_DAYS = 45;
    static final int LOOKBACK_DAYS = 400;
    private static final BigDecimal BASELINE_TOLERANCE = new BigDecimal("0.10");
    private static final BigDecimal PRICE_HIKE_THRESHOLD = new BigDecimal("1.05");
    private static final BigDecimal LATEST_MIN = new BigDecimal("0.5");
    private static final BigDecimal LATEST_MAX = new BigDecimal("2.0");

    private final TransactionRepository transactionRepository;

    public List<RecurringChargeResponse> detectFor(UUID userId, LocalDate today) {
        List<Transaction> expenses = transactionRepository
                .findByUserIdAndTypeAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
                        userId, "EXPENSE", today.minusDays(LOOKBACK_DAYS));
        return detect(expenses, today);
    }

    /** Pure detection over expenses (any order). */
    public static List<RecurringChargeResponse> detect(List<Transaction> expenses, LocalDate today) {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : expenses) {
            String key = PendingTransactionService.normalizeDescription(t.getDescription());
            if (key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<RecurringChargeResponse> result = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : groups.entrySet()) {
            RecurringChargeResponse r = analyse(e.getKey(), e.getValue(), today);
            if (r != null) {
                result.add(r);
            }
        }
        result.sort(Comparator.comparing(RecurringChargeResponse::yearlyCost).reversed());
        return result;
    }

    private static RecurringChargeResponse analyse(String key, List<Transaction> group, LocalDate today) {
        if (group.size() < MIN_OCCURRENCES) {
            return null;
        }
        List<Transaction> sorted = group.stream()
                .sorted(Comparator.comparing(Transaction::getTransactionDate))
                .toList();

        for (int i = 1; i < sorted.size(); i++) {
            long gap = ChronoUnit.DAYS.between(
                    sorted.get(i - 1).getTransactionDate(), sorted.get(i).getTransactionDate());
            if (gap < MIN_GAP_DAYS || gap > MAX_GAP_DAYS) {
                return null;
            }
        }

        Transaction latest = sorted.get(sorted.size() - 1);
        List<BigDecimal> earlier = sorted.subList(0, sorted.size() - 1).stream()
                .map(Transaction::getAmount).sorted().toList();
        BigDecimal baseline = earlier.get(earlier.size() / 2);
        if (baseline.signum() <= 0) {
            return null;
        }
        BigDecimal low = baseline.multiply(BigDecimal.ONE.subtract(BASELINE_TOLERANCE));
        BigDecimal high = baseline.multiply(BigDecimal.ONE.add(BASELINE_TOLERANCE));
        for (BigDecimal a : earlier) {
            if (a.compareTo(low) < 0 || a.compareTo(high) > 0) {
                return null;
            }
        }
        BigDecimal ratio = latest.getAmount().divide(baseline, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(LATEST_MIN) < 0 || ratio.compareTo(LATEST_MAX) > 0) {
            return null;
        }

        LocalDate lastSeen = latest.getTransactionDate();
        if (ChronoUnit.DAYS.between(lastSeen, today) > ACTIVE_WITHIN_DAYS) {
            return null;
        }

        boolean priceHike = ratio.compareTo(PRICE_HIKE_THRESHOLD) > 0;
        String display = latest.getDescription() == null ? key : latest.getDescription().trim();
        return new RecurringChargeResponse(
                key,
                display,
                latest.getAmount(),
                baseline,
                latest.getAmount().multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP),
                sorted.size(),
                lastSeen,
                lastSeen.plusMonths(1),
                priceHike);
    }
}
