package dev.lseg.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import dev.lseg.ledger.LedgerApplication;
import dev.lseg.ledger.domain.Direction;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.support.LedgerPostgres;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * The invariants asserted against the real database over thousands of randomly
 * generated histories.
 *
 * <p>The example-based suites in {@link PostingServiceIT} check the sequences
 * someone thought to write down. These check the ones nobody did: arbitrary
 * interleavings of postings and reversals across arbitrary dates, with the
 * ledger required to stay balanced throughout.
 *
 * <h2>Why this is not a {@code @SpringBootTest}</h2>
 *
 * jqwik runs on its own JUnit Platform engine and does not process Jupiter
 * extensions, so {@code SpringExtension} — and therefore {@code @SpringBootTest},
 * {@code @Autowired} and {@code @ServiceConnection} — has no effect here. The
 * context is built by hand in {@link #startContext()} against the container
 * {@link LedgerPostgres} already shares with the rest of the suite.
 */
class LedgerDatabasePropertyIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Codes from the seeded chart of accounts, all EUR. */
    private static final List<String> ACCOUNT_CODES =
            List.of("1000", "1100", "1200", "2000", "2100", "4000", "5000", "9000");

    private static ConfigurableApplicationContext context;
    private static PostingService posting;
    private static BalanceQuery balances;
    private static AccountRepository accounts;
    private static JdbcClient jdbc;

    @BeforeContainer
    static void startContext() {
        // Passed as command-line arguments, NOT via SpringApplicationBuilder
        // .properties(). That method registers *default* properties, which sit at
        // the bottom of Spring's precedence order and lose to application.yml —
        // whose datasource URL falls back to localhost:5432. The context then
        // silently connects to whatever Postgres happens to be listening there,
        // which on a developer machine running `pnpm db` is a real database that
        // this class truncates before every try.
        context = new SpringApplicationBuilder(LedgerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + LedgerPostgres.INSTANCE.getJdbcUrl(),
                        "--spring.datasource.username=" + LedgerPostgres.INSTANCE.getUsername(),
                        "--spring.datasource.password=" + LedgerPostgres.INSTANCE.getPassword(),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");

        posting = context.getBean(PostingService.class);
        balances = context.getBean(BalanceQuery.class);
        accounts = context.getBean(AccountRepository.class);
        jdbc = context.getBean(JdbcClient.class);

        assertConnectedToTheTestContainer();
    }

    /**
     * Refuses to run against anything but the container.
     *
     * <p>This class truncates the journal before every try. Pointed at the wrong
     * database it would destroy data and still report green, which is exactly what
     * happened before the fix above — the suite passed locally against the Compose
     * database and only failed in CI, where nothing listens on localhost:5432.
     * A wrong connection must be a loud failure, not a silent one.
     */
    private static void assertConnectedToTheTestContainer() {
        String expectedPort = String.valueOf(LedgerPostgres.INSTANCE.getMappedPort(5432));
        String actualUrl;
        try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
            actualUrl = connection.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not inspect the test datasource", e);
        }

        if (!actualUrl.contains(":" + expectedPort + "/")) {
            throw new IllegalStateException("refusing to run: this suite truncates the journal, and the context is "
                    + "connected to " + actualUrl + " rather than the test container on port " + expectedPort);
        }
    }

    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Each try starts from an empty journal.
     *
     * <p>Without this, tries accumulate and a property that passed would keep
     * passing for the wrong reason — the invariants below must hold for the
     * generated history alone, not for it plus whatever ran before.
     */
    @BeforeTry
    void authenticateAsOperator() {
        // @WithMockUser is a Jupiter extension and jqwik does not run those, so
        // the context is populated by hand. See the class javadoc.
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "operator@demo.local", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @BeforeTry
    void emptyTheJournal() {
        jdbc.sql("TRUNCATE journal_line, journal_entry RESTART IDENTITY CASCADE")
                .update();
    }

    // ---------------------------------------------------------------- generators

    /**
     * Entries balanced by construction: one to three debit lines against a single
     * credit line that absorbs their total.
     *
     * <p>Shaped after the case that makes a two-line-only model wrong — a
     * settlement split between a clearing account and a fee — rather than after a
     * plain transfer.
     */
    @Provide
    Arbitrary<JournalEntry> entries() {
        return Combinators.combine(
                        Arbitraries.of(ACCOUNT_CODES).set().ofMinSize(2).ofMaxSize(4),
                        Arbitraries.longs().between(1L, 5_000_000L).list().ofSize(3),
                        Arbitraries.integers().between(0, 400))
                .as(LedgerDatabasePropertyIT::buildEntry);
    }

    @Provide
    Arbitrary<List<JournalEntry>> histories() {
        return entries().list().ofMinSize(1).ofMaxSize(8);
    }

    private static JournalEntry buildEntry(Set<String> codes, List<Long> amounts, int daysAgo) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(codes));
        String creditCode = ordered.removeLast();

        List<JournalLine> lines = new ArrayList<>();
        long total = 0L;
        for (int i = 0; i < ordered.size(); i++) {
            long amount = amounts.get(i % amounts.size());
            lines.add(JournalLine.debit(ordered.get(i), Money.of(amount, EUR)));
            total += amount;
        }
        lines.add(JournalLine.credit(creditCode, Money.of(total, EUR)));

        // Always in the past, so the postdating rule never rejects generated
        // input — that rule has its own example-based test.
        LocalDate date = LocalDate.now().minusDays(daysAgo);
        return new JournalEntry(date, "generated entry", EUR, EntrySource.API, null, lines);
    }

    private static PostingContext context() {
        return PostingContext.of(SYSTEM_USER, "prop-" + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- properties

    /**
     * Invariant I7, the one the health check publishes in production.
     *
     * <p>If this ever fails, double entry has been broken somewhere between the
     * domain model and the stored generated column.
     */
    @Property(tries = 200)
    void theWholeJournalAlwaysNetsToZero(@ForAll("histories") List<JournalEntry> history) {
        history.forEach(entry -> posting.post(entry, context()));

        // Non-vacuity guard. A sum over an empty journal is also zero, so without
        // this the property would keep passing if posting silently stopped working.
        assertThat(countEntries()).isEqualTo(history.size());
        assertThat(countLines()).isGreaterThanOrEqualTo(2L * history.size());

        assertThat(balances.outOfBalanceMinor()).isZero();
    }

    /**
     * The derived balance is the sum of the account's own movements.
     *
     * <p>This is what ADR-0003 claims and what a stored balance column would
     * eventually stop satisfying. It compares two independent paths to the same
     * number: the aggregate query, and a raw sum over the lines.
     */
    @Property(tries = 200)
    void aDerivedBalanceEqualsTheSumOfItsMovements(@ForAll("histories") List<JournalEntry> history) {
        history.forEach(entry -> posting.post(entry, context()));

        // At least one account must have moved, or the comparison below is
        // 0 == 0 for every account and proves nothing.
        assertThat(snapshotBalances().values()).anySatisfy(b -> assertThat(b).isNotZero());

        for (String code : ACCOUNT_CODES) {
            UUID accountId = accounts.findByCode(code).orElseThrow().id();

            long fromQuery = balances.asOf(accountId, LocalDate.now()).signed().amountMinor();
            long fromLines = jdbc.sql(
                            "SELECT COALESCE(SUM(signed_amount_minor), 0) FROM journal_line WHERE account_id = :id")
                    .param("id", accountId)
                    .query(Long.class)
                    .single();

            assertThat(fromQuery).as("balance of account %s", code).isEqualTo(fromLines);
        }
    }

    /** Reversing every entry must return every account to where it started. */
    @Property(tries = 100)
    void reversingEveryEntryRestoresEveryBalance(@ForAll("histories") List<JournalEntry> history) {
        Map<String, Long> before = snapshotBalances();

        List<PostedEntry> posted =
                history.stream().map(entry -> posting.post(entry, context())).toList();

        posted.forEach(entry -> posting.reverse(entry.id(), "property test", entry.effectiveDate(), context()));

        assertThat(snapshotBalances()).isEqualTo(before);
        // History is not erased in the process: every entry has a partner.
        assertThat(countEntries()).isEqualTo(2L * history.size());
    }

    /**
     * A balance computed as of a past date does not move when later entries
     * arrive.
     *
     * <p>The property that makes historical reporting trustworthy, and the one a
     * mutable journal would lose. It holds because entries are append-only and
     * balances filter on {@code effective_date}.
     */
    @Property(tries = 100)
    void historicalBalancesAreStableAsNewEntriesArrive(@ForAll("histories") List<JournalEntry> history) {
        LocalDate cutoff = LocalDate.now().minusDays(500);

        history.forEach(entry -> posting.post(entry, context()));
        Map<String, Long> asOfCutoff = snapshotBalancesAsOf(cutoff);

        // Everything generated is within the last 400 days, so nothing is
        // effective before the cutoff and every balance there must be zero.
        assertThat(asOfCutoff.values())
                .allSatisfy(balance -> assertThat(balance).isZero());

        history.forEach(entry -> posting.post(entry, context()));

        assertThat(snapshotBalancesAsOf(cutoff)).isEqualTo(asOfCutoff);
    }

    /** The trial balance is balanced by construction, not by assumption. */
    @Property(tries = 100)
    void theTrialBalanceAlwaysSumsToZero(@ForAll("histories") List<JournalEntry> history) {
        history.forEach(entry -> posting.post(entry, context()));

        List<TrialBalanceRow> rows = balances.trialBalance(LocalDate.now(), "EUR");

        long signedTotal = rows.stream()
                .mapToLong(
                        row -> row.balance().amountMinor() * (row.type().normalBalance() == Direction.DEBIT ? 1 : -1))
                .sum();
        long debits = rows.stream().mapToLong(r -> r.debit().amountMinor()).sum();
        long credits = rows.stream().mapToLong(r -> r.credit().amountMinor()).sum();

        assertThat(signedTotal).isZero();
        assertThat(debits).isEqualTo(credits);
    }

    /** Every line carries the entry's own effective date — the denormalised copy never drifts. */
    @Property(tries = 100)
    void everyLineSharesItsEntrysEffectiveDate(@ForAll("histories") List<JournalEntry> history) {
        history.forEach(entry -> posting.post(entry, context()));

        long mismatches = jdbc.sql(
                        """
                        SELECT count(*)
                          FROM journal_line l
                          JOIN journal_entry e ON e.id = l.entry_id
                         WHERE l.effective_date <> e.effective_date
                        """)
                .query(Long.class)
                .single();

        assertThat(mismatches).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Long> snapshotBalances() {
        return snapshotBalancesAsOf(LocalDate.now());
    }

    private Map<String, Long> snapshotBalancesAsOf(LocalDate asOf) {
        Map<String, Long> snapshot = new HashMap<>();
        for (String code : ACCOUNT_CODES) {
            UUID id = accounts.findByCode(code).orElseThrow().id();
            snapshot.put(code, balances.asOf(id, asOf).signed().amountMinor());
        }
        return snapshot;
    }

    private long countEntries() {
        return jdbc.sql("SELECT count(*) FROM journal_entry").query(Long.class).single();
    }

    private long countLines() {
        return jdbc.sql("SELECT count(*) FROM journal_line").query(Long.class).single();
    }
}
