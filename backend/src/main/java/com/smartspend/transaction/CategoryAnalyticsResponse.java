package com.smartspend.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Expense breakdown for [from, to] compared with the immediately preceding period of the same length.
 * Change percentages are null when the previous total is zero.
 */
public record CategoryAnalyticsResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalExpenses,
        BigDecimal previousTotalExpenses,
        Double changePercent,
        List<CategorySpend> categories,
        List<MerchantSpend> topMerchants) {

    public record CategorySpend(
            UUID categoryId,
            String name,
            BigDecimal total,
            BigDecimal previousTotal,
            Double changePercent,
            double sharePercent) {
    }

    public record MerchantSpend(String merchant, BigDecimal total, long count) {
    }
}
