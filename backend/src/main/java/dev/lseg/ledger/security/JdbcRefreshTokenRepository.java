package dev.lseg.ledger.security;

import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final RowMapper<RefreshToken> MAPPER = (rs, rowNum) -> new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("family_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("used_at") == null
                    ? null
                    : rs.getTimestamp("used_at").toInstant(),
            rs.getTimestamp("revoked_at") == null
                    ? null
                    : rs.getTimestamp("revoked_at").toInstant());

    private final JdbcClient jdbc;

    JdbcRefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID store(UUID familyId, UUID userId, byte[] tokenHash, Duration ttl, String userAgent, String ipAddress) {
        // The expiry is computed by the database from its own now(), as with the
        // idempotency store: one clock, so the expires_at > issued_at constraint
        // cannot be tripped by skew between the application and PostgreSQL.
        return jdbc.sql(
                        """
                        INSERT INTO refresh_token (family_id, user_id, token_hash, expires_at, user_agent, ip_address)
                        VALUES (:familyId, :userId, :hash, now() + make_interval(secs => :ttlSeconds),
                                :userAgent, CAST(:ip AS inet))
                        RETURNING id
                        """)
                .param("familyId", familyId)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("ttlSeconds", (double) ttl.toSeconds())
                .param("userAgent", userAgent, Types.VARCHAR)
                .param("ip", ipAddress, Types.VARCHAR)
                .query(UUID.class)
                .single();
    }

    @Override
    public Optional<RefreshToken> findByHash(byte[] tokenHash) {
        return jdbc.sql(
                        """
                        SELECT id, family_id, user_id, issued_at, expires_at, used_at, revoked_at
                          FROM refresh_token
                         WHERE token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void markUsed(UUID id, UUID replacedBy) {
        jdbc.sql("UPDATE refresh_token SET used_at = now(), replaced_by = :replacedBy WHERE id = :id")
                .param("id", id)
                .param("replacedBy", replacedBy)
                .update();
    }

    @Override
    public int revokeFamily(UUID familyId) {
        return jdbc.sql(
                        """
                        UPDATE refresh_token
                           SET revoked_at = now()
                         WHERE family_id = :familyId AND revoked_at IS NULL
                        """)
                .param("familyId", familyId)
                .update();
    }

    @Override
    public int revokeAllForUser(UUID userId) {
        return jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
                .param("userId", userId)
                .update();
    }

    @Override
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM refresh_token WHERE expires_at < now()").update();
    }
}
