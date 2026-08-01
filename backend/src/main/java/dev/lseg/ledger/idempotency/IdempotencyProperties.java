package dev.lseg.ledger.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ttl how long a record stays replayable. Comfortably beyond any realistic
 *     client retry schedule, and short enough to keep the table small. After
 *     expiry the semantics degrade gracefully rather than dangerously: a very
 *     late retry is no longer recognised as a replay, but the partial unique
 *     index on {@code journal_entry.idempotency_key} still refuses to post twice.
 * @param sweepInterval how often expired records are removed
 */
@ConfigurationProperties("ledger.idempotency")
public record IdempotencyProperties(Duration ttl, Duration sweepInterval) {

    public IdempotencyProperties {
        if (ttl == null) {
            ttl = Duration.ofHours(24);
        }
        if (sweepInterval == null) {
            sweepInterval = Duration.ofMinutes(15);
        }
    }
}
