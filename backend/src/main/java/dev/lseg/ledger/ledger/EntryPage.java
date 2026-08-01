package dev.lseg.ledger.ledger;

import java.util.List;

import dev.lseg.ledger.domain.PostedEntry;

/**
 * @param nextCursor null when the end has been reached. Present means there is
 *     more, so callers never have to infer it from a short page — a page can be
 *     short because the limit was odd, not because the data ran out.
 */
public record EntryPage(List<PostedEntry> items, String nextCursor, boolean hasMore) {}
