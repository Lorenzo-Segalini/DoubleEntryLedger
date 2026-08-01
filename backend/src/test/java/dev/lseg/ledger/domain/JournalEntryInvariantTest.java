package dev.lseg.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The accounting rules, tested as behaviour rather than asserted in prose.
 *
 * <p>Named after the invariants from docs/01-domain-model.md §1.3, so a failure
 * report names the rule that broke rather than a method.
 */
class JournalEntryInvariantTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final LocalDate DATE = LocalDate.of(2026, 6, 30);

    private static Money eur(long minor) {
        return Money.of(minor, EUR);
    }

    private static JournalEntry entry(JournalLine... lines) {
        return new JournalEntry(DATE, "test", EUR, EntrySource.API, null, List.of(lines));
    }

    @Nested
    @DisplayName("I1 — every entry balances")
    class Balancing {

        @Test
        void acceptsAnEntryWhoseDebitsEqualItsCredits() {
            JournalEntry e = entry(JournalLine.debit("1000", eur(10_000)), JournalLine.credit("4000", eur(10_000)));

            assertThat(e.isBalanced()).isTrue();
            assertThat(e.totalDebitMinor()).isEqualTo(10_000);
            assertThat(e.totalCreditMinor()).isEqualTo(10_000);
        }

        @Test
        void acceptsMoreThanTwoLines() {
            // A card settlement with a fee: one fact, three lines. The case a
            // two-line-only model gets wrong.
            JournalEntry e = entry(
                    JournalLine.debit("1100", eur(9_710)),
                    JournalLine.debit("5000", eur(290)),
                    JournalLine.credit("4000", eur(10_000)));

            assertThat(e.isBalanced()).isTrue();
            assertThat(e.lines()).hasSize(3);
        }

        @Test
        void rejectsAnUnbalancedEntryAndSaysByHowMuch() {
            assertThatThrownBy(
                            () -> entry(JournalLine.debit("1000", eur(10_000)), JournalLine.credit("4000", eur(9_000))))
                    .isInstanceOf(LedgerException.class)
                    .extracting(e -> ((LedgerException) e).error())
                    .isEqualTo(LedgerError.UNBALANCED_ENTRY);
        }

        @Test
        void reportsTheDifferenceInDetailsSoACallerCanFixIt() {
            LedgerException thrown = catchLedgerException(
                    () -> entry(JournalLine.debit("1000", eur(10_000)), JournalLine.credit("4000", eur(9_000))));

            assertThat(thrown.details())
                    .containsEntry("differenceMinor", 1_000L)
                    .containsEntry("totalDebitMinor", 10_000L)
                    .containsEntry("totalCreditMinor", 9_000L);
        }

        @Test
        void rejectsAnEntryOffByOneMinorUnit() {
            // The case that a tolerance-based check would wave through.
            assertThatThrownBy(
                            () -> entry(JournalLine.debit("1000", eur(10_000)), JournalLine.credit("4000", eur(9_999))))
                    .isInstanceOf(LedgerException.class);
        }
    }

    @Nested
    @DisplayName("I2 — at least two lines")
    class MinimumLines {

        @Test
        void rejectsASingleLine() {
            assertThat(catchLedgerException(() -> entry(JournalLine.debit("1000", eur(10_000))))
                            .error())
                    .isEqualTo(LedgerError.INSUFFICIENT_LINES);
        }

        @Test
        void rejectsNoLines() {
            assertThat(catchLedgerException(JournalEntryInvariantTest::emptyEntry)
                            .error())
                    .isEqualTo(LedgerError.INSUFFICIENT_LINES);
        }
    }

    @Nested
    @DisplayName("I3 — positive integer minor units")
    class PositiveAmounts {

        @Test
        void rejectsAZeroAmount() {
            assertThat(catchLedgerException(() -> JournalLine.debit("1000", eur(0)))
                            .error())
                    .isEqualTo(LedgerError.NON_POSITIVE_AMOUNT);
        }

        @Test
        void rejectsANegativeAmount() {
            // A negative debit is not something an accountant would write, and
            // allowing it would give every amount two representations.
            assertThat(catchLedgerException(() -> JournalLine.debit("1000", eur(-500)))
                            .error())
                    .isEqualTo(LedgerError.NON_POSITIVE_AMOUNT);
        }

        @Test
        void theSignComesFromTheDirectionNotTheAmount() {
            assertThat(JournalLine.debit("1000", eur(500)).signedAmountMinor()).isEqualTo(500);
            assertThat(JournalLine.credit("1000", eur(500)).signedAmountMinor()).isEqualTo(-500);
        }
    }

    @Nested
    @DisplayName("I4 — one currency per entry")
    class SingleCurrency {

        @Test
        void rejectsLinesInADifferentCurrencyFromTheEntry() {
            assertThat(catchLedgerException(() -> new JournalEntry(
                                    DATE,
                                    "mixed",
                                    EUR,
                                    EntrySource.API,
                                    null,
                                    List.of(
                                            JournalLine.debit("1000", eur(10_000)),
                                            JournalLine.credit("1900", Money.of(10_000, JPY)))))
                            .error())
                    .isEqualTo(LedgerError.MIXED_CURRENCY_ENTRY);
        }

        @Test
        void mixingCurrenciesIsRejectedEvenWhenTheNumbersHappenToCancel() {
            // The trap this invariant exists for: +100 EUR and -100 JPY sums to
            // zero and means nothing. Without the currency check this would pass
            // the balance test. See ADR-0005.
            assertThat(catchLedgerException(() -> new JournalEntry(
                                    DATE,
                                    "spurious balance",
                                    EUR,
                                    EntrySource.API,
                                    null,
                                    List.of(
                                            JournalLine.debit("1000", eur(10_000)),
                                            JournalLine.credit("1900", Money.of(10_000, JPY)))))
                            .error())
                    .isEqualTo(LedgerError.MIXED_CURRENCY_ENTRY);
        }
    }

    @Nested
    @DisplayName("Descriptions and construction")
    class Construction {

        @Test
        void rejectsABlankDescription() {
            assertThat(catchLedgerException(() -> new JournalEntry(
                                    DATE,
                                    "   ",
                                    EUR,
                                    EntrySource.API,
                                    null,
                                    List.of(JournalLine.debit("1000", eur(1)), JournalLine.credit("4000", eur(1)))))
                            .error())
                    .isEqualTo(LedgerError.BLANK_DESCRIPTION);
        }

        @Test
        void transferExpandsToABalancedTwoLineEntry() {
            JournalEntry t = JournalEntry.transfer(DATE, "top-up", "1000", "1100", eur(250_000));

            assertThat(t.isBalanced()).isTrue();
            assertThat(t.source()).isEqualTo(EntrySource.TRANSFER);
            assertThat(t.lines())
                    .extracting(JournalLine::accountCode, JournalLine::direction)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("1000", Direction.CREDIT),
                            org.assertj.core.groups.Tuple.tuple("1100", Direction.DEBIT));
        }

        @Test
        void linesAreDefensivelyCopied() {
            List<JournalLine> mutable = new java.util.ArrayList<>(
                    List.of(JournalLine.debit("1000", eur(1)), JournalLine.credit("4000", eur(1))));
            JournalEntry e = new JournalEntry(DATE, "test", EUR, EntrySource.API, null, mutable);

            mutable.clear();

            assertThat(e.lines()).hasSize(2);
            assertThatCode(e::isBalanced).doesNotThrowAnyException();
        }
    }

    private static JournalEntry emptyEntry() {
        return new JournalEntry(DATE, "empty", EUR, EntrySource.API, null, List.of());
    }

    private static LedgerException catchLedgerException(Runnable action) {
        try {
            action.run();
        } catch (LedgerException e) {
            return e;
        }
        throw new AssertionError("expected a LedgerException, none was thrown");
    }

    private static LedgerException catchLedgerException(java.util.function.Supplier<?> action) {
        return catchLedgerException((Runnable) action::get);
    }
}
