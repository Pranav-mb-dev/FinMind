package com.smartspend.budget;

import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import com.smartspend.transaction.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BudgetStatusService {

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public List<BudgetStatusResponse> statusFor(UUID userId, LocalDate today) {
        List<Budget> budgets = budgetRepository.findByUserId(userId);
        if (budgets.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (Category c : categoryRepository.findByUserIdOrIsSystemTrue(userId)) {
            names.put(c.getId(), c.getName());
        }
        return budgets.stream().map(b -> compute(userId, b, names, today)).toList();
    }

    private BudgetStatusResponse compute(UUID userId, Budget b, Map<UUID, String> names, LocalDate today) {
        BudgetWindow window = BudgetWindow.resolve(b, today);
        BigDecimal spent = b.getCategoryId() == null
                ? transactionRepository.sumByUserIdAndTypeAndDateRange(userId, "EXPENSE", window.start(), window.end())
                : transactionRepository.sumExpensesByCategoryId(userId, b.getCategoryId(), window.start(), window.end());
        if (spent == null) {
            spent = BigDecimal.ZERO;
        }
        BigDecimal limit = b.getAmount();
        // Rounded DOWN so a display value of 80.0 / 100.0 always means the threshold was truly reached
        // (79.998% must not trigger a warning).
        double percent = limit.signum() == 0
                ? 0.0
                : spent.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.DOWN).doubleValue();
        String status = percent >= BudgetStatusResponse.EXCEEDED_PERCENT ? "EXCEEDED"
                : percent >= BudgetStatusResponse.WARNING_PERCENT ? "WARNING" : "OK";
        String name = b.getCategoryId() == null ? "Overall" : names.getOrDefault(b.getCategoryId(), "Unknown");
        return new BudgetStatusResponse(b.getId(), b.getCategoryId(), name, b.getPeriod(), limit, spent,
                limit.subtract(spent), percent, status, window.start(), window.end());
    }
}
