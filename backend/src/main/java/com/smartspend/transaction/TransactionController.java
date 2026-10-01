package com.smartspend.transaction;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<TransactionResponse>> getTransactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID userId = getCurrentUserId();
        List<Transaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = transactionRepository
                    .findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(userId, startDate, endDate);
        } else {
            transactions = transactionRepository.findByUserIdOrderByTransactionDateDesc(userId);
        }
        List<TransactionResponse> responses = transactions.stream().map(this::toResponse).toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
        UUID userId = getCurrentUserId();
        Transaction transaction = Transaction.builder()
                .userId(userId)
                .categoryId(request.getCategoryId())
                .amount(request.getAmount())
                .type(request.getType().toUpperCase())
                .description(request.getDescription())
                .transactionDate(request.getTransactionDate())
                .build();
        Transaction saved = transactionRepository.save(transaction);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/summary")
    public ResponseEntity<TransactionSummaryResponse> getSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID userId = getCurrentUserId();
        BigDecimal totalIncome = transactionRepository.sumByUserIdAndTypeAndDateRange(userId, "INCOME", startDate, endDate);
        BigDecimal totalExpenses = transactionRepository.sumByUserIdAndTypeAndDateRange(userId, "EXPENSE", startDate, endDate);
        return ResponseEntity.ok(TransactionSummaryResponse.builder()
                .totalIncome(totalIncome)
                .totalExpenses(totalExpenses)
                .netBalance(totalIncome.subtract(totalExpenses))
                .startDate(startDate)
                .endDate(endDate)
                .build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
        if (!userId.equals(transaction.getUserId())) {
            throw new ResourceNotFoundException("Transaction not found");
        }
        transactionRepository.delete(transaction);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return user.getId();
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .categoryId(t.getCategoryId())
                .amount(t.getAmount())
                .type(t.getType())
                .description(t.getDescription())
                .transactionDate(t.getTransactionDate())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
