package dev.lseg.ledger.reconciliation;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;

@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ReconciliationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- imports

    public Optional<UUID> findImportIdByContent(UUID accountId, byte[] sha256) {
        return jdbc.sql("SELECT id FROM statement_import WHERE account_id = :accountId AND content_sha256 = :sha")
                .param("accountId", accountId)
                .param("sha", sha256)
                .query(UUID.class)
                .optional();
    }

    public UUID createImport(
            UUID accountId,
            String currency,
            LocalDate periodStart,
            LocalDate periodEnd,
            long openingMinor,
            long closingMinor,
            String filename,
            byte[] sha256,
            UUID importedBy) {

        return jdbc.sql(
                        """
                        INSERT INTO statement_import (account_id, currency, period_start, period_end,
                                opening_balance_minor, closing_balance_minor, source_filename, content_sha256,
                                status, imported_by)
                        VALUES (:accountId, :currency, :periodStart, :periodEnd, :opening, :closing,
                                :filename, :sha, 'MATCHING', :importedBy)
                        RETURNING id
                        """)
                .param("accountId", accountId)
                .param("currency", currency)
                .param("periodStart", periodStart)
                .param("periodEnd", periodEnd)
                .param("opening", openingMinor)
                .param("closing", closingMinor)
                .param("filename", filename)
                .param("sha", sha256)
                .param("importedBy", importedBy)
                .query(UUID.class)
                .single();
    }

    public void markCompleted(UUID importId) {
        jdbc.sql("UPDATE statement_import SET status = 'COMPLETED' WHERE id = :id")
                .param("id", importId)
                .update();
    }

    public Optional<StatementImport> findImport(UUID importId) {
        return jdbc.sql(
                        """
                        SELECT i.id, i.account_id, a.code AS account_code, i.currency, i.period_start, i.period_end,
                               i.opening_balance_minor, i.closing_balance_minor, i.source_filename, i.status,
                               i.imported_at, i.imported_by, i.failure_reason
                          FROM statement_import i
                          JOIN account a ON a.id = i.account_id
                         WHERE i.id = :id
                        """)
                .param("id", importId)
                .query((rs, rowNum) -> new StatementImport(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_code"),
                        rs.getString("currency").trim(),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getLong("opening_balance_minor"),
                        rs.getLong("closing_balance_minor"),
                        rs.getString("source_filename"),
                        ImportStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("imported_at").toInstant(),
                        rs.getObject("imported_by", UUID.class),
                        rs.getString("failure_reason")))
                .optional();
    }

    public List<StatementImport> findImports(UUID accountId) {
        String filter = accountId == null ? "" : " WHERE i.account_id = :accountId ";
        var spec = jdbc.sql(
                """
                SELECT i.id, i.account_id, a.code AS account_code, i.currency, i.period_start, i.period_end,
                       i.opening_balance_minor, i.closing_balance_minor, i.source_filename, i.status,
                       i.imported_at, i.imported_by, i.failure_reason
                  FROM statement_import i
                  JOIN account a ON a.id = i.account_id
                """
                        + filter
                        + " ORDER BY i.imported_at DESC");
        if (accountId != null) {
            spec = spec.param("accountId", accountId);
        }
        return spec.query((rs, rowNum) -> new StatementImport(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_code"),
                        rs.getString("currency").trim(),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getLong("opening_balance_minor"),
                        rs.getLong("closing_balance_minor"),
                        rs.getString("source_filename"),
                        ImportStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("imported_at").toInstant(),
                        rs.getObject("imported_by", UUID.class),
                        rs.getString("failure_reason")))
                .list();
    }

    // ---------------------------------------------------------------- lines

    public UUID insertStatementLine(UUID importId, StatementLine line) {
        return jdbc.sql(
                        """
                        INSERT INTO statement_line (import_id, row_no, value_date, amount_minor, currency,
                                description, external_id, counterparty_ref)
                        VALUES (:importId, :rowNo, :valueDate, :amount, :currency, :description, :externalId, :ref)
                        RETURNING id
                        """)
                .param("importId", importId)
                .param("rowNo", line.rowNo())
                .param("valueDate", line.valueDate())
                .param("amount", line.amountMinor())
                .param("currency", line.currency())
                .param("description", line.description())
                .param("externalId", line.externalId(), Types.VARCHAR)
                .param("ref", line.counterpartyRef(), Types.VARCHAR)
                .query(UUID.class)
                .single();
    }

    /** The journal side of the comparison: every line on the account within the period. */
    public List<JournalMovement> movementsIn(UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql(
                        """
                        SELECT l.id, l.entry_id, e.sequence_no, l.effective_date, l.signed_amount_minor,
                               e.description, e.external_ref
                          FROM journal_line l
                          JOIN journal_entry e ON e.id = l.entry_id
                         WHERE l.account_id = :accountId
                           AND l.effective_date BETWEEN :from AND :to
                         ORDER BY l.effective_date, e.sequence_no, l.line_no
                        """)
                .param("accountId", accountId)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new JournalMovement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("entry_id", UUID.class),
                        rs.getLong("sequence_no"),
                        rs.getObject("effective_date", LocalDate.class),
                        rs.getLong("signed_amount_minor"),
                        rs.getString("description"),
                        rs.getString("external_ref")))
                .list();
    }

    // ---------------------------------------------------------------- matches and breaks

    public void insertMatch(
            UUID importId, UUID statementLineId, UUID journalLineId, MatchRule rule, double confidence) {
        jdbc.sql(
                        """
                        INSERT INTO reconciliation_match (import_id, statement_line_id, journal_line_id, rule, confidence)
                        VALUES (:importId, :statementLineId, :journalLineId, :rule, :confidence)
                        """)
                .param("importId", importId)
                .param("statementLineId", statementLineId)
                .param("journalLineId", journalLineId)
                .param("rule", rule.name())
                .param("confidence", confidence)
                .update();
    }

    public UUID insertBreak(
            UUID importId,
            BreakType type,
            long deltaMinor,
            String currency,
            UUID statementLineId,
            UUID journalLineId,
            Map<String, Object> detail) {

        return jdbc.sql(
                        """
                        INSERT INTO reconciliation_break (import_id, type, delta_minor, currency,
                                statement_line_id, journal_line_id, detail)
                        VALUES (:importId, CAST(:type AS break_type), :delta, :currency,
                                :statementLineId, :journalLineId, CAST(:detail AS jsonb))
                        RETURNING id
                        """)
                .param("importId", importId)
                .param("type", type.name())
                .param("delta", deltaMinor)
                .param("currency", currency)
                .param("statementLineId", statementLineId, Types.OTHER)
                .param("journalLineId", journalLineId, Types.OTHER)
                .param("detail", writeJson(detail))
                .query(UUID.class)
                .single();
    }

    private static final RowMapper<ReconciliationBreak> BREAK_MAPPER = (rs, rowNum) -> new ReconciliationBreak(
            rs.getObject("id", UUID.class),
            rs.getObject("import_id", UUID.class),
            BreakType.valueOf(rs.getString("type")),
            BreakStatus.valueOf(rs.getString("status")),
            rs.getObject("statement_line_id", UUID.class),
            rs.getObject("journal_line_id", UUID.class),
            rs.getLong("delta_minor"),
            rs.getString("currency").trim(),
            Map.of(),
            rs.getString("explanation"),
            rs.getObject("resolving_entry_id", UUID.class),
            rs.getObject("resolved_by", UUID.class),
            rs.getTimestamp("resolved_at") == null
                    ? null
                    : rs.getTimestamp("resolved_at").toInstant());

    private static final String SELECT_BREAK =
            """
            SELECT id, import_id, type, status, statement_line_id, journal_line_id, delta_minor, currency,
                   detail, explanation, resolving_entry_id, resolved_by, resolved_at
              FROM reconciliation_break
            """;

    public List<ReconciliationBreak> findBreaks(UUID importId) {
        return jdbc.sql(SELECT_BREAK + " WHERE import_id = :importId ORDER BY type, created_at")
                .param("importId", importId)
                .query(BREAK_MAPPER)
                .list();
    }

    public List<Map<String, Object>> findBreakDetails(UUID importId) {
        return jdbc.sql("SELECT id, detail FROM reconciliation_break WHERE import_id = :importId")
                .param("importId", importId)
                .query()
                .listOfRows();
    }

    public Optional<ReconciliationBreak> findBreak(UUID breakId) {
        return jdbc.sql(SELECT_BREAK + " WHERE id = :id")
                .param("id", breakId)
                .query(BREAK_MAPPER)
                .optional();
    }

    public void explainBreak(UUID breakId, String explanation) {
        jdbc.sql(
                        """
                        UPDATE reconciliation_break
                           SET status = 'EXPLAINED', explanation = :explanation
                         WHERE id = :id
                        """)
                .param("id", breakId)
                .param("explanation", explanation)
                .update();
    }

    public void resolveBreak(UUID breakId, UUID entryId, UUID resolvedBy, String explanation, BreakStatus status) {
        jdbc.sql(
                        """
                        UPDATE reconciliation_break
                           SET status = CAST(:status AS break_status), resolving_entry_id = :entryId,
                               resolved_by = :resolvedBy, resolved_at = now(),
                               explanation = COALESCE(:explanation, explanation)
                         WHERE id = :id
                        """)
                .param("id", breakId)
                .param("status", status.name())
                .param("entryId", entryId)
                .param("resolvedBy", resolvedBy)
                .param("explanation", explanation, Types.VARCHAR)
                .update();
    }

    /** The ledger's own closing figure, in signed terms, as of the period end. */
    public long ledgerClosingSigned(UUID accountId, LocalDate asOf) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(signed_amount_minor), 0)
                          FROM journal_line
                         WHERE account_id = :accountId AND effective_date <= :asOf
                        """)
                .param("accountId", accountId)
                .param("asOf", asOf)
                .query(Long.class)
                .single();
    }

    public int countMatches(UUID importId) {
        return jdbc.sql("SELECT count(*) FROM reconciliation_match WHERE import_id = :id")
                .param("id", importId)
                .query(Integer.class)
                .single();
    }

    public int countStatementLines(UUID importId) {
        return jdbc.sql("SELECT count(*) FROM statement_line WHERE import_id = :id")
                .param("id", importId)
                .query(Integer.class)
                .single();
    }

    /** Absolute value: a match rate is about volume reconciled, not net movement. */
    public long matchedAmountMinor(UUID importId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(abs(l.amount_minor)), 0)
                          FROM reconciliation_match m
                          JOIN statement_line l ON l.id = m.statement_line_id
                         WHERE m.import_id = :id
                        """)
                .param("id", importId)
                .query(Long.class)
                .single();
    }

    private String writeJson(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (Exception e) {
            throw new LedgerException(LedgerError.STATEMENT_NOT_READABLE, "break detail is not serialisable");
        }
    }
}
