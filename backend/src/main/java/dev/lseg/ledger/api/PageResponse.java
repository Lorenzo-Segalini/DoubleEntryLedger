package dev.lseg.ledger.api;

import java.util.List;

/**
 * @param nextCursor pass back as {@code ?cursor=} for the next page. Absent when
 *     there is nothing more, so a caller never has to guess from a short page.
 */
public record PageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {}
