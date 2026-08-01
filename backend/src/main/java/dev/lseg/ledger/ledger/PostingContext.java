package dev.lseg.ledger.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is posting, and under what request.
 *
 * <p>Not part of the domain aggregate: an entry's accounting content is
 * independent of who submitted it. But every posted row records both, because
 * "why does this entry exist" is a question the audit trail has to answer, and
 * {@code requestId} is what links a row in the back office to the log line and
 * trace that produced it.
 */
public record PostingContext(UUID actorId, String requestId, String idempotencyKey) {

    public PostingContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(requestId, "requestId");
    }

    public static PostingContext of(UUID actorId, String requestId) {
        return new PostingContext(actorId, requestId, null);
    }
}
