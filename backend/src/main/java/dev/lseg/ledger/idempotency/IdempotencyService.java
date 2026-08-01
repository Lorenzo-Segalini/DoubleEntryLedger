package dev.lseg.ledger.idempotency;

import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;

/**
 * Makes a write operation safe to retry.
 *
 * <p>The guarantee: a request carrying an {@code Idempotency-Key} is applied
 * <strong>at most once</strong>, and a repeat with the same body returns the
 * original outcome — the same status, the same body, the same entry id — without
 * posting again.
 *
 * <p>That guarantee does not come from checking whether the key exists. A check
 * is a read followed by a write, and two concurrent requests can both read
 * "absent" before either writes. It comes from the primary key of
 * {@code idempotency_record}: claiming is an INSERT, PostgreSQL serialises it,
 * and the loser learns it lost from the database rather than from a race it might
 * have observed wrongly. Everything else here is bookkeeping around that fact.
 *
 * <p>See docs/04-idempotency.md and ADR-0004.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final RequestFingerprint fingerprints;
    private final IdempotencyJson json;
    private final IdempotencyProperties properties;

    public IdempotencyService(
            IdempotencyRepository repository,
            RequestFingerprint fingerprints,
            IdempotencyJson json,
            IdempotencyProperties properties) {
        this.repository = repository;
        this.fingerprints = fingerprints;
        this.json = json;
        this.properties = properties;
    }

    public <T> IdempotentOutcome<T> execute(IdempotencyRequest request, Class<T> resultType, Supplier<T> action) {
        return execute(request, resultType, action, result -> null);
    }

    /**
     * Runs {@code action} at most once for this key.
     *
     * <p>{@code REQUIRES_NEW} is deliberate and load-bearing. The claim, the
     * ledger write and the stored response must commit or roll back together:
     * there must be no instant at which the entry exists and the record does not,
     * or a crash would leave a posting no retry could recognise. Joining an
     * ambient transaction would let a caller widen that boundary and break the
     * property without touching this class.
     *
     * <p>A crash anywhere inside rolls back both, and the retry then finds a clean
     * slate and proceeds normally — which is correct, since nothing was posted.
     *
     * @param entryIdOf extracts the journal entry id from the result, for the
     *     foreign key that lets an operator trace a replay back to its posting
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> IdempotentOutcome<T> execute(
            IdempotencyRequest request, Class<T> resultType, Supplier<T> action, Function<T, UUID> entryIdOf) {

        byte[] fingerprint = fingerprints.of(request.body());

        boolean claimed = repository.tryClaim(
                request.key(),
                request.endpoint(),
                request.principalId(),
                fingerprint,
                // The TTL is applied by the database against its own now(), so
                // expires_at and created_at come from one clock. Computing it here
                // would compare two, and clock skew between app and database would
                // trip the expires_at > created_at constraint.
                properties.ttl());

        if (claimed) {
            T result = action.get();
            repository.complete(
                    request.key(),
                    request.endpoint(),
                    request.principalId(),
                    201,
                    serialise(result),
                    entryIdOf.apply(result));
            return new IdempotentOutcome.Applied<>(result);
        }

        return replay(request, resultType, fingerprint);
    }

    private <T> IdempotentOutcome<T> replay(IdempotencyRequest request, Class<T> resultType, byte[] fingerprint) {
        Optional<IdempotencyRecord> existing =
                repository.find(request.key(), request.endpoint(), request.principalId());

        if (existing.isEmpty()) {
            // The claim was refused and yet nothing is there: the holder rolled
            // back between our INSERT unblocking and this read. Retrying is safe
            // because nothing was posted.
            throw new LedgerException(
                    LedgerError.IDEMPOTENCY_REQUEST_IN_FLIGHT,
                    "the request holding key %s did not complete; retry".formatted(request.key()),
                    Map.of("idempotencyKey", request.key()));
        }

        IdempotencyRecord record = existing.get();

        // MessageDigest.isEqual rather than Arrays.equals: constant-time
        // comparison, so the response time cannot be used to probe for a valid key.
        if (!MessageDigest.isEqual(record.requestFingerprint(), fingerprint)) {
            throw new LedgerException(
                    LedgerError.IDEMPOTENCY_KEY_CONFLICT,
                    "key %s was already used for a different request body".formatted(request.key()),
                    Map.of("idempotencyKey", request.key(), "endpoint", request.endpoint()));
        }

        if (!record.isCompleted()) {
            // Only reachable if the original process died after committing its
            // claim but before completing — which a single transaction cannot do.
            // It exists so an asynchronous variant (the outbox on the roadmap)
            // does not have to change the client contract to be added.
            throw new LedgerException(
                    LedgerError.IDEMPOTENCY_REQUEST_IN_FLIGHT,
                    "a request with key %s is still in flight".formatted(request.key()),
                    Map.of("idempotencyKey", request.key()));
        }

        return new IdempotentOutcome.Replayed<>(
                deserialise(record.responseBody(), resultType), record.responseStatus(), record.completedAt());
    }

    /** Written inside the same transaction as the ledger write, so a replay
     * returns what was stored rather than a re-derivation that could differ. */
    private String serialise(Object result) {
        return json.write(result);
    }

    private <T> T deserialise(String body, Class<T> type) {
        return json.read(body, type);
    }
}
