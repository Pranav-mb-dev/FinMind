package com.smartspend.budget;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetResponse {
    private UUID id;
    private UUID categoryId;
    private BigDecimal amount;
    private String period;
    private LocalDate startDate;
    private LocalDate endDate;
    private Instant createdAt;
}
