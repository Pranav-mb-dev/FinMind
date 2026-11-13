package com.smartspend.transaction;

import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionAnalyticsService {

    static final int TOP_MERCHANTS = 5;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public CategoryAnalyticsResponse byCategory(UUID userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        Map<UUID, String> names = new HashMap<>();
        for (Category c : categoryRepository.findByUserIdOrIsSystemTrue(userId)) {
            names.put(c.getId(), c.getName());
        }

        Map<UUID, BigDecimal> current = toMap(transactionRepository.sumExpensesGroupedByCategory(userId, from, to));
        Map<UUID, BigDecimal> previous = toMap(transactionRepository.sumExpensesGroupedByCategory(userId, prevFrom, prevTo));

        BigDecimal total = current.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevTotal = previous.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CategoryAnalyticsResponse.CategorySpend> categories = current.entrySet().stream()
                .map(e -> {
                    BigDecimal prev = previous.getOrDefault(e.getKey(), BigDecimal.ZERO);
                    String name = e.getKey() == null ? "Uncategorized" : names.getOrDefault(e.getKey(), "Unknown");
                    return new CategoryAnalyticsResponse.CategorySpend(
                            e.getKey(), name, e.getValue(), prev, change(e.getValue(), prev), share(e.getValue(), total));
                })
                .sorted(Comparator.comparing(CategoryAnalyticsResponse.CategorySpend::total).reversed()
                        .thenComparing(CategoryAnalyticsResponse.CategorySpend::name))
                .toList();

        List<CategoryAnalyticsResponse.MerchantSpend> merchants = transactionRepository
                .topMerchants(userId, from, to, PageRequest.of(0, TOP_MERCHANTS))
                .stream()
                .map(r -> new CategoryAnalyticsResponse.MerchantSpend(
                        (String) r[0], (BigDecimal) r[1], ((Number) r[2]).longValue()))
                .toList();

        return new CategoryAnalyticsResponse(from, to, total, prevTotal, change(total, prevTotal), categories, merchants);
    }

    private static Map<UUID, BigDecimal> toMap(List<Object[]> rows) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (Object[] r : rows) {
            map.merge((UUID) r[0], (BigDecimal) r[1], BigDecimal::add);
        }
        return map;
    }

    static Double change(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double share(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return 0.0;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP).doubleValue();
    }
}
