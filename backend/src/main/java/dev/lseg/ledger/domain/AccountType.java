package dev.lseg.ledger.domain;

/**
 * The five account types of the extended accounting equation
 * {@code Assets = Liabilities + Equity}, with the temporary accounts that roll
 * into equity.
 *
 * <p>{@code balanceSign} bridges the two ways of reading an amount: the signed
 * balance (debit positive), which is what sums to zero across the whole ledger,
 * and the natural balance, which is the number a human expects to see. A
 * liability holding 500 has a signed balance of -500 and a natural balance of 500.
 *
 * <p>The mapping is a property of accounting, not a configuration choice — which
 * is why the database generates the same column rather than accepting it as input.
 */
public enum AccountType {
    ASSET(1),
    EXPENSE(1),
    LIABILITY(-1),
    EQUITY(-1),
    REVENUE(-1);

    private final int balanceSign;

    AccountType(int balanceSign) {
        this.balanceSign = balanceSign;
    }

    public int balanceSign() {
        return balanceSign;
    }

    /** The direction that increases this account. */
    public Direction normalBalance() {
        return balanceSign == 1 ? Direction.DEBIT : Direction.CREDIT;
    }

    public long toNatural(long signedAmountMinor) {
        return signedAmountMinor * balanceSign;
    }
}
