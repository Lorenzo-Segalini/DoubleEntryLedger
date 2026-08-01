package dev.lseg.ledger.idempotency;

import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String CLAIM =
            """
            INSERT INTO idempotency_record (key, endpoint, principal_id, request_fingerprint, status, expires_at)
            VALUES (:key, :endpoint, :principalId, :fingerprint, 'IN_PROGRESS',
                    now() + make_interval(secs => :ttlSeconds))
            ON CONFLICT (key, endpoint, principal_id) DO NOTHING
            """;

    private static final String SELECT =
            """
            SELECT key, endpoint, principal_id, request_fingerprint, status, response_status,
                   response_body, entry_id, created_at, completed_at, expires_at
              FROM idempotency_record
             WHERE key = :key AND endpoint = :endpoint AND principal_id = :principalId
            """;

    private static final String COMPLETE =
            """
            UPDATE idempotency_record
               SET status = 'COMPLETED',
                   response_status = :responseStatus,
                   response_body = CAST(:responseBody AS jsonb),
                   entry_id = :entryId,
                   completed_at = now()
             WHERE key = :key AND endpoint = :endpoint AND principal_id = :principalId
            """;

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("key"),
            rs.getString("endpoint"),
            rs.getObject("principal_id", UUID.class),
            rs.getBytes("request_fingerprint"),
            IdempotencyStatus.valueOf(rs.getString("status")),
            rs.getObject("response_status", Integer.class),
            rs.getString("response_body"),
            rs.getObject("entry_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null
                    ? null
                    : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcClient jdbc;

    JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryClaim(String key, String endpoint, UUID principalId, byte[] fingerprint, Duration ttl) {
        // A concurrent uncommitted claim makes this statement block on the
        // primary key until that transaction resolves, then insert (if it rolled
        // back) or return zero rows (if it committed). Either answer is correct
        // and neither is a guess about timing.
        int inserted = jdbc.sql(CLAIM)
                .param("key", key)
                .param("endpoint", endpoint)
                .param("principalId", principalId)
                .param("fingerprint", fingerprint)
                .param("ttlSeconds", (double) ttl.toSeconds())
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key, String endpoint, UUID principalId) {
        return jdbc.sql(SELECT)
                .param("key", key)
                .param("endpoint", endpoint)
                .param("principalId", principalId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void complete(
            String key, String endpoint, UUID principalId, int responseStatus, String responseBody, UUID entryId) {
        jdbc.sql(COMPLETE)
                .param("key", key)
                .param("endpoint", endpoint)
                .param("principalId", principalId)
                .param("responseStatus", responseStatus)
                .param("responseBody", responseBody)
                .param("entryId", entryId, Types.OTHER)
                .update();
    }

    @Override
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM idempotency_record WHERE expires_at < now()")
                .update();
    }
}
