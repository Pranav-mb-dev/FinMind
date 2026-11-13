package com.smartspend.alert;

import com.smartspend.budget.BudgetStatusResponse;
import com.smartspend.budget.BudgetStatusService;
import com.smartspend.recurring.RecurringChargeResponse;
import com.smartspend.recurring.RecurringChargeService;
import com.smartspend.transaction.TransactionRepository;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {

    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final BudgetStatusService budgetStatusService = mock(BudgetStatusService.class);
    private final RecurringChargeService recurringChargeService = mock(RecurringChargeService.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AlertWebhookNotifier notifier = mock(AlertWebhookNotifier.class);
    private final AlertService service = new AlertService(alertRepository, budgetStatusService,
            recurringChargeService, transactionRepository, userRepository, notifier,
            mock(PlatformTransactionManager.class));

    private final UUID userId = UUID.randomUUID();
    private final UUID budgetId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 11, 13);
    private final LocalDate windowStart = LocalDate.of(2026, 11, 1);

    @BeforeEach
    void setUp() {
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alertRepository.existsByUserIdAndDedupeKey(any(), anyString())).thenReturn(false);
        when(recurringChargeService.detectFor(userId, today)).thenReturn(List.of());
    }

    private BudgetStatusResponse status(double percent, LocalDate start) {
        return new BudgetStatusResponse(budgetId, UUID.randomUUID(), "Food", "MONTHLY",
                new BigDecimal("500.00"), BigDecimal.valueOf(percent * 5).setScale(2),
                BigDecimal.ZERO, percent, "X", start, start.plusMonths(1).minusDays(1));
    }

    private Alert singleSavedAlert() {
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------- budgets ----------

    @Test
    void below80PercentCreatesNothing() {
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(79.9, windowStart)));

        assertThat(service.evaluateBudgets(userId, today)).isZero();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void at80PercentCreatesWarning() {
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(80.0, windowStart)));

        assertThat(service.evaluateBudgets(userId, today)).isEqualTo(1);

        Alert a = singleSavedAlert();
        assertThat(a.getType()).isEqualTo("BUDGET_WARNING");
        assertThat(a.getSeverity()).isEqualTo("WARNING");
        assertThat(a.getDedupeKey()).isEqualTo("budget:" + budgetId + ":2026-11-01:80");
        assertThat(a.getMessage()).contains("80%").contains("Food");
    }

    @Test
    void at100PercentCreatesCriticalExceededOnly() {
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(112.0, windowStart)));

        assertThat(service.evaluateBudgets(userId, today)).isEqualTo(1);

        Alert a = singleSavedAlert();
        assertThat(a.getType()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(a.getSeverity()).isEqualTo("CRITICAL");
        assertThat(a.getDedupeKey()).endsWith(":100");
    }

    @Test
    void sameThresholdInSamePeriodAlertsOnce() {
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(85.0, windowStart)));
        when(alertRepository.existsByUserIdAndDedupeKey(userId, "budget:" + budgetId + ":2026-11-01:80"))
                .thenReturn(true);

        assertThat(service.evaluateBudgets(userId, today)).isZero();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void newPeriodAlertsAgain() {
        LocalDate nextStart = LocalDate.of(2026, 12, 1);
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(90.0, nextStart)));
        // the November alert exists, December's does not
        when(alertRepository.existsByUserIdAndDedupeKey(userId, "budget:" + budgetId + ":2026-11-01:80"))
                .thenReturn(true);

        assertThat(service.evaluateBudgets(userId, today)).isEqualTo(1);
        assertThat(singleSavedAlert().getDedupeKey()).isEqualTo("budget:" + budgetId + ":2026-12-01:80");
    }

    @Test
    void doesNotRaiseWarningAfterExceededAlreadySent() {
        // spend dropped back under 100% (e.g. a deleted transaction) after the 100% alert was sent
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(90.0, windowStart)));
        when(alertRepository.existsByUserIdAndDedupeKey(userId, "budget:" + budgetId + ":2026-11-01:100"))
                .thenReturn(true);

        assertThat(service.evaluateBudgets(userId, today)).isZero();
        verify(alertRepository, never()).save(any());
    }

    // ---------- recurring ----------

    private RecurringChargeResponse charge(boolean priceHike) {
        return new RecurringChargeResponse("netflix", "Netflix", new BigDecimal("17.99"),
                new BigDecimal(priceHike ? "15.99" : "17.99"), new BigDecimal("215.88"), 5,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5), priceHike);
    }

    @Test
    void newRecurringChargeCreatesInfoAlertOnce() {
        when(recurringChargeService.detectFor(userId, today)).thenReturn(List.of(charge(false)));

        assertThat(service.evaluateRecurring(userId, today)).isEqualTo(1);

        Alert a = singleSavedAlert();
        assertThat(a.getType()).isEqualTo("NEW_SUBSCRIPTION");
        assertThat(a.getSeverity()).isEqualTo("INFO");
        assertThat(a.getDedupeKey()).isEqualTo("recurring:netflix");
    }

    @Test
    void knownRecurringChargeCreatesNothing() {
        when(recurringChargeService.detectFor(userId, today)).thenReturn(List.of(charge(false)));
        when(alertRepository.existsByUserIdAndDedupeKey(userId, "recurring:netflix")).thenReturn(true);

        assertThat(service.evaluateRecurring(userId, today)).isZero();
    }

    @Test
    void priceHikeCreatesWarningInAdditionToNewSubscription() {
        when(recurringChargeService.detectFor(userId, today)).thenReturn(List.of(charge(true)));

        assertThat(service.evaluateRecurring(userId, today)).isEqualTo(2);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Alert::getType)
                .containsExactlyInAnyOrder("PRICE_HIKE", "NEW_SUBSCRIPTION");
        assertThat(captor.getAllValues()).filteredOn(a -> a.getType().equals("PRICE_HIKE"))
                .first().extracting(Alert::getDedupeKey).isEqualTo("pricehike:netflix:17.99");
    }

    // ---------- webhook ----------

    @Test
    void newAlertIsPushedToWebhookWithUserEmailWhenEnabled() {
        when(notifier.isEnabled()).thenReturn(true);
        User user = new User();
        user.setEmail("a@b.c");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(100.0, windowStart)));

        service.evaluateBudgets(userId, today);

        verify(notifier).sendAfterCommit(any(Alert.class), org.mockito.ArgumentMatchers.eq("a@b.c"));
    }

    @Test
    void webhookIsNotCalledWhenDisabled() {
        when(notifier.isEnabled()).thenReturn(false);
        when(budgetStatusService.statusFor(userId, today)).thenReturn(List.of(status(100.0, windowStart)));

        service.evaluateBudgets(userId, today);

        verify(notifier, never()).sendAfterCommit(any(), any());
        verify(userRepository, never()).findById(any());
    }
}
