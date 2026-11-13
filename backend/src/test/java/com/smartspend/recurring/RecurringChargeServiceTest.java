package com.smartspend.recurring;

import com.smartspend.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringChargeServiceTest {

    private final LocalDate today = LocalDate.of(2026, 11, 13);

    private static Transaction tx(String description, String amount, LocalDate date) {
        return Transaction.builder().description(description).amount(new BigDecimal(amount))
                .type("EXPENSE").transactionDate(date).build();
    }

    private List<Transaction> monthly(String description, LocalDate first, int count, String amount) {
        List<Transaction> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(tx(description, amount, first.plusMonths(i)));
        }
        return list;
    }

    @Test
    void detectsMonthlySubscription() {
        List<RecurringChargeResponse> found = RecurringChargeService.detect(
                monthly("Netflix", LocalDate.of(2026, 7, 5), 5, "15.99"), today);

        assertThat(found).hasSize(1);
        RecurringChargeResponse r = found.get(0);
        assertThat(r.key()).isEqualTo("netflix");
        assertThat(r.amount()).isEqualByComparingTo("15.99");
        assertThat(r.yearlyCost()).isEqualByComparingTo("191.88");
        assertThat(r.occurrences()).isEqualTo(5);
        assertThat(r.priceHike()).isFalse();
        assertThat(r.lastSeen()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void normalizesCaseAndWhitespaceWhenGrouping() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(tx("Spotify  AB", "9.99", LocalDate.of(2026, 9, 3)));
        txs.add(tx("spotify ab", "9.99", LocalDate.of(2026, 10, 3)));
        txs.add(tx(" SPOTIFY AB ", "9.99", LocalDate.of(2026, 11, 3)));

        assertThat(RecurringChargeService.detect(txs, today)).hasSize(1);
    }

    @Test
    void flagsPriceHikeOnLatestCharge() {
        List<Transaction> txs = monthly("Netflix", LocalDate.of(2026, 8, 5), 3, "15.99");
        txs.add(tx("Netflix", "17.99", LocalDate.of(2026, 11, 5)));

        RecurringChargeResponse r = RecurringChargeService.detect(txs, today).get(0);

        assertThat(r.priceHike()).isTrue();
        assertThat(r.previousAmount()).isEqualByComparingTo("15.99");
        assertThat(r.amount()).isEqualByComparingTo("17.99");
    }

    @Test
    void ignoresOneOffsAndTooFewCharges() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(tx("Hardware store", "80.00", LocalDate.of(2026, 10, 1)));
        txs.addAll(monthly("Gym", LocalDate.of(2026, 10, 5), 2, "30.00"));

        assertThat(RecurringChargeService.detect(txs, today)).isEmpty();
    }

    @Test
    void ignoresIrregularIntervals() {
        List<Transaction> txs = List.of(
                tx("Coffee shop", "4.50", LocalDate.of(2026, 10, 1)),
                tx("Coffee shop", "4.50", LocalDate.of(2026, 10, 3)),
                tx("Coffee shop", "4.50", LocalDate.of(2026, 11, 9)));

        assertThat(RecurringChargeService.detect(txs, today)).isEmpty();
    }

    @Test
    void ignoresVaryingAmounts() {
        List<Transaction> txs = List.of(
                tx("Electricity", "40.00", LocalDate.of(2026, 8, 10)),
                tx("Electricity", "95.00", LocalDate.of(2026, 9, 10)),
                tx("Electricity", "61.00", LocalDate.of(2026, 10, 10)),
                tx("Electricity", "72.00", LocalDate.of(2026, 11, 10)));

        assertThat(RecurringChargeService.detect(txs, today)).isEmpty();
    }

    @Test
    void ignoresCancelledSubscription() {
        List<Transaction> txs = monthly("Old gym", LocalDate.of(2026, 1, 5), 4, "30.00"); // last: 2026-04-05

        assertThat(RecurringChargeService.detect(txs, today)).isEmpty();
    }

    @Test
    void skipsBlankDescriptions() {
        List<Transaction> txs = monthly("  ", LocalDate.of(2026, 8, 5), 4, "10.00");
        txs.add(tx(null, "10.00", LocalDate.of(2026, 11, 5)));

        assertThat(RecurringChargeService.detect(txs, today)).isEmpty();
    }

    @Test
    void sortsByYearlyCostDescending() {
        List<Transaction> txs = new ArrayList<>();
        txs.addAll(monthly("Small", LocalDate.of(2026, 8, 5), 4, "5.00"));
        txs.addAll(monthly("Big", LocalDate.of(2026, 8, 7), 4, "50.00"));

        assertThat(RecurringChargeService.detect(txs, today)).extracting(RecurringChargeResponse::key)
                .containsExactly("big", "small");
    }
}
