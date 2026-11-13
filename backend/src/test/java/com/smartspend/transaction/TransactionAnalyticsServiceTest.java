package com.smartspend.transaction;

import com.smartspend.category.Category;
import com.smartspend.category.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionAnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @InjectMocks
    private TransactionAnalyticsService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID food = UUID.randomUUID();
    private final UUID fun = UUID.randomUUID();

    private static List<Object[]> rows(Object[]... r) {
        return new ArrayList<>(List.of(r));
    }

    @Test
    void breaksDownByCategoryWithShareAndChangeVersusPreviousPeriod() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        // previous period: same length (30 days) ending the day before `from`
        LocalDate prevTo = LocalDate.of(2026, 10, 31);
        LocalDate prevFrom = LocalDate.of(2026, 10, 2);
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of(
                Category.builder().id(food).name("Food").build(),
                Category.builder().id(fun).name("Fun").build()));
        when(transactionRepository.sumExpensesGroupedByCategory(userId, from, to)).thenReturn(rows(
                new Object[]{fun, new BigDecimal("100.00")},
                new Object[]{food, new BigDecimal("300.00")},
                new Object[]{null, new BigDecimal("100.00")}));
        when(transactionRepository.sumExpensesGroupedByCategory(userId, prevFrom, prevTo)).thenReturn(rows(
                new Object[]{food, new BigDecimal("200.00")}));
        lenient().when(transactionRepository.topMerchants(any(), any(), any(), any(Pageable.class)))
                .thenReturn(rows(new Object[]{"netflix", new BigDecimal("15.99"), 1L}));

        CategoryAnalyticsResponse r = service.byCategory(userId, from, to);

        assertThat(r.totalExpenses()).isEqualByComparingTo("500.00");
        assertThat(r.previousTotalExpenses()).isEqualByComparingTo("200.00");
        assertThat(r.changePercent()).isEqualTo(150.0);
        assertThat(r.categories()).extracting(CategoryAnalyticsResponse.CategorySpend::name)
                .containsExactly("Food", "Fun", "Uncategorized");
        CategoryAnalyticsResponse.CategorySpend foodRow = r.categories().get(0);
        assertThat(foodRow.sharePercent()).isEqualTo(60.0);
        assertThat(foodRow.changePercent()).isEqualTo(50.0);
        assertThat(r.categories().get(1).changePercent()).isNull(); // no previous spend
        assertThat(r.topMerchants()).hasSize(1);
        assertThat(r.topMerchants().get(0).merchant()).isEqualTo("netflix");
    }

    @Test
    void emptyPeriodGivesZeroTotalsAndNoChange() {
        when(categoryRepository.findByUserIdOrIsSystemTrue(userId)).thenReturn(List.of());
        when(transactionRepository.sumExpensesGroupedByCategory(any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.topMerchants(any(), any(), any(), any(Pageable.class))).thenReturn(List.of());

        CategoryAnalyticsResponse r = service.byCategory(userId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        assertThat(r.totalExpenses()).isEqualByComparingTo("0");
        assertThat(r.changePercent()).isNull();
        assertThat(r.categories()).isEmpty();
    }

    @Test
    void rejectsReversedRange() {
        assertThatThrownBy(() -> service.byCategory(userId, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
