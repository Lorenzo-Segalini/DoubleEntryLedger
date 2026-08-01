package dev.lseg.ledger.domain;

import java.util.UUID;

/** A line as it exists in the journal: identified, immutable, never updated. */
public record PostedLine(
        UUID id,
        int lineNo,
        UUID accountId,
        String accountCode,
        String accountName,
        Direction direction,
        Money amount,
        String memo) {

    public long signedAmountMinor() {
        return direction.signed(amount.amountMinor());
    }
}
