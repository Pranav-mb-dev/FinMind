package com.smartspend.transaction;

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
public class PendingTransactionResponse {
    private UUID id;
    private UUID documentId;
    private UUID categoryId;
    private BigDecimal amount;
    private String type;
    private String description;
    private LocalDate transactionDate;
    private String suggestedCategory;
    private Instant createdAt;

    public static PendingTransactionResponse from(PendingTransaction p) {
        return PendingTransactionResponse.builder()
                .id(p.getId())
                .documentId(p.getDocumentId())
                .categoryId(p.getCategoryId())
                .amount(p.getAmount())
                .type(p.getType())
                .description(p.getDescription())
                .transactionDate(p.getTransactionDate())
                .suggestedCategory(p.getSuggestedCategory())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
