package dev.lseg.ledger.reconciliation;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One journal line on the account being reconciled, in the ledger's own
 * debit-positive convention.
 */
public record JournalMovement(
        UUID journalLineId,
        UUID entryId,
        long sequenceNo,
        LocalDate effectiveDate,
        long signedAmountMinor,
        String description,
        String externalRef) {}
