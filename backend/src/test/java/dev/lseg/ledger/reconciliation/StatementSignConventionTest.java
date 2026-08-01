package dev.lseg.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import dev.lseg.ledger.domain.AccountType;

/**
 * Table-driven, one row per account type.
 *
 * <p>Getting this mapping wrong inverts every match in a run, and the resulting
 * report still looks like a plausible reconciliation — which is why the rule is
 * pinned per type rather than tested once against cash and assumed to generalise.
 */
class StatementSignConventionTest {

    @ParameterizedTest(name = "{0}: bank {1} → ledger {2}")
    @CsvSource({
        // Cash at a bank: money arriving is a debit, and the bank shows it
        // positive. The two conventions agree.
        "ASSET,      10000,  10000",
        "ASSET,     -10000, -10000",
        "EXPENSE,     2500,   2500",
        // Credit-normal accounts invert: an increase is a credit, so negative in
        // signed terms.
        "LIABILITY,  10000, -10000",
        "LIABILITY, -10000,  10000",
        "EQUITY,     50000, -50000",
        "REVENUE,    30000, -30000"
    })
    void mapsABankAmountOntoTheLedgersSignedConvention(AccountType type, long bankAmount, long expectedLedgerSigned) {
        assertThat(StatementSignConvention.toLedgerSigned(bankAmount, type)).isEqualTo(expectedLedgerSigned);
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void theConversionIsItsOwnInverse(AccountType type) {
        // balanceSign is ±1, so one multiplication goes both ways. If that ever
        // stops being true, every round trip in the report silently doubles.
        for (long amount : new long[] {0, 1, -1, 250_000, -250_000, Long.MAX_VALUE / 2}) {
            long there = StatementSignConvention.toLedgerSigned(amount, type);
            assertThat(StatementSignConvention.toBankSigned(there, type)).isEqualTo(amount);
        }
    }

    @Test
    void zeroHasNoSignToGetWrong() {
        for (AccountType type : AccountType.values()) {
            assertThat(StatementSignConvention.toLedgerSigned(0, type)).isZero();
        }
    }
}
