package dev.lseg.ledger.reconciliation;

import dev.lseg.ledger.domain.AccountType;

/**
 * Translates a statement amount into the ledger's signed convention.
 *
 * <p>A statement is written from the counterparty's point of view; the ledger is
 * debit-positive. For an asset account — cash at a bank — money arriving is a
 * debit and the bank shows it positive, so the two agree. For a credit-normal
 * account the sense is inverted: an increase is a credit, which is negative in
 * signed terms.
 *
 * <p>That is exactly {@link AccountType#balanceSign()}, so this is a
 * multiplication rather than a table of special cases. It gets its own class
 * anyway, because getting it wrong inverts every match in the run and the result
 * still looks like a plausible reconciliation.
 */
public final class StatementSignConvention {

    private StatementSignConvention() {}

    public static long toLedgerSigned(long bankAmountMinor, AccountType accountType) {
        return bankAmountMinor * accountType.balanceSign();
    }

    public static long toBankSigned(long ledgerSignedMinor, AccountType accountType) {
        // balanceSign is its own inverse, so the same multiplication goes both ways.
        return ledgerSignedMinor * accountType.balanceSign();
    }
}
