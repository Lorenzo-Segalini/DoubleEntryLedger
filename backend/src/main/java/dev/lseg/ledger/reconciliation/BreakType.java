package dev.lseg.ledger.reconciliation;

/**
 * Why a line could not be matched.
 *
 * <p>The type is what turns a list into an explanation. {@code TIMING_DIFFERENCE}
 * earns its own value rather than being folded into {@code MISSING_IN_STATEMENT}
 * because a payment made on 30 June and cleared on 2 July is not an error and
 * needs no correcting entry — it resolves itself next period. Reporting it as
 * "missing" sends an operator chasing a transaction that is fine.
 */
public enum BreakType {
    /** On the statement, never booked — a bank fee, typically. */
    MISSING_IN_LEDGER,
    /** Booked, never appeared at the bank. */
    MISSING_IN_STATEMENT,
    /** Matched pair, different amounts. */
    AMOUNT_MISMATCH,
    /** Matched pair straddling the period cut-off. Not an error. */
    TIMING_DIFFERENCE,
    /** The same movement booked twice. */
    DUPLICATE_IN_LEDGER,
    /** The bank reported it twice. */
    DUPLICATE_IN_STATEMENT,
    /** Statement line in a currency the account does not hold. */
    CURRENCY_MISMATCH,
    /**
     * The two documents disagree about where the period started.
     *
     * <p>Not a movement at all, and usually a symptom: the previous period was
     * never reconciled, or its breaks were left open. Without it the bridge cannot
     * close, because closing balances differ by the opening gap plus the movement
     * gap and only the second is explained by matching.
     */
    OPENING_BALANCE_MISMATCH
}
