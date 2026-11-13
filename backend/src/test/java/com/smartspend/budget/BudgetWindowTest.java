package com.smartspend.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetWindowTest {

    private static Budget budget(String period, LocalDate start, LocalDate end) {
        return Budget.builder().period(period).startDate(start).endDate(end).build();
    }

    @Test
    void explicitEndDateIsUsedAsIs() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("MONTHLY", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)), LocalDate.of(2026, 2, 10));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(w.end()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void openEndedMonthlyRollsToCurrentMonth() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("MONTHLY", LocalDate.of(2026, 1, 1), null), LocalDate.of(2026, 11, 13));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(w.end()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void monthlyWindowStartingMidMonth() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("MONTHLY", LocalDate.of(2026, 1, 15), null), LocalDate.of(2026, 11, 13));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(w.end()).isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    void lastDayOfWindowStillBelongsToIt() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("MONTHLY", LocalDate.of(2026, 1, 1), null), LocalDate.of(2026, 11, 30));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(w.end()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void weeklyWindow() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("WEEKLY", LocalDate.of(2026, 11, 2), null), LocalDate.of(2026, 11, 13));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(w.end()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void yearlyWindow() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("YEARLY", LocalDate.of(2024, 4, 1), null), LocalDate.of(2026, 11, 13));
        assertThat(w.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(w.end()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void budgetThatHasNotStartedYetUsesItsFirstWindow() {
        BudgetWindow w = BudgetWindow.resolve(
                budget("MONTHLY", LocalDate.of(2027, 1, 1), null), LocalDate.of(2026, 11, 13));
        assertThat(w.start()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(w.end()).isEqualTo(LocalDate.of(2027, 1, 31));
    }
}
