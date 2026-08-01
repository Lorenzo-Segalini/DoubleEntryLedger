package dev.lseg.ledger.security;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    UUID store(UUID familyId, UUID userId, byte[] tokenHash, Duration ttl, String userAgent, String ipAddress);

    Optional<RefreshToken> findByHash(byte[] tokenHash);

    void markUsed(UUID id, UUID replacedBy);

    /** Revokes every live token descended from one login. */
    int revokeFamily(UUID familyId);

    int revokeAllForUser(UUID userId);

    int deleteExpired();
}
