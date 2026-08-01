package dev.lseg.ledger.domain;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One atomic financial fact, validated and ready to post.
 *
 * <p>This is the write aggregate. It is created whole and validated whole: an
 * entry with one line, or one whose debits do not equal its credits, has never
 * been a legal state, so there is no API that builds one incrementally and
 * checks later.
 *
 * <p>Invariants I1–I4 are enforced in the compact constructor below. They are
 * <em>also</em> enforced by a deferred constraint trigger in PostgreSQL. The
 * duplication is deliberate: this layer gives the caller a precise 422 with the
 * amount it is out by; the database guarantees that no other write path — a
 * migration, a script, a psql session — can bypass the rule. See ADR-0008.
 *
 * <p>Notably absent: any check that {@code effectiveDate} is not in the future.
 * That needs to know what "now" is, and a domain object that reads a clock is
 * not deterministic. The posting service applies it.
 */
public record JournalEntry(
        LocalDate effectiveDate,
        String description,
        Currency currency,
        EntrySource source,
        String externalRef,
        List<JournalLine> lines) {

    public JournalEntry {
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lines, "lines");

        if (description == null || description.isBlank()) {
            throw new LedgerException(LedgerError.BLANK_DESCRIPTION, "description must not be blank");
        }

        lines = List.copyOf(lines);

        // I2 — an entry moves money between at least two places, by definition.
        if (lines.size() < 2) {
            throw new LedgerException(
                    LedgerError.INSUFFICIENT_LINES,
                    "entry has %d line(s); at least 2 required".formatted(lines.size()),
                    Map.of("lineCount", lines.size()));
        }

        // I4 — mixing currencies would make the balance check below compare
        // numbers that are not commensurable, so the sum could be zero and mean
        // nothing. A currency exchange is two linked entries. See ADR-0005.
        for (JournalLine line : lines) {
            if (!line.amount().currency().equals(currency)) {
                throw new LedgerException(
                        LedgerError.MIXED_CURRENCY_ENTRY,
                        "entry is in %s but line for account %s is in %s"
                                .formatted(
                                        currency.getCurrencyCode(),
                                        line.accountCode(),
                                        line.amount().currency().getCurrencyCode()),
                        Map.of(
                                "entryCurrency", currency.getCurrencyCode(),
                                "accountCode", line.accountCode(),
                                "lineCurrency", line.amount().currency().getCurrencyCode()));
            }
        }

        // I1 — the definition of double entry.
        long signedTotal = 0L;
        for (JournalLine line : lines) {
            signedTotal = Math.addExact(signedTotal, line.signedAmountMinor());
        }
        if (signedTotal != 0L) {
            throw new LedgerException(
                    LedgerError.UNBALANCED_ENTRY,
                    "entry is unbalanced by %d minor units (debits %d, credits %d)"
                            .formatted(signedTotal, totalDebitOf(lines), totalCreditOf(lines)),
                    Map.of(
                            "differenceMinor", signedTotal,
                            "totalDebitMinor", totalDebitOf(lines),
                            "totalCreditMinor", totalCreditOf(lines)));
        }
    }

    public static JournalEntry of(
            LocalDate effectiveDate, String description, Currency currency, EntrySource source, JournalLine... lines) {
        return new JournalEntry(effectiveDate, description, currency, source, null, List.of(lines));
    }

    /**
     * A two-line transfer.
     *
     * <p>Sugar over the general case, because the two-account movement is most of
     * real traffic and making every caller hand-write a balanced line array
     * invites arithmetic mistakes at the edge. It produces an ordinary entry and
     * goes through the identical validation.
     */
    public static JournalEntry transfer(
            LocalDate effectiveDate, String description, String fromAccountCode, String toAccountCode, Money amount) {
        return new JournalEntry(
                effectiveDate,
                description,
                amount.currency(),
                EntrySource.TRANSFER,
                null,
                List.of(JournalLine.credit(fromAccountCode, amount), JournalLine.debit(toAccountCode, amount)));
    }

    public JournalEntry withExternalRef(String ref) {
        return new JournalEntry(effectiveDate, description, currency, source, ref, lines);
    }

    public long totalDebitMinor() {
        return totalDebitOf(lines);
    }

    public long totalCreditMinor() {
        return totalCreditOf(lines);
    }

    public Money totalDebit() {
        return Money.of(totalDebitMinor(), currency);
    }

    public Money totalCredit() {
        return Money.of(totalCreditMinor(), currency);
    }

    /**
     * Always true for a constructed instance — the constructor refuses anything
     * else. Kept because a predicate that can only ever return true is exactly
     * what a property test should assert over generated input.
     */
    public boolean isBalanced() {
        return lines.stream().mapToLong(JournalLine::signedAmountMinor).sum() == 0L;
    }

    public List<String> accountCodes() {
        return lines.stream().map(JournalLine::accountCode).distinct().toList();
    }

    private static long totalDebitOf(List<JournalLine> lines) {
        return lines.stream()
                .filter(l -> l.direction() == Direction.DEBIT)
                .mapToLong(l -> l.amount().amountMinor())
                .sum();
    }

    private static long totalCreditOf(List<JournalLine> lines) {
        return lines.stream()
                .filter(l -> l.direction() == Direction.CREDIT)
                .mapToLong(l -> l.amount().amountMinor())
                .sum();
    }
}
