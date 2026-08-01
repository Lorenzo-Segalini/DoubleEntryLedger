package dev.lseg.ledger.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The deliverable.
 *
 * <p>Most reconciliation tooling reports a number: the ledger says one thing, the
 * bank says another, difference so much. That tells an operator something is
 * wrong and nothing about what. Here {@code differenceMinor} must equal
 * {@code bridgeTotalMinor} — the explanations add up to the difference, or the
 * reconciliation is not finished.
 *
 * @param bridgeBalanced false means the matching engine has a bug: a
 *     double-consumed line, a sign error, a missed classification. Reported
 *     loudly rather than presenting a plausible-looking list.
 */
public record ReconciliationReport(
        UUID importId,
        String accountCode,
        String accountName,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        long ledgerClosingMinor,
        long statementClosingMinor,
        long differenceMinor,
        int matchedCount,
        long matchedAmountMinor,
        int unmatchedStatementLines,
        int unmatchedJournalLines,
        List<BridgeRow> bridge,
        long bridgeTotalMinor,
        boolean bridgeBalanced,
        double matchRate,
        Instant generatedAt) {

    public record BridgeRow(
            UUID breakId,
            BreakType type,
            BreakStatus status,
            long deltaMinor,
            String detail,
            Map<String, Object> data) {}
}
