package dev.lseg.ledger.ledger;

import java.time.LocalDate;
import java.util.UUID;

import dev.lseg.ledger.domain.EntrySource;

/**
 * @param accountId matches entries with at least one line on that account —
 *     which is what "show me this account's history" means. An entry touching it
 *     twice still appears once.
 */
public record JournalFilter(
        LocalDate from, LocalDate to, UUID accountId, EntrySource source, String externalRef, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public JournalFilter {
        // Capped rather than rejected: a caller asking for 10,000 rows wants as
        // many as they can have, and refusing outright only makes them retry.
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    public static JournalFilter unfiltered() {
        return new JournalFilter(null, null, null, null, null, DEFAULT_LIMIT);
    }
}
