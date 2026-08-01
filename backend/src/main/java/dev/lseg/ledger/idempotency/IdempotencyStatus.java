package dev.lseg.ledger.idempotency;

public enum IdempotencyStatus {
    /** Claimed, work not yet committed. */
    IN_PROGRESS,
    /** The response is stored and can be replayed verbatim. */
    COMPLETED
}
