package com.smartspend.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Spending against one budget over its current window. {@code status} is OK, WARNING (>= 80%) or EXCEEDED (>= 100%). */
public record BudgetStatusResponse(
        UUID budgetId,
        UUID categoryId,
        String categoryName,
        String period,
        BigDecimal limit,
        BigDecimal spent,
        BigDecimal remaining,
        double percentUsed,
        String status,
        LocalDate windowStart,
        LocalDate windowEnd) {

    public static final double WARNING_PERCENT = 80.0;
    public static final double EXCEEDED_PERCENT = 100.0;
}
