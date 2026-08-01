package dev.lseg.ledger.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementImport(
        UUID id,
        UUID accountId,
        String accountCode,
        String currency,
        LocalDate periodStart,
        LocalDate periodEnd,
        long openingBalanceMinor,
        long closingBalanceMinor,
        String sourceFilename,
        ImportStatus status,
        Instant importedAt,
        UUID importedBy,
        String failureReason) {}
