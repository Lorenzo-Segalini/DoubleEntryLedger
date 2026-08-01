package dev.lseg.ledger.idempotency;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The JSON contract of the idempotency store.
 *
 * <p>Owns a mapper of its own rather than exposing one as a bean: Spring Boot's
 * auto-configured {@code ObjectMapper} is {@code @ConditionalOnMissingBean}, so
 * publishing a second one would silently suppress the application's.
 *
 * <p>It differs from the application mapper in two deliberate ways.
 *
 * <p><strong>It reads and writes record components only.</strong> The default
 * mapper serialises anything shaped like a getter, and the domain records expose
 * derived accessors — {@code Money.isZero()}, {@code PostedEntry.totalDebitMinor()}
 * — that are computed, not stored. Those become fields the canonical constructor
 * has no parameter for, and a stored response then fails to deserialise on
 * replay. Annotating the domain with {@code @JsonIgnore} would fix it by pushing
 * a serialisation concern into the accounting model, which is precisely what the
 * framework-free rule on that package exists to prevent. Looking at fields and
 * ignoring accessors fixes it here instead.
 *
 * <p><strong>It tolerates unknown properties</strong>, unlike the API mapper. A
 * stored response outlives deploys within its 24-hour TTL: a retry can arrive
 * after a release that removed a field, and replaying the old shape is better
 * than failing on it.
 */
@Component
class IdempotencyJson {

    private final ObjectMapper mapper;

    IdempotencyJson(Jackson2ObjectMapperBuilder builder) {
        this.mapper = builder.build()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    JsonNode createObjectNode() {
        return mapper.createObjectNode();
    }

    ObjectMapper mapper() {
        return mapper;
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not store the idempotent response", e);
        }
    }

    <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not replay the stored response", e);
        }
    }
}
