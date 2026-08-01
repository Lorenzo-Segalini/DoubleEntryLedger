package dev.lseg.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * An entry as it exists in the journal.
 *
 * <p>Distinct from {@link JournalEntry}, which is the validated command to post
 * one. This carries identity and provenance that only the database can assign:
 * the id, the monotonic sequence number, and {@code postedAt}.
 *
 * <p>There is no method here that changes anything. Correcting this entry means
 * posting a reversal, which produces a second {@code PostedEntry} linked to this
 * one — see ADR-0001.
 */
public record PostedEntry(
        UUID id,
        long sequenceNo,
        LocalDate effectiveDate,
        Instant postedAt,
        String description,
        Currency currency,
        EntrySource source,
        String externalRef,
        String idempotencyKey,
        UUID reversalOfEntryId,
        String reversalReason,
        UUID createdBy,
        String requestId,
        List<PostedLine> lines) {

    public PostedEntry {
        lines = List.copyOf(lines);
    }

    public boolean isReversal() {
        return reversalOfEntryId != null;
    }

    public long totalDebitMinor() {
        return lines.stream()
                .filter(l -> l.direction() == Direction.DEBIT)
                .mapToLong(l -> l.amount().amountMinor())
                .sum();
    }

    public long totalCreditMinor() {
        return lines.stream()
                .filter(l -> l.direction() == Direction.CREDIT)
                .mapToLong(l -> l.amount().amountMinor())
                .sum();
    }

    public long signedTotalMinor() {
        return lines.stream().mapToLong(PostedLine::signedAmountMinor).sum();
    }

    /**
     * Builds the entry that cancels this one.
     *
     * <p>Generated from the original rather than supplied by the caller. That is
     * what makes invariant I9 hold: a reversal cannot be a partial or subtly
     * different cancellation of the entry it claims to reverse, because nobody
     * gets to describe it.
     */
    public JournalEntry reversal(LocalDate reversalDate, String reason) {
        List<JournalLine> mirrored = lines.stream()
                .map(l -> new JournalLine(l.accountCode(), l.direction().opposite(), l.amount(), l.memo()))
                .toList();

        return new JournalEntry(
                reversalDate,
                "Reversal of entry %d: %s".formatted(sequenceNo, reason),
                currency,
                EntrySource.REVERSAL,
                externalRef,
                mirrored);
    }
}
