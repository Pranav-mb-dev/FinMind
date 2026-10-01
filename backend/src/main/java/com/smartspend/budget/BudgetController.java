package com.smartspend.budget;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
/.
    @GetMapping
    public ResponseEntity<List<BudgetResponse>> getBudgets() {
        UUID userId = getCurrentUserId();
        List<BudgetResponse> budgets = budgetRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(budgets);
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        UUID userId = getCurrentUserId();
        Budget budget = Budget.builder()
                .userId(userId)
                .categoryId(request.getCategoryId())
                .amount(request.getAmount())
                .period(request.getPeriod().toUpperCase())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .build();
        Budget saved = budgetRepository.save(budget);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BudgetResponse> updateBudget(@PathVariable UUID id, @Valid @RequestBody CreateBudgetRequest request) {
        UUID userId = getCurrentUserId();
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Budget not found"));
        if (!userId.equals(budget.getUserId())) {
            throw new ResourceNotFoundException("Budget not found");
        }
        budget.setCategoryId(request.getCategoryId());
        budget.setAmount(request.getAmount());
        budget.setPeriod(request.getPeriod().toUpperCase());
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        Budget saved = budgetRepository.save(budget);
        return ResponseEntity.ok(toResponse(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Budget not found"));
        if (!userId.equals(budget.getUserId())) {
            throw new ResourceNotFoundException("Budget not found");
        }
        budgetRepository.delete(budget);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return user.getId();
    }

    private BudgetResponse toResponse(Budget b) {
        return BudgetResponse.builder()
                .id(b.getId())
                .categoryId(b.getCategoryId())
                .amount(b.getAmount())
                .period(b.getPeriod())
                .startDate(b.getStartDate())
                .endDate(b.getEndDate())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
