package dev.lseg.ledger.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The single entry point for reading a balance.
 *
 * <p>One interface with one implementation today, deliberately. The roadmap's
 * balance snapshots turn an O(lines) read into O(lines since checkpoint); having
 * every caller already funnel through here makes that a second implementation
 * rather than a refactor — and keeps the derived version available to check the
 * fast one against. See ADR-0003.
 */
public interface BalanceQuery {

    AccountBalance asOf(UUID accountId, LocalDate asOf);

    AccountBalance current(UUID accountId);

    List<TrialBalanceRow> trialBalance(LocalDate asOf, String currencyCode);

    /**
     * Invariant I7 across the whole journal. Should always be zero; published as
     * a health check precisely because that belief is worth testing in production.
     */
    long outOfBalanceMinor();
}
