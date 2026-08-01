package dev.lseg.ledger.reconciliation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReconciliationBreak(
        UUID id,
        UUID importId,
        BreakType type,
        BreakStatus status,
        UUID statementLineId,
        UUID journalLineId,
        long deltaMinor,
        String currency,
        Map<String, Object> detail,
        String explanation,
        UUID resolvingEntryId,
        UUID resolvedBy,
        Instant resolvedAt) {}
