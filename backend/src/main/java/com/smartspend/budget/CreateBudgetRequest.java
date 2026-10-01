package com.smartspend.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBudgetRequest {

    private UUID categoryId;

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String period;

    @NotNull
    private LocalDate startDate;

    private LocalDate endDate;
}
