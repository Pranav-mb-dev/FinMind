package com.smartspend.alert;

import com.smartspend.budget.BudgetStatusResponse;
import com.smartspend.budget.BudgetStatusService;
import com.smartspend.recurring.RecurringChargeResponse;
import com.smartspend.recurring.RecurringChargeService;
import com.smartspend.transaction.TransactionRepository;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Creates alerts when budgets cross 80% / 100% or a recurring charge appears or goes up. Each alert has a
 * dedupe key, so the same event alerts at most once per user.
 */
@Service
@Slf4j
public class AlertService {

    static final String BUDGET_WARNING = "BUDGET_WARNING";
    static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    static final String NEW_SUBSCRIPTION = "NEW_SUBSCRIPTION";
    static final String PRICE_HIKE = "PRICE_HIKE";

    private final AlertRepository alertRepository;
    private final BudgetStatusService budgetStatusService;
    private final RecurringChargeService recurringChargeService;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AlertWebhookNotifier notifier;
    private final TransactionTemplate newTransaction;

    public AlertService(AlertRepository alertRepository,
                        BudgetStatusService budgetStatusService,
                        RecurringChargeService recurringChargeService,
                        TransactionRepository transactionRepository,
                        UserRepository userRepository,
                        AlertWebhookNotifier notifier,
                        PlatformTransactionManager transactionManager) {
        this.alertRepository = alertRepository;
        this.budgetStatusService = budgetStatusService;
        this.recurringChargeService = recurringChargeService;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.notifier = notifier;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Call after a user's transactions changed. Runs after the caller's transaction commits (so it sees the
     * new rows) in its own transaction, and never throws: a problem here must not undo or fail the change.
     */
    public void evaluateAfterChange(UUID userId) {
        Runnable task = () -> {
            try {
                newTransaction.executeWithoutResult(status -> {
                    LocalDate today = LocalDate.now();
                    evaluateBudgets(userId, today);
                    evaluateRecurring(userId, today);
                });
            } catch (RuntimeException e) {
                log.warn("Alert evaluation failed for user {}: {}", userId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** Nightly scan used by n8n: recurring-charge alerts for every user with transactions. Returns alerts created. */
    public int scanAllUsers() {
        int created = 0;
        LocalDate today = LocalDate.now();
        for (UUID userId : transactionRepository.findDistinctUserIds()) {
            try {
                created += newTransaction.execute(status -> evaluateRecurring(userId, today));
            } catch (RuntimeException e) {
                log.warn("Recurring scan failed for user {}: {}", userId, e.getMessage());
            }
        }
        return created;
    }

    int evaluateBudgets(UUID userId, LocalDate today) {
        int created = 0;
        for (BudgetStatusResponse s : budgetStatusService.statusFor(userId, today)) {
            String base = "budget:" + s.budgetId() + ":" + s.windowStart() + ":";
            if (s.percentUsed() >= BudgetStatusResponse.EXCEEDED_PERCENT) {
                String msg = String.format("You have used %.0f%% of your %s budget (%s of %s).",
                        s.percentUsed(), s.categoryName(), s.spent().toPlainString(), s.limit().toPlainString());
                if (createIfNew(userId, BUDGET_EXCEEDED, "CRITICAL", msg, base + "100")) {
                    created++;
                }
            } else if (s.percentUsed() >= BudgetStatusResponse.WARNING_PERCENT
                    && !alertRepository.existsByUserIdAndDedupeKey(userId, base + "100")) {
                String msg = String.format("You have used %.0f%% of your %s budget (%s of %s).",
                        s.percentUsed(), s.categoryName(), s.spent().toPlainString(), s.limit().toPlainString());
                if (createIfNew(userId, BUDGET_WARNING, "WARNING", msg, base + "80")) {
                    created++;
                }
            }
        }
        return created;
    }

    int evaluateRecurring(UUID userId, LocalDate today) {
        int created = 0;
        List<RecurringChargeResponse> charges = recurringChargeService.detectFor(userId, today);
        for (RecurringChargeResponse c : charges) {
            if (c.priceHike()) {
                String msg = String.format("%s went up from %s to %s per month (%s a year).",
                        c.description(), c.previousAmount().toPlainString(), c.amount().toPlainString(),
                        c.yearlyCost().toPlainString());
                String key = "pricehike:" + c.key() + ":" + c.amount().toPlainString();
                if (createIfNew(userId, PRICE_HIKE, "WARNING", msg, key)) {
                    created++;
                }
            }
            String msg = String.format("Recurring charge detected: %s, %s a month (%s a year).",
                    c.description(), c.amount().toPlainString(), c.yearlyCost().toPlainString());
            if (createIfNew(userId, NEW_SUBSCRIPTION, "INFO", msg, "recurring:" + c.key())) {
                created++;
            }
        }
        return created;
    }

    private boolean createIfNew(UUID userId, String type, String severity, String message, String dedupeKey) {
        String key = dedupeKey.length() > 200 ? dedupeKey.substring(0, 200) : dedupeKey;
        if (alertRepository.existsByUserIdAndDedupeKey(userId, key)) {
            return false;
        }
        Alert saved = alertRepository.save(Alert.builder()
                .userId(userId)
                .type(type)
                .severity(severity)
                .message(message)
                .dedupeKey(key)
                .build());
        if (notifier.isEnabled()) {
            String email = userRepository.findById(userId).map(User::getEmail).orElse(null);
            notifier.sendAfterCommit(saved, email);
        }
        return true;
    }
}
