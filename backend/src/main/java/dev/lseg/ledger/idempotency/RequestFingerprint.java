package dev.lseg.ledger.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A stable SHA-256 over a request body.
 *
 * <p>Answers exactly one question: <em>is this the same request, or a different
 * request that happens to reuse the key?</em> Same key with a different
 * fingerprint is a client bug — a reused UUID, a mutated payload — and is
 * rejected rather than applied. Silently applying the second request would leave
 * the caller believing both succeeded when exactly one did.
 *
 * <p>Canonicalisation sorts object keys but <strong>preserves array order</strong>.
 * Reordering the lines of an entry changes which account gets line 1, so two
 * differently ordered line arrays are genuinely different requests.
 */
@Component
public class RequestFingerprint {

    /**
     * Fields excluded from the hash because they vary between retries of the same
     * logical request. A correlation id changing must not make a retry look new.
     */
    private static final Set<String> VOLATILE_FIELDS = Set.of("requestId", "request_id", "timestamp", "clientTime");

    private final ObjectMapper mapper;

    public RequestFingerprint(IdempotencyJson json) {
        this.mapper = json.mapper();
    }

    public byte[] of(Object body) {
        try {
            JsonNode canonical = canonicalise(mapper.valueToTree(body));
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("request body is not serialisable", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private JsonNode canonicalise(JsonNode node) {
        if (node.isObject()) {
            ObjectNode source = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            source.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);

            ObjectNode sorted = mapper.createObjectNode();
            for (String name : names) {
                if (VOLATILE_FIELDS.contains(name)) {
                    continue;
                }
                JsonNode value = source.get(name);
                // A field explicitly set to null and a field left out describe the
                // same request, so both are dropped rather than hashed differently.
                if (value.isNull()) {
                    continue;
                }
                sorted.set(name, canonicalise(value));
            }
            return sorted;
        }

        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(element -> copy.add(canonicalise(element)));
            return copy;
        }

        return node;
    }
}
