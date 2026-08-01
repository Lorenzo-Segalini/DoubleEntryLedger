package dev.lseg.ledger.idempotency;

import java.time.Instant;

/**
 * Whether the work ran, or the original outcome was returned again.
 *
 * <p>The caller usually does not care — the result is the same either way, which
 * is the point. The API layer does care: a replay answers {@code 200} with an
 * {@code Idempotency-Replayed: true} header rather than {@code 201}.
 */
public sealed interface IdempotentOutcome<T> {

    T result();

    boolean replayed();

    record Applied<T>(T result) implements IdempotentOutcome<T> {
        @Override
        public boolean replayed() {
            return false;
        }
    }

    record Replayed<T>(T result, int originalStatus, Instant originallyCompletedAt) implements IdempotentOutcome<T> {
        @Override
        public boolean replayed() {
            return true;
        }
    }
}
