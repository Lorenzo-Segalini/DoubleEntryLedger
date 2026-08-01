package dev.lseg.ledger.reconciliation;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a bank statement.
 *
 * @param amountMinor signed <strong>from the bank's perspective</strong>: money
 *     leaving the account is negative. Converting this to the ledger's convention
 *     is {@link StatementSignConvention}'s job and nobody else's.
 */
public record StatementLine(
        UUID id,
        int rowNo,
        LocalDate valueDate,
        long amountMinor,
        String currency,
        String description,
        String externalId,
        String counterpartyRef) {

    public static StatementLine parsed(
            int rowNo,
            LocalDate valueDate,
            long amountMinor,
            String currency,
            String description,
            String externalId,
            String counterpartyRef) {
        return new StatementLine(
                null, rowNo, valueDate, amountMinor, currency, description, externalId, counterpartyRef);
    }
}
