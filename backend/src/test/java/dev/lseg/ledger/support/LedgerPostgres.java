package dev.lseg.ledger.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one PostgreSQL container the whole test suite shares.
 *
 * <p>It exists as a separate holder because two different mechanisms need it:
 * {@link PostgresIT} hands it to Spring Boot via {@code @ServiceConnection}, and
 * the jqwik property classes bootstrap their own context against it — jqwik runs
 * on its own test engine and cannot use Jupiter extensions, so it cannot be a
 * {@code @SpringBootTest}.
 *
 * <p>Started once in a static initialiser and never stopped explicitly.
 * Testcontainers' Ryuk sidecar removes it when the JVM exits, which is what keeps
 * the container alive across test classes that would otherwise each tear it down.
 */
public final class LedgerPostgres {

    public static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger")
            .withReuse(true);

    static {
        INSTANCE.start();
    }

    private LedgerPostgres() {}
}
