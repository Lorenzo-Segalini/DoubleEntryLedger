package dev.lseg.ledger.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes expired idempotency records.
 *
 * <p>Losing a record is not dangerous, only inconvenient: a retry arriving after
 * expiry is no longer recognised as a replay, but the partial unique index on
 * {@code journal_entry.idempotency_key} still refuses to post it twice. The
 * caller gets a worse error message, never a duplicate posting — which is why
 * DELETE is granted on this table and nowhere else in the write path.
 */
@Component
class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    private final IdempotencyRepository repository;

    IdempotencySweeper(IdempotencyRepository repository) {
        this.repository = repository;
    }

    // A property placeholder, not SpEL: @ConfigurationProperties beans are named
    // after their prefix and type, so "@idempotencyProperties" does not resolve.
    @Scheduled(fixedDelayString = "${ledger.idempotency.sweep-interval:PT15M}")
    @Transactional
    void sweep() {
        int removed = repository.deleteExpired();
        if (removed > 0) {
            log.info("swept {} expired idempotency records", removed);
        }
    }
}
