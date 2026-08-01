package dev.lseg.ledger.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    /**
     * Attempts to claim a key.
     *
     * <p>This is where the at-most-once guarantee lives. It is an INSERT with
     * {@code ON CONFLICT DO NOTHING}, so PostgreSQL serialises concurrent
     * attempts on the primary key and exactly one caller wins. A preceding
     * {@code SELECT} would not do: two requests can both read "absent" before
     * either writes, and retries frequently arrive concurrently — a client
     * timeout fires while the original is still running.
     *
     * @return true if this caller claimed the key and should do the work
     */
    boolean tryClaim(String key, String endpoint, UUID principalId, byte[] fingerprint, Duration ttl);

    Optional<IdempotencyRecord> find(String key, String endpoint, UUID principalId);

    void complete(String key, String endpoint, UUID principalId, int responseStatus, String responseBody, UUID entryId);

    /**
     * Removes records past their TTL.
     *
     * <p>Expiry is evaluated by the database, not against a clock passed in, for
     * the same reason the TTL is: one time source, no skew.
     */
    int deleteExpired();
}
