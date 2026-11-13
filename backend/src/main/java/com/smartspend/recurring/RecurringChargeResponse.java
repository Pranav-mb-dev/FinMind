package com.smartspend.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A detected monthly recurring charge. {@code key} is the normalized description (stable id for alert
 * dedupe); {@code priceHike} is true when the latest charge is more than 5% above the earlier baseline.
 */
public record RecurringChargeResponse(
        String key,
        String description,
        BigDecimal amount,
        BigDecimal previousAmount,
        BigDecimal yearlyCost,
        int occurrences,
        LocalDate lastSeen,
        LocalDate nextExpected,
        boolean priceHike) {
}
