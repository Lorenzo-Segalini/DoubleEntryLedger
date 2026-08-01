package dev.lseg.ledger.idempotency;

import java.util.Objects;
import java.util.UUID;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;

/**
 * What identifies one attempt at a write operation.
 *
 * <p>The key is scoped by endpoint and principal, not global. Two clients that
 * happen to choose the same UUID cannot collide, and a key spent on a transfer
 * cannot short-circuit a reversal.
 *
 * @param key the caller's {@code Idempotency-Key}
 * @param endpoint logical operation name, e.g. {@code POST /api/v1/transfers}
 * @param principalId the authenticated user
 * @param body the request payload, used only to compute a fingerprint
 */
public record IdempotencyRequest(String key, String endpoint, UUID principalId, Object body) {

    public IdempotencyRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(principalId, "principalId");

        // Required, not optional. An optional safety mechanism is the one omitted
        // by the caller who most needed it. See ADR-0004.
        if (key == null || key.isBlank()) {
            throw new LedgerException(
                    LedgerError.IDEMPOTENCY_KEY_REQUIRED, "an Idempotency-Key is required on every write endpoint");
        }
    }

    /**
     * The key qualified by what it is scoped to, for storing on
     * {@code journal_entry.idempotency_key}.
     *
     * <p>That column's unique index is the second line of defence behind this
     * store, and a safety net has to agree with the thing it is backing up.
     * The store treats a request as identified by key <em>plus endpoint plus
     * principal</em>; the index is global. Writing the raw key would make two
     * clients that both chose {@code invoice-1} collide in the journal even though
     * the store considers them unrelated — a false conflict, and one that only
     * appears once a caller uses business-derived keys rather than UUIDs.
     */
    public String scopedKey() {
        return principalId + "|" + endpoint + "|" + key;
    }
}
