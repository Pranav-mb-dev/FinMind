package com.smartspend.budget;

import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import com.smartspend.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetStatusServiceTest {

    @Mock
    private BudgetRepository budgetRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @InjectMocks
    private BudgetStatusService service;

    private final UUID userId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 11, 13);
    private final LocalDate windowStart = LocalDate.of(2026, 11, 1);
    private final LocalDate windowEnd = LocalDate.of(2026, 11, 30);

    private Budget budget(UUID categoryId, String amount) {
        return Budget.builder().id(UUID.randomUUID()).userId(userId).categoryId(categoryId)
                .amount(new BigDecimal(amount)).period("MONTHLY").startDate(windowStart).build();
    }

    @Test
    void noBudgetsReturnsEmptyWithoutQueryingSpend() {
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.statusFor(userId, today)).isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void categoryBudgetUsesCategorySpendAndReportsWarning() {
        UUID food = UUID.randomUUID();
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget(food, "500.00")));
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId))
                .thenReturn(List.of(Category.builder().id(food).name("Food").build()));
        when(transactionRepository.sumExpensesByCategoryId(userId, food, windowStart, windowEnd))
                .thenReturn(new BigDecimal("400.00"));

        BudgetStatusResponse s = service.statusFor(userId, today).get(0);

        assertThat(s.categoryName()).isEqualTo("Food");
        assertThat(s.spent()).isEqualByComparingTo("400.00");
        assertThat(s.remaining()).isEqualByComparingTo("100.00");
        assertThat(s.percentUsed()).isEqualTo(80.0);
        assertThat(s.status()).isEqualTo("WARNING");
    }

    @Test
    void overallBudgetUsesTotalExpensesAndReportsExceeded() {
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget(null, "1000.00")));
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());
        when(transactionRepository.sumByUserIdAndTypeAndDateRange(userId, "EXPENSE", windowStart, windowEnd))
                .thenReturn(new BigDecimal("1250.00"));

        BudgetStatusResponse s = service.statusFor(userId, today).get(0);

        assertThat(s.categoryName()).isEqualTo("Overall");
        assertThat(s.remaining()).isEqualByComparingTo("-250.00");
        assertThat(s.percentUsed()).isEqualTo(125.0);
        assertThat(s.status()).isEqualTo("EXCEEDED");
    }

    @Test
    void lowSpendIsOk() {
        UUID food = UUID.randomUUID();
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget(food, "500.00")));
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());
        when(transactionRepository.sumExpensesByCategoryId(userId, food, windowStart, windowEnd))
                .thenReturn(new BigDecimal("399.99"));

        BudgetStatusResponse s = service.statusFor(userId, today).get(0);

        assertThat(s.status()).isEqualTo("OK");
        assertThat(s.categoryName()).isEqualTo("Unknown");
        verify(transactionRepository).sumExpensesByCategoryId(userId, food, windowStart, windowEnd);
    }
}
