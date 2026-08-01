package dev.lseg.ledger.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        String key,
        String endpoint,
        UUID principalId,
        byte[] requestFingerprint,
        IdempotencyStatus status,
        Integer responseStatus,
        String responseBody,
        UUID entryId,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt) {

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }
}
