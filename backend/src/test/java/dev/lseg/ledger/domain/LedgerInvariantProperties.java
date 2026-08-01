package dev.lseg.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Example-based tests check the cases you thought of. These check the ones you
 * did not: jqwik generates thousands of random-but-legal entries and asserts the
 * invariants survive all of them.
 *
 * <p>Generation reaches well past realistic amounts (up to 10^12 minor units, i.e.
 * ten billion euro per line), builds entries of up to twenty lines, and covers
 * currencies with 0, 2 and 3 decimal exponents.
 *
 * <p>Overflow at {@code Long.MAX_VALUE} is deliberately <em>not</em> generated
 * here: summing twenty such lines would throw inside the generator rather than
 * inside a property, testing the fixture instead of the domain. That boundary has
 * its own targeted assertions in {@code MoneyTest}.
 */
class LedgerInvariantProperties {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 30);

    // ---------------------------------------------------------------- generators

    @Provide
    Arbitrary<Currency> currencies() {
        // One per exponent class: JPY has 0 decimals, EUR 2, TND 3.
        return Arbitraries.of("EUR", "USD", "JPY", "TND").map(Currency::getInstance);
    }

    /**
     * Entries balanced by construction: random lines, then a final line computed
     * as the negation of the rest.
     *
     * <p>Generating this way explores the space of <em>legal</em> histories rather
     * than burning cases on inputs the constructor rejects at the door.
     */
    @Provide
    Arbitrary<JournalEntry> balancedEntries() {
        return Combinators.combine(
                        currencies(),
                        Arbitraries.integers().between(1, 19),
                        Arbitraries.longs()
                                .between(1L, 1_000_000_000_000L)
                                .list()
                                .ofMinSize(19)
                                .ofMaxSize(19))
                .as(LedgerInvariantProperties::buildBalanced);
    }

    private static JournalEntry buildBalanced(Currency currency, int lineCount, List<Long> amounts) {
        List<JournalLine> lines = new ArrayList<>();
        long running = 0L;

        for (int i = 0; i < lineCount; i++) {
            long amount = amounts.get(i);
            Direction direction = i % 2 == 0 ? Direction.DEBIT : Direction.CREDIT;
            lines.add(new JournalLine("acct-" + i, direction, Money.of(amount, currency), null));
            running += direction.signed(amount);
        }

        // The balancing line. Its direction is whichever cancels what came before.
        long remainder = Math.abs(running);
        Direction closing = running > 0 ? Direction.CREDIT : Direction.DEBIT;
        if (remainder == 0) {
            // Already balanced; a zero-amount line is illegal, so add a matched pair.
            lines.add(new JournalLine("acct-close-d", Direction.DEBIT, Money.of(1L, currency), null));
            lines.add(new JournalLine("acct-close-c", Direction.CREDIT, Money.of(1L, currency), null));
        } else {
            lines.add(new JournalLine("acct-close", closing, Money.of(remainder, currency), null));
        }

        return new JournalEntry(DATE, "generated", currency, EntrySource.API, null, lines);
    }

    // ---------------------------------------------------------------- properties

    @Property(tries = 1000)
    void everyConstructedEntryBalances(@ForAll("balancedEntries") JournalEntry entry) {
        assertThat(entry.isBalanced()).isTrue();
        assertThat(entry.totalDebitMinor()).isEqualTo(entry.totalCreditMinor());
    }

    @Property(tries = 1000)
    void signedAmountsAlwaysSumToZero(@ForAll("balancedEntries") JournalEntry entry) {
        long total =
                entry.lines().stream().mapToLong(JournalLine::signedAmountMinor).sum();

        assertThat(total).isZero();
    }

    @Property(tries = 1000)
    void everyLineAmountIsStrictlyPositive(@ForAll("balancedEntries") JournalEntry entry) {
        // I3: the sign lives in the direction, never in the amount.
        assertThat(entry.lines())
                .allSatisfy(line -> assertThat(line.amount().isPositive()).isTrue());
    }

    @Property(tries = 1000)
    void mirroringAnEntryProducesTheExactOppositeMovement(@ForAll("balancedEntries") JournalEntry entry) {
        List<JournalLine> mirrored =
                entry.lines().stream().map(JournalLine::mirrored).toList();

        // The mirror is itself balanced...
        assertThat(mirrored.stream().mapToLong(JournalLine::signedAmountMinor).sum())
                .isZero();

        // ...and applying both leaves every account exactly where it started.
        for (int i = 0; i < entry.lines().size(); i++) {
            long original = entry.lines().get(i).signedAmountMinor();
            long reversed = mirrored.get(i).signedAmountMinor();
            assertThat(original + reversed).isZero();
            assertThat(mirrored.get(i).accountCode())
                    .isEqualTo(entry.lines().get(i).accountCode());
            assertThat(mirrored.get(i).amount()).isEqualTo(entry.lines().get(i).amount());
        }
    }

    @Property(tries = 500)
    void anEntryOffByAnyNonZeroAmountIsRejected(
            @ForAll("balancedEntries") JournalEntry entry, @ForAll @LongRange(min = 1, max = 1_000_000) long drift) {

        // Perturb one line and the entry must stop being constructible. This is
        // the property a tolerance-based balance check would fail.
        List<JournalLine> broken = new ArrayList<>(entry.lines());
        JournalLine first = broken.get(0);
        broken.set(
                0,
                new JournalLine(
                        first.accountCode(),
                        first.direction(),
                        Money.of(
                                first.amount().amountMinor() + drift,
                                first.amount().currency()),
                        first.memo()));

        assertThatThrownBy(() -> new JournalEntry(DATE, "perturbed", entry.currency(), EntrySource.API, null, broken))
                .isInstanceOf(LedgerException.class);
    }

    @Property(tries = 200)
    void anEntryWithFewerThanTwoLinesIsAlwaysRejected(
            @ForAll("currencies") Currency currency, @ForAll @LongRange(min = 1, max = 1_000_000) long amount) {

        assertThatThrownBy(() -> new JournalEntry(
                        DATE,
                        "too short",
                        currency,
                        EntrySource.API,
                        null,
                        List.of(JournalLine.debit("1000", Money.of(amount, currency)))))
                .isInstanceOf(LedgerException.class);
    }

    @Property(tries = 500)
    void moneyAdditionIsAssociativeAndCommutative(
            @ForAll("currencies") Currency currency,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long c) {

        Money ma = Money.of(a, currency);
        Money mb = Money.of(b, currency);
        Money mc = Money.of(c, currency);

        assertThat(ma.plus(mb)).isEqualTo(mb.plus(ma));
        assertThat(ma.plus(mb).plus(mc)).isEqualTo(ma.plus(mb.plus(mc)));
        assertThat(ma.plus(mb).minus(mb)).isEqualTo(ma);
    }

    @Property(tries = 500)
    void naturalAndSignedBalancesAgreeThroughTheAccountSign(
            @ForAll AccountType type, @ForAll @LongRange(min = -1_000_000L, max = 1_000_000L) long signedMinor) {

        long natural = type.toNatural(signedMinor);

        // Converting twice returns the original: balanceSign is its own inverse.
        assertThat(type.toNatural(natural)).isEqualTo(signedMinor);

        // Debit-normal accounts read the same either way; credit-normal invert.
        if (type.normalBalance() == Direction.DEBIT) {
            assertThat(natural).isEqualTo(signedMinor);
        } else {
            assertThat(natural).isEqualTo(-signedMinor);
        }
    }

    @Property(tries = 200)
    void directionOppositeIsAnInvolution(
            @ForAll Direction direction, @ForAll @IntRange(min = 1, max = 1000) int amount) {
        assertThat(direction.opposite().opposite()).isEqualTo(direction);
        assertThat(direction.signed(amount) + direction.opposite().signed(amount))
                .isZero();
    }
}
