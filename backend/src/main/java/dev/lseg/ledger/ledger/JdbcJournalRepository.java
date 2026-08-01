package dev.lseg.ledger.ledger;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.lseg.ledger.domain.Account;
import dev.lseg.ledger.domain.Direction;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.domain.PostedLine;

@Repository
class JdbcJournalRepository implements JournalRepository {

    private static final String INSERT_ENTRY =
            """
            INSERT INTO journal_entry (
                effective_date, description, currency, source, external_ref,
                idempotency_key, reversal_of_entry_id, reversal_reason, created_by, request_id)
            VALUES (
                :effectiveDate, :description, :currency, CAST(:source AS entry_source), :externalRef,
                :idempotencyKey, :reversalOf, :reversalReason, :createdBy, :requestId)
            RETURNING id, sequence_no, posted_at
            """;

    private static final String INSERT_LINE =
            """
            INSERT INTO journal_line (
                entry_id, line_no, account_id, direction, amount_minor, currency, memo, effective_date)
            VALUES (
                :entryId, :lineNo, :accountId, CAST(:direction AS direction),
                :amountMinor, :currency, :memo, :effectiveDate)
            RETURNING id
            """;

    private static final String SELECT_ENTRY =
            """
            SELECT id, sequence_no, effective_date, posted_at, description, currency, source,
                   external_ref, idempotency_key, reversal_of_entry_id, reversal_reason,
                   created_by, request_id
              FROM journal_entry
            """;

    private static final String SELECT_LINES =
            """
            SELECT l.id, l.line_no, l.account_id, a.code AS account_code, a.name AS account_name,
                   l.direction, l.amount_minor, l.currency, l.memo
              FROM journal_line l
              JOIN account a ON a.id = l.account_id
             WHERE l.entry_id = :entryId
             ORDER BY l.line_no
            """;

    private static final RowMapper<PostedLine> LINE_MAPPER = (rs, rowNum) -> new PostedLine(
            rs.getObject("id", UUID.class),
            rs.getInt("line_no"),
            rs.getObject("account_id", UUID.class),
            rs.getString("account_code"),
            rs.getString("account_name"),
            Direction.valueOf(rs.getString("direction")),
            Money.of(
                    rs.getLong("amount_minor"),
                    Currency.getInstance(rs.getString("currency").trim())),
            rs.getString("memo"));

    private final JdbcClient jdbc;

    JdbcJournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PostedEntry insert(
            JournalEntry entry,
            Map<String, Account> accountsByCode,
            PostingContext context,
            UUID reversalOf,
            String reversalReason) {

        EntryIdentity identity = jdbc.sql(INSERT_ENTRY)
                .param("effectiveDate", entry.effectiveDate())
                .param("description", entry.description())
                .param("currency", entry.currency().getCurrencyCode())
                .param("source", entry.source().name())
                .param("externalRef", entry.externalRef(), Types.VARCHAR)
                .param("idempotencyKey", context.idempotencyKey(), Types.VARCHAR)
                .param("reversalOf", reversalOf, Types.OTHER)
                .param("reversalReason", reversalReason, Types.VARCHAR)
                .param("createdBy", context.actorId())
                .param("requestId", context.requestId())
                .query((rs, rowNum) -> new EntryIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getLong("sequence_no"),
                        rs.getTimestamp("posted_at").toInstant()))
                .single();

        List<PostedLine> postedLines = new ArrayList<>(entry.lines().size());
        int lineNo = 0;
        for (JournalLine line : entry.lines()) {
            lineNo++;
            Account account = accountsByCode.get(line.accountCode());
            UUID lineId = jdbc.sql(INSERT_LINE)
                    .param("entryId", identity.id())
                    .param("lineNo", lineNo)
                    .param("accountId", account.id())
                    .param("direction", line.direction().name())
                    .param("amountMinor", line.amount().amountMinor())
                    .param("currency", line.amount().currency().getCurrencyCode())
                    .param("memo", line.memo(), Types.VARCHAR)
                    // Denormalised copy of the entry's date. Safe because both rows
                    // are immutable; the deferred trigger verifies they agree.
                    .param("effectiveDate", entry.effectiveDate())
                    .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                    .single();

            postedLines.add(new PostedLine(
                    lineId,
                    lineNo,
                    account.id(),
                    account.code(),
                    account.name(),
                    line.direction(),
                    line.amount(),
                    line.memo()));
        }

        return new PostedEntry(
                identity.id(),
                identity.sequenceNo(),
                entry.effectiveDate(),
                identity.postedAt(),
                entry.description(),
                entry.currency(),
                entry.source(),
                entry.externalRef(),
                context.idempotencyKey(),
                reversalOf,
                reversalReason,
                context.actorId(),
                context.requestId(),
                postedLines);
    }

    @Override
    public Optional<PostedEntry> findById(UUID id) {
        return jdbc.sql(SELECT_ENTRY + " WHERE id = :id")
                .param("id", id)
                .query(this::mapEntryWithoutLines)
                .optional()
                .map(this::withLines);
    }

    @Override
    public Optional<PostedEntry> findByExternalRef(String externalRef) {
        return jdbc.sql(SELECT_ENTRY + " WHERE external_ref = :ref ORDER BY sequence_no LIMIT 1")
                .param("ref", externalRef)
                .query(this::mapEntryWithoutLines)
                .optional()
                .map(this::withLines);
    }

    @Override
    public Optional<UUID> findReversalOf(UUID entryId) {
        return jdbc.sql("SELECT id FROM journal_entry WHERE reversal_of_entry_id = :id")
                .param("id", entryId)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();
    }

    @Override
    public EntryPage findPage(JournalFilter filter, EntryCursor after) {
        StringBuilder sql = new StringBuilder(SELECT_ENTRY).append(" WHERE 1 = 1 ");
        Map<String, Object> params = new java.util.HashMap<>();

        if (after != null) {
            // A row-value comparison, not two ORed predicates: it reads as the
            // single "everything before this position" it is, and PostgreSQL can
            // satisfy it from (effective_date, sequence_no) as one range scan.
            sql.append(" AND (effective_date, sequence_no) < (:cursorDate, :cursorSeq) ");
            params.put("cursorDate", after.effectiveDate());
            params.put("cursorSeq", after.sequenceNo());
        }
        if (filter.from() != null) {
            sql.append(" AND effective_date >= :from ");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            sql.append(" AND effective_date <= :to ");
            params.put("to", filter.to());
        }
        if (filter.source() != null) {
            sql.append(" AND source = CAST(:source AS entry_source) ");
            params.put("source", filter.source().name());
        }
        if (filter.externalRef() != null && !filter.externalRef().isBlank()) {
            sql.append(" AND external_ref = :externalRef ");
            params.put("externalRef", filter.externalRef());
        }
        if (filter.accountId() != null) {
            // EXISTS rather than a join: an entry with two lines on the account
            // must appear once, and a join would return it twice.
            sql.append(" AND EXISTS (SELECT 1 FROM journal_line l "
                    + "WHERE l.entry_id = journal_entry.id AND l.account_id = :accountId) ");
            params.put("accountId", filter.accountId());
        }

        sql.append(" ORDER BY effective_date DESC, sequence_no DESC LIMIT :limit ");
        // One more than asked for: the extra row is how we know there is a next
        // page without a second COUNT query over the whole table.
        params.put("limit", filter.limit() + 1);

        var spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }

        List<PostedEntry> rows = spec.query(this::mapEntryWithoutLines).list();

        boolean hasMore = rows.size() > filter.limit();
        List<PostedEntry> page = (hasMore ? rows.subList(0, filter.limit()) : rows)
                .stream().map(this::withLines).toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            PostedEntry last = page.getLast();
            nextCursor = new EntryCursor(last.effectiveDate(), last.sequenceNo()).encode();
        }

        return new EntryPage(page, nextCursor, hasMore);
    }

    private PostedEntry withLines(PostedEntry entry) {
        List<PostedLine> lines = jdbc.sql(SELECT_LINES)
                .param("entryId", entry.id())
                .query(LINE_MAPPER)
                .list();
        return new PostedEntry(
                entry.id(),
                entry.sequenceNo(),
                entry.effectiveDate(),
                entry.postedAt(),
                entry.description(),
                entry.currency(),
                entry.source(),
                entry.externalRef(),
                entry.idempotencyKey(),
                entry.reversalOfEntryId(),
                entry.reversalReason(),
                entry.createdBy(),
                entry.requestId(),
                lines);
    }

    private PostedEntry mapEntryWithoutLines(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp postedAt = rs.getTimestamp("posted_at");
        LocalDate effectiveDate = rs.getObject("effective_date", LocalDate.class);
        return new PostedEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("sequence_no"),
                effectiveDate,
                postedAt == null ? Instant.EPOCH : postedAt.toInstant(),
                rs.getString("description"),
                Currency.getInstance(rs.getString("currency").trim()),
                EntrySource.valueOf(rs.getString("source")),
                rs.getString("external_ref"),
                rs.getString("idempotency_key"),
                rs.getObject("reversal_of_entry_id", UUID.class),
                rs.getString("reversal_reason"),
                rs.getObject("created_by", UUID.class),
                rs.getString("request_id"),
                List.of());
    }

    private record EntryIdentity(UUID id, long sequenceNo, Instant postedAt) {}
}
