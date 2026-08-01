package dev.lseg.ledger.domain;

/**
 * Which side of an entry a line sits on.
 *
 * <p>The sign of an amount lives here and nowhere else. A line's stored amount is
 * always positive; debit means {@code +}, credit means {@code -}. Allowing a
 * negative debit would give every amount two representations, which means two
 * code paths and eventually a disagreement between them. See invariant I3.
 */
public enum Direction {
    DEBIT(1),
    CREDIT(-1);

    private final int sign;

    Direction(int sign) {
        this.sign = sign;
    }

    public long signed(long amountMinor) {
        return sign * amountMinor;
    }

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
