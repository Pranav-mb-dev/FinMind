package com.smartspend.budget;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** The date range a budget is currently measured over. */
public record BudgetWindow(LocalDate start, LocalDate end) {

    /**
     * Budgets with an explicit end date use [startDate, endDate]. Open-ended budgets roll over by period
     * (WEEKLY / MONTHLY / YEARLY) from their start date; the window containing {@code today} is returned,
     * or the first window if the budget has not started yet.
     */
    public static BudgetWindow resolve(Budget budget, LocalDate today) {
        LocalDate start = budget.getStartDate();
        if (budget.getEndDate() != null) {
            return new BudgetWindow(start, budget.getEndDate());
        }
        ChronoUnit unit = unitFor(budget.getPeriod());
        long k = today.isBefore(start) ? 0 : unit.between(start, today);
        LocalDate windowStart = start.plus(k, unit);
        if (windowStart.isAfter(today) && k > 0) {
            k--;
            windowStart = start.plus(k, unit);
        }
        LocalDate windowEnd = start.plus(k + 1, unit).minusDays(1);
        return new BudgetWindow(windowStart, windowEnd);
    }

    private static ChronoUnit unitFor(String period) {
        if (period == null) {
            return ChronoUnit.MONTHS;
        }
        return switch (period.trim().toUpperCase()) {
            case "WEEKLY" -> ChronoUnit.WEEKS;
            case "YEARLY" -> ChronoUnit.YEARS;
            default -> ChronoUnit.MONTHS;
        };
    }
}
