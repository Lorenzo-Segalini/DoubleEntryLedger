package dev.lseg.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The fingerprint answers one question: is this the same request, or a different
 * one that happens to reuse the key? These tests pin down where that line sits.
 */
class RequestFingerprintTest {

    private RequestFingerprint fingerprints;

    @BeforeEach
    void setUp() {
        fingerprints = new RequestFingerprint(new IdempotencyJson(new Jackson2ObjectMapperBuilder()));
    }

    private record Transfer(String from, String to, long amountMinor, String currency) {}

    @Test
    void theSameRequestAlwaysHashesTheSame() {
        assertThat(fingerprints.of(new Transfer("1000", "1100", 250_000, "EUR")))
                .isEqualTo(fingerprints.of(new Transfer("1000", "1100", 250_000, "EUR")));
    }

    @Test
    void anyChangeToTheRequestChangesTheHash() {
        byte[] original = fingerprints.of(new Transfer("1000", "1100", 250_000, "EUR"));

        assertThat(fingerprints.of(new Transfer("1000", "1100", 250_001, "EUR")))
                .isNotEqualTo(original);
        assertThat(fingerprints.of(new Transfer("1000", "1200", 250_000, "EUR")))
                .isNotEqualTo(original);
        assertThat(fingerprints.of(new Transfer("1000", "1100", 250_000, "USD")))
                .isNotEqualTo(original);
    }

    @Test
    void keyOrderDoesNotMatter() {
        // Two clients serialising the same object can emit fields in any order.
        // That is not a different request.
        var a = Map.of("to", "1100", "from", "1000", "amountMinor", 250_000);
        var b = Map.of("amountMinor", 250_000, "from", "1000", "to", "1100");

        assertThat(fingerprints.of(a)).isEqualTo(fingerprints.of(b));
    }

    @Test
    void nestedKeyOrderDoesNotMatterEither() {
        var a = Map.of("lines", List.of(Map.of("account", "1000", "direction", "DEBIT")));
        var b = Map.of("lines", List.of(Map.of("direction", "DEBIT", "account", "1000")));

        assertThat(fingerprints.of(a)).isEqualTo(fingerprints.of(b));
    }

    @Test
    void arrayOrderDoesMatter() {
        // Reordering the lines of an entry changes which account gets line 1, so
        // these are genuinely different requests and must not replay each other.
        var a = Map.of("lines", List.of("1000", "1100"));
        var b = Map.of("lines", List.of("1100", "1000"));

        assertThat(fingerprints.of(a)).isNotEqualTo(fingerprints.of(b));
    }

    @Test
    void volatileFieldsAreIgnored() {
        // A correlation id changes on every retry of the same logical request.
        // Hashing it would make every retry look like a new request and defeat
        // the entire mechanism.
        var withOne = Map.of("amountMinor", 250_000, "requestId", "01JQ8Z5K3M");
        var withAnother = Map.of("amountMinor", 250_000, "requestId", "01ZZZZZZZZ");

        assertThat(fingerprints.of(withOne)).isEqualTo(fingerprints.of(withAnother));
    }

    @Test
    void anAbsentFieldAndAnExplicitNullAreTheSameRequest() {
        var absent = new java.util.HashMap<String, Object>();
        absent.put("amountMinor", 250_000);

        var explicitNull = new java.util.HashMap<String, Object>();
        explicitNull.put("amountMinor", 250_000);
        explicitNull.put("memo", null);

        assertThat(fingerprints.of(absent)).isEqualTo(fingerprints.of(explicitNull));
    }

    @Test
    void theHashIsASha256() {
        assertThat(fingerprints.of(new Transfer("1000", "1100", 1, "EUR"))).hasSize(32);
    }

    @Test
    void derivedAccessorsDoNotLeakIntoTheFingerprint() {
        // The store's mapper reads record components only. If it picked up
        // computed getters instead, a change in a derived value would look like a
        // different request even though the caller sent the same bytes.
        record WithDerived(long amountMinor) {
            @SuppressWarnings("unused")
            boolean isLarge() {
                return amountMinor > 100;
            }
        }

        assertThat(fingerprints.of(new WithDerived(50))).isNotEqualTo(fingerprints.of(new WithDerived(500)));
        assertThat(fingerprints.of(new WithDerived(50))).isEqualTo(fingerprints.of(new WithDerived(50)));
    }
}
