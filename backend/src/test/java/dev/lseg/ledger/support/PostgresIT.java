package dev.lseg.ledger.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real schema.
 *
 * <p>PostgreSQL, not H2. Half of this system's invariants live in features H2
 * does not have — deferred constraint triggers, stored generated columns, partial
 * unique indexes, {@code pg_trgm} — so an H2 test would pass while verifying
 * behaviour production does not exhibit. Docker is a hard requirement here; that
 * trade is made deliberately in docs/07-testing.md §7.1.
 *
 * <p>The container is static, so one instance serves the whole suite. Flyway runs
 * against it once, which means these tests exercise the migrations too.
 */
@SpringBootTest(
        classes = {dev.lseg.ledger.LedgerApplication.class, PostgresIT.FixedClockConfig.class},
        // The concurrency suite fires 32 simultaneous requests. Losers block on the
        // idempotency primary key while holding a connection, so a pool smaller than
        // the thread count turns contention into connection timeouts and hides what
        // the test is meant to measure.
        properties = {"spring.datasource.hikari.maximum-pool-size=40"})
public abstract class PostgresIT {

    /** Fixed, so "reject postdated entries" is testable without waiting for midnight. */
    public static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

    /**
     * Deliberately not annotated {@code @Container}: that extension stops the
     * container when the declaring class finishes, while Spring keeps the
     * application context cached across test classes — the second IT class then
     * fails on a dead database. The shared singleton in {@link LedgerPostgres}
     * outlives every class and is reclaimed by Ryuk at JVM exit.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = LedgerPostgres.INSTANCE;

    @Autowired
    protected JdbcClient jdbc;

    /**
     * Runs a block in one transaction.
     *
     * <p>Needed whenever a test writes an entry and its lines by hand: the balance
     * rule is a DEFERRABLE INITIALLY DEFERRED trigger that fires at COMMIT, so
     * auto-committing each statement would trip "at least 2 lines" on the first
     * insert instead of exercising the rule under test.
     */
    @Autowired
    protected TransactionTemplate inTransaction;

    /**
     * Removes everything the previous test posted.
     *
     * <p>TRUNCATE as the owning role, not through the application. There is
     * deliberately no code path that can delete a journal entry, and this must not
     * become one — {@code AppendOnlyInvariantIT} asserts precisely that such a
     * path is unreachable.
     *
     * <p>Rollback-per-test would be tidier but is unusable here for the reason
     * above: a test that never commits never fires the deferred trigger.
     */
    protected void truncateJournal() {
        jdbc.sql("TRUNCATE journal_line, journal_entry RESTART IDENTITY CASCADE")
                .update();
    }

    @TestConfiguration
    public static class FixedClockConfig {

        /**
         * {@code @Primary} rather than relying on the production bean backing off:
         * {@code @ConditionalOnMissingBean} outside auto-configuration resolves in
         * registration order, which is not a guarantee worth depending on.
         */
        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
