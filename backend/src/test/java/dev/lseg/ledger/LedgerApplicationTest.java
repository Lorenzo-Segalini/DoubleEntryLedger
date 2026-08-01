package dev.lseg.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Placeholder so `mvn verify` is green on a fresh checkout.
 *
 * <p>Replaced by {@code LedgerApplicationIT} once there is a schema to boot against:
 * a context-load test without Testcontainers would only prove that Spring can read
 * a YAML file. See {@code docs/07-testing.md}.
 */
class LedgerApplicationTest {

    @Test
    void applicationClassIsOnTheClasspath() {
        assertThat(LedgerApplication.class.getPackageName()).isEqualTo("dev.lseg.ledger");
    }
}
