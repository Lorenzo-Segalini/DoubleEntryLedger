package dev.lseg.ledger.domain;

import java.util.Objects;

/**
 * One side of a financial fact, before it is posted.
 *
 * <p>Identifies its account by <em>code</em> rather than id: the code is the
 * business key a caller knows, and resolving it is the posting service's job.
 *
 * <p>The amount is always positive; {@link Direction} carries the sign.
 */
public record JournalLine(String accountCode, Direction direction, Money amount, String memo) {

    public JournalLine {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");

        if (accountCode.isBlank()) {
            throw new LedgerException(LedgerError.UNKNOWN_ACCOUNT, "account code must not be blank");
        }
        // I3. Enforced here as well as by a CHECK constraint, because a caller
        // deserves a field-level 422 rather than a transaction-level error.
        if (!amount.isPositive()) {
            throw new LedgerException(
                    LedgerError.NON_POSITIVE_AMOUNT,
                    "line amount must be positive, got %s".formatted(amount),
                    java.util.Map.of("accountCode", accountCode, "amountMinor", amount.amountMinor()));
        }
    }

    public static JournalLine debit(String accountCode, Money amount) {
        return new JournalLine(accountCode, Direction.DEBIT, amount, null);
    }

    public static JournalLine credit(String accountCode, Money amount) {
        return new JournalLine(accountCode, Direction.CREDIT, amount, null);
    }

    public JournalLine withMemo(String newMemo) {
        return new JournalLine(accountCode, direction, amount, newMemo);
    }

    /** Debit positive, credit negative. What the ledger sums. */
    public long signedAmountMinor() {
        return direction.signed(amount.amountMinor());
    }

    /** The same movement in the opposite direction. Used to build reversals. */
    public JournalLine mirrored() {
        return new JournalLine(accountCode, direction.opposite(), amount, memo);
    }
}
