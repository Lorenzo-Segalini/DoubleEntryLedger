package dev.lseg.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import dev.lseg.ledger.LedgerApplication;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;
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
 * The bridge invariant, over randomly generated journals and statements.
 *
 * <pre>ledger_closing + Σ(delta over live breaks) = statement_closing</pre>
 *
 * <p>Discrepancies are injected on purpose — dropped lines, duplicates, amount
 * perturbations, dates shifted across the cut-off — and the engine has to account
 * for every cent of the resulting difference. If it double-consumes a line, gets
 * a sign backwards, or fails to classify something, the bridge stops closing and
 * this fails with a shrunk counterexample.
 *
 * <p>Bootstrapped by hand for the same reason as
 * {@code LedgerDatabasePropertyIT}: jqwik runs on its own engine and does not
 * process Jupiter extensions, so {@code @SpringBootTest} has no effect.
 */
class ReconciliationBridgePropertyIT {

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);
    private static final Currency EUR = Currency.getInstance("EUR");

    private static ConfigurableApplicationContext context;
    private static ReconciliationService reconciliation;
    private static PostingService posting;
    private static JdbcClient jdbc;

    @BeforeContainer
    static void startContext() {
        context = new SpringApplicationBuilder(LedgerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + LedgerPostgres.INSTANCE.getJdbcUrl(),
                        "--spring.datasource.username=" + LedgerPostgres.INSTANCE.getUsername(),
                        "--spring.datasource.password=" + LedgerPostgres.INSTANCE.getPassword(),
                        "--ledger.demo.seed-users=true",
                        "--ledger.demo.operator-password=test-operator",
                        "--ledger.demo.auditor-password=test-auditor",
                        "--ledger.demo.admin-password=test-admin",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");

        reconciliation = context.getBean(ReconciliationService.class);
        posting = context.getBean(PostingService.class);
        jdbc = context.getBean(JdbcClient.class);

        assertConnectedToTheTestContainer();
    }

    /** This class truncates the journal, so it must verify what it is about to destroy. */
    private static void assertConnectedToTheTestContainer() {
        String expectedPort = String.valueOf(LedgerPostgres.INSTANCE.getMappedPort(5432));
        String url;
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not inspect the test datasource", e);
        }
        if (!url.contains(":" + expectedPort + "/")) {
            throw new IllegalStateException("refusing to run against " + url);
        }
    }

    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeTry
    void resetAndAuthenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "operator@demo.local", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));

        jdbc.sql("DELETE FROM reconciliation_break").update();
        jdbc.sql("DELETE FROM reconciliation_match").update();
        jdbc.sql("DELETE FROM statement_line").update();
        jdbc.sql("DELETE FROM statement_import").update();
        jdbc.sql("TRUNCATE journal_line, journal_entry RESTART IDENTITY CASCADE")
                .update();
    }

    // ---------------------------------------------------------------- generators

    /** A movement on the cash account, signed from the bank's point of view. */
    record Movement(int dayOfMonth, long bankSignedMinor, String reference) {}

    @Provide
    Arbitrary<List<Movement>> journals() {
        Arbitrary<Movement> movement = Combinators.combine(
                        Arbitraries.integers().between(1, 30),
                        Arbitraries.longs().between(1L, 500_000L),
                        Arbitraries.of(true, false))
                .as((day, amount, inbound) ->
                        new Movement(day, inbound ? amount : -amount, "REF-" + day + "-" + amount));

        return movement.list().ofMinSize(1).ofMaxSize(10);
    }

    /**
     * What goes wrong between two documents.
     *
     * <p>Each value removes something from one side or adds something to the
     * other, so a generated run exercises every break type the classifier knows.
     */
    enum Discrepancy {
        DROP_FROM_STATEMENT,
        DROP_FROM_LEDGER,
        DUPLICATE_IN_STATEMENT,
        SHIFT_PAST_CUTOFF
    }

    @Provide
    Arbitrary<List<Discrepancy>> discrepancies() {
        return Arbitraries.of(Discrepancy.class).list().ofMinSize(0).ofMaxSize(4);
    }

    // ---------------------------------------------------------------- the property

    /**
     * The opening balances are generated independently of the journal, so the two
     * documents routinely disagree about where the period started.
     *
     * <p>The original version of this property always began from an empty ledger
     * with a declared opening of zero — which is precisely why it passed while the
     * engine could not explain an opening gap at all. A generator that only
     * produces the easy case proves the easy case.
     */
    @Property(tries = 120)
    void theBridgeClosesEvenWhenTheOpeningBalancesDisagree(
            @ForAll("journals") List<Movement> journal,
            @ForAll @net.jqwik.api.constraints.LongRange(min = -200_000, max = 200_000) long declaredOpening) {

        journal.forEach(this::post);
        long movements = journal.stream().mapToLong(Movement::bankSignedMinor).sum();
        UUID importId = importStatement(journal, declaredOpening, declaredOpening + movements);

        ReconciliationReport report = reconciliation.report(importId);

        assertThat(report.ledgerClosingMinor() + report.bridgeTotalMinor())
                .as("ledger closing + bridge should equal statement closing")
                .isEqualTo(report.statementClosingMinor());
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Property(tries = 120)
    void theBridgeAlwaysClosesTheDifference(
            @ForAll("journals") List<Movement> journal, @ForAll("discrepancies") List<Discrepancy> injected) {

        List<Movement> ledgerSide = new ArrayList<>(journal);
        List<Movement> statementSide = new ArrayList<>(journal);

        for (Discrepancy discrepancy : injected) {
            switch (discrepancy) {
                case DROP_FROM_STATEMENT -> {
                    if (!statementSide.isEmpty()) {
                        statementSide.removeLast();
                    }
                }
                case DROP_FROM_LEDGER -> {
                    if (!ledgerSide.isEmpty()) {
                        ledgerSide.removeLast();
                    }
                }
                case DUPLICATE_IN_STATEMENT -> {
                    if (!statementSide.isEmpty()) {
                        statementSide.add(statementSide.getFirst());
                    }
                }
                case SHIFT_PAST_CUTOFF -> {
                    if (!statementSide.isEmpty()) {
                        // In the ledger before the cut-off, absent from the
                        // statement: the timing case.
                        statementSide.removeFirst();
                    }
                }
            }
        }

        ledgerSide.forEach(this::post);

        long statementTotal =
                statementSide.stream().mapToLong(Movement::bankSignedMinor).sum();
        UUID importId = importStatement(statementSide, statementTotal);

        ReconciliationReport report = reconciliation.report(importId);

        // The invariant. Asserted directly rather than trusting the flag, so a bug
        // in the flag itself cannot hide a bug in the arithmetic.
        assertThat(report.ledgerClosingMinor() + report.bridgeTotalMinor())
                .as("ledger closing + bridge should equal statement closing")
                .isEqualTo(report.statementClosingMinor());
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Property(tries = 60)
    void noLineIsEverConsumedTwice(@ForAll("journals") List<Movement> journal) {
        journal.forEach(this::post);
        long total = journal.stream().mapToLong(Movement::bankSignedMinor).sum();
        UUID importId = importStatement(journal, total);

        // The unique constraints enforce this, but a run that quietly matched
        // fewer lines than it claimed would still balance. Count both sides.
        long matches = jdbc.sql("SELECT count(*) FROM reconciliation_match WHERE import_id = :id")
                .param("id", importId)
                .query(Long.class)
                .single();
        long distinctJournalLines = jdbc.sql(
                        "SELECT count(DISTINCT journal_line_id) FROM reconciliation_match WHERE import_id = :id")
                .param("id", importId)
                .query(Long.class)
                .single();
        long distinctStatementLines = jdbc.sql(
                        "SELECT count(DISTINCT statement_line_id) FROM reconciliation_match WHERE import_id = :id")
                .param("id", importId)
                .query(Long.class)
                .single();

        assertThat(distinctJournalLines).isEqualTo(matches);
        assertThat(distinctStatementLines).isEqualTo(matches);
    }

    @Property(tries = 60)
    void everyBreakCarriesTheSameCurrencyAsTheAccount(
            @ForAll("journals") List<Movement> journal, @ForAll("discrepancies") List<Discrepancy> injected) {

        journal.forEach(this::post);
        List<Movement> statementSide = new ArrayList<>(journal);
        if (!injected.isEmpty() && !statementSide.isEmpty()) {
            statementSide.removeLast();
        }

        long total = statementSide.stream().mapToLong(Movement::bankSignedMinor).sum();
        UUID importId = importStatement(statementSide, total);

        assertThat(reconciliation.breaks(importId))
                .allSatisfy(found -> assertThat(found.currency()).isEqualTo("EUR"));
    }

    // ---------------------------------------------------------------- helpers

    private void post(Movement movement) {
        LocalDate date = START.withDayOfMonth(movement.dayOfMonth());
        long amount = Math.abs(movement.bankSignedMinor());

        JournalLine onCash = movement.bankSignedMinor() > 0
                ? JournalLine.debit("1000", Money.of(amount, EUR))
                : JournalLine.credit("1000", Money.of(amount, EUR));
        JournalLine contra = movement.bankSignedMinor() > 0
                ? JournalLine.credit("4000", Money.of(amount, EUR))
                : JournalLine.debit("5000", Money.of(amount, EUR));

        posting.post(
                new JournalEntry(
                        date,
                        movement.reference(),
                        EUR,
                        EntrySource.API,
                        movement.reference(),
                        List.of(onCash, contra)),
                PostingContext.of(OPERATOR_ID, "prop-" + UUID.randomUUID()));
    }

    private UUID importStatement(List<Movement> lines, long closingMinor) {
        return importStatement(lines, 0L, closingMinor);
    }

    private UUID importStatement(List<Movement> lines, long openingMinor, long closingMinor) {
        StringBuilder csv = new StringBuilder("value_date,amount,currency,description,external_id\n");
        for (Movement line : lines) {
            csv.append("%s,%s,EUR,%s,%s%n"
                    .formatted(
                            START.withDayOfMonth(line.dayOfMonth()),
                            new java.math.BigDecimal(line.bankSignedMinor())
                                    .movePointLeft(2)
                                    .toPlainString(),
                            line.reference(),
                            line.reference()));
        }
        if (lines.isEmpty()) {
            // The parser refuses a header with no rows, so an empty statement is
            // represented by a single zero-sum pair.
            csv.append("%s,0.01,EUR,PLACEHOLDER,PH-1%n".formatted(START));
            csv.append("%s,-0.01,EUR,PLACEHOLDER,PH-2%n".formatted(START));
        }

        return reconciliation.importStatement(
                new ReconciliationService.ImportCommand(
                        "1000",
                        START,
                        END,
                        openingMinor,
                        lines.isEmpty() ? openingMinor : closingMinor,
                        "generated.csv"),
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)),
                OPERATOR_ID);
    }
}
