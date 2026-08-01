package dev.lseg.ledger.security;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id, UUID familyId, UUID userId, Instant issuedAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
