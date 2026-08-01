package dev.lseg.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;
import dev.lseg.ledger.support.PostgresIT;

@WithMockUser(username = "operator@demo.local", roles = "OPERATOR")
class ReconciliationServiceIT extends PostgresIT {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    PostingService posting;

    private PostingContext context;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM reconciliation_break").update();
        jdbc.sql("DELETE FROM reconciliation_match").update();
        jdbc.sql("DELETE FROM statement_line").update();
        jdbc.sql("DELETE FROM statement_import").update();
        truncateJournal();
        context = PostingContext.of(OPERATOR_ID, "req-" + UUID.randomUUID());
    }

    // ---------------------------------------------------------------- the happy case

    @Test
    void aStatementThatAgreesWithTheJournalProducesNoBreaks() {
        // 1000 Cash at Bank: money in is a debit.
        post("2026-06-04", 100_000, "CARD SETTLEMENT", "TX-1");
        post("2026-06-06", -25_000, "SUPPLIER PAYMENT", "TX-2");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,CARD SETTLEMENT,TX-1
                2026-06-06,-250.00,EUR,SUPPLIER PAYMENT,TX-2
                """,
                0,
                75_000);

        assertThat(report.bridge()).isEmpty();
        assertThat(report.differenceMinor()).isZero();
        assertThat(report.bridgeBalanced()).isTrue();
        assertThat(report.matchedCount()).isEqualTo(2);
        assertThat(report.matchRate()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- the bridge

    @Test
    void anUnbookedBankChargeBecomesAMissingInLedgerBreakThatClosesTheBridge() {
        post("2026-06-04", 100_000, "CARD SETTLEMENT", "TX-1");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,CARD SETTLEMENT,TX-1
                2026-06-28,-14.50,EUR,ACCOUNT MAINTENANCE FEE,TX-FEE
                """,
                0,
                98_550);

        assertThat(report.bridge()).hasSize(1);
        var row = report.bridge().getFirst();
        assertThat(row.type()).isEqualTo(BreakType.MISSING_IN_LEDGER);
        assertThat(row.deltaMinor()).isEqualTo(-1_450);

        // The whole point: the explanations add up to the difference.
        assertThat(report.bridgeTotalMinor()).isEqualTo(report.differenceMinor());
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void aDuplicateInTheJournalIsClassifiedAsSuchRatherThanAsMissing() {
        post("2026-06-04", 100_000, "CARD SETTLEMENT psp", "psp:pay_1");
        post("2026-06-24", 100_000, "CARD SETTLEMENT psp", "psp:pay_1");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,CARD SETTLEMENT psp,psp:pay_1
                """,
                0,
                100_000);

        assertThat(report.bridge()).hasSize(1);
        assertThat(report.bridge().getFirst().type()).isEqualTo(BreakType.DUPLICATE_IN_LEDGER);
        assertThat(report.bridge().getFirst().deltaMinor()).isEqualTo(-100_000);
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void aMovementAtTheCutOffIsATimingDifferenceNotAnError() {
        // Booked 30 June, clears at the bank on 2 July. Calling this "missing"
        // sends an operator chasing a transaction that is fine.
        post("2026-06-30", -15_000, "SUPPLIER TRANSFER INITIATED", "TX-LATE");

        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-02,100.00,EUR,UNRELATED
                """,
                0,
                10_000);

        assertThat(report.bridge())
                .extracting(ReconciliationReport.BridgeRow::type)
                .contains(BreakType.TIMING_DIFFERENCE);

        // It is still a real difference between the two documents, so it still
        // contributes to the bridge. Only the label says "no action needed".
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void aDuplicateOnTheStatementIsClassifiedAsSuch() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-10,50.00,EUR,BANK REPORTED TWICE
                2026-06-10,50.00,EUR,BANK REPORTED TWICE
                """,
                0,
                10_000);

        assertThat(report.bridge())
                .extracting(ReconciliationReport.BridgeRow::type)
                .containsExactlyInAnyOrder(BreakType.MISSING_IN_LEDGER, BreakType.DUPLICATE_IN_STATEMENT);
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void aForeignCurrencyLineIsFlaggedAndContributesNothing() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-10,100.00,USD,DOLLAR RECEIPT
                """,
                0,
                10_000);

        var row = report.bridge().getFirst();
        assertThat(row.type()).isEqualTo(BreakType.CURRENCY_MISMATCH);
        // It cannot be added to a EUR balance at all: it needs a rate and a
        // decision, which is phase 3.
        assertThat(row.deltaMinor()).isZero();
    }

    @Test
    void aDisagreementAboutTheOpeningBalanceIsItsOwnBreak() {
        // The ledger starts the period at zero; the statement claims 410.65 was
        // already there. Closing balances differ by the opening gap plus the
        // movement gap, and only the second is explained by matching — so without
        // this break the bridge could not close however good the matching was.
        post("2026-06-04", 100_000, "SETTLEMENT", "TX-1");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,SETTLEMENT,TX-1
                """,
                41_065,
                141_065);

        assertThat(report.bridge())
                .extracting(ReconciliationReport.BridgeRow::type)
                .containsExactly(BreakType.OPENING_BALANCE_MISMATCH);
        assertThat(report.bridge().getFirst().deltaMinor()).isEqualTo(41_065);
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void anAgreeingOpeningBalanceProducesNoBreak() {
        post("2026-05-20", 41_065, "PRIOR PERIOD", "TX-0");
        post("2026-06-04", 100_000, "SETTLEMENT", "TX-1");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,SETTLEMENT,TX-1
                """,
                41_065,
                141_065);

        assertThat(report.bridge()).isEmpty();
        assertThat(report.bridgeBalanced()).isTrue();
    }

    // ---------------------------------------------------------------- matching rules

    @Test
    void anExactReferenceMatchBeatsAnAmbiguousAmountMatch() {
        post("2026-06-04", 100_000, "SETTLEMENT A", "TX-A");
        post("2026-06-04", 100_000, "SETTLEMENT B", "TX-B");

        var report = importStatement(
                """
                value_date,amount,currency,description,external_id
                2026-06-04,1000.00,EUR,SETTLEMENT A,TX-A
                2026-06-04,1000.00,EUR,SETTLEMENT B,TX-B
                """,
                0,
                200_000);

        assertThat(report.matchedCount()).isEqualTo(2);
        assertThat(report.bridge()).isEmpty();
    }

    @Test
    void twoEquallyGoodCandidatesAreLeftUnmatchedRatherThanGuessed() {
        // Same amount, same date, no references. A wrong automatic match costs
        // more than an unmatched line, because it looks finished.
        post("2026-06-04", 100_000, "PAYMENT", null);
        post("2026-06-04", 100_000, "PAYMENT", null);

        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,PAYMENT
                """,
                0,
                100_000);

        assertThat(report.matchedCount()).isZero();
        assertThat(report.bridgeBalanced()).isTrue();
    }

    @Test
    void aDateWithinTheWindowStillMatches() {
        post("2026-06-04", 100_000, "SETTLEMENT", null);

        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-06,1000.00,EUR,SETTLEMENT
                """,
                0,
                100_000);

        assertThat(report.matchedCount()).isEqualTo(1);
        assertThat(report.bridge()).isEmpty();
    }

    @Test
    void theSameStatementImportedTwiceReturnsTheSameRun() {
        String csv =
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,SETTLEMENT
                """;
        post("2026-06-04", 100_000, "SETTLEMENT", null);

        UUID first = importId(csv, 0, 100_000);
        UUID second = importId(csv, 0, 100_000);

        // Idempotency by natural key: the file's content.
        assertThat(second).isEqualTo(first);
        assertThat(count("statement_import")).isEqualTo(1);
    }

    @Test
    void theRunIsDeterministic() {
        post("2026-06-04", 100_000, "SETTLEMENT", null);
        post("2026-06-10", -25_000, "PAYMENT", null);

        var first = importStatement(
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,SETTLEMENT
                2026-06-11,-250.00,EUR,PAYMENT
                2026-06-20,-10.00,EUR,FEE
                """,
                0,
                74_000);

        setUp();
        post("2026-06-04", 100_000, "SETTLEMENT", null);
        post("2026-06-10", -25_000, "PAYMENT", null);

        var second = importStatement(
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,SETTLEMENT
                2026-06-11,-250.00,EUR,PAYMENT
                2026-06-20,-10.00,EUR,FEE
                """,
                0,
                74_000);

        // A reconciliation you cannot reproduce is not evidence of anything.
        assertThat(second.matchedCount()).isEqualTo(first.matchedCount());
        assertThat(second.bridge())
                .extracting(ReconciliationReport.BridgeRow::type)
                .isEqualTo(first.bridge().stream()
                        .map(ReconciliationReport.BridgeRow::type)
                        .toList());
        assertThat(second.bridgeTotalMinor()).isEqualTo(first.bridgeTotalMinor());
    }

    // ---------------------------------------------------------------- guards

    @Test
    void aStatementThatDoesNotAddUpIsRefusedBeforeAnythingIsMatched() {
        // Reconciling against an inconsistent file produces confident nonsense.
        assertThatThrownBy(() -> importStatement(
                        """
                        value_date,amount,currency,description
                        2026-06-04,1000.00,EUR,SETTLEMENT
                        """,
                        0,
                        999_999))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.STATEMENT_NOT_INTERNALLY_CONSISTENT);

        assertThat(count("statement_import")).isZero();
    }

    // ---------------------------------------------------------------- break lifecycle

    @Test
    void explainingABreakMovesNoMoney() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-28,-14.50,EUR,BANK FEE
                """,
                0,
                -1_450);
        UUID breakId = report.bridge().getFirst().breakId();

        reconciliation.explain(breakId, "known monthly charge, booking next period");

        assertThat(count("journal_entry")).isZero();
        var after = reconciliation.report(report.importId());
        assertThat(after.bridge().getFirst().status()).isEqualTo(BreakStatus.EXPLAINED);
        // Still a live difference, so it still counts.
        assertThat(after.bridgeBalanced()).isTrue();
    }

    @Test
    void resolvingABreakPostsAnAdjustingEntryAndClosesTheBridge() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-28,-14.50,EUR,BANK FEE
                """,
                0,
                -1_450);
        UUID breakId = report.bridge().getFirst().breakId();
        assertThat(report.differenceMinor()).isEqualTo(-1_450);

        UUID entryId = reconciliation.resolve(
                breakId, new ReconciliationService.ResolveCommand("5000", "bank maintenance fee", END, false), context);

        // Posted through the ordinary service: it is an entry like any other.
        assertThat(entryId).isNotNull();
        assertThat(count("journal_entry")).isEqualTo(1);

        var after = reconciliation.report(report.importId());
        assertThat(after.bridge().getFirst().status()).isEqualTo(BreakStatus.RESOLVED);
        // The adjustment moved the ledger onto the statement, so there is nothing
        // left to explain — and the resolved break no longer counts.
        assertThat(after.differenceMinor()).isZero();
        assertThat(after.bridgeTotalMinor()).isZero();
        assertThat(after.bridgeBalanced()).isTrue();
    }

    @Test
    void aBreakCannotBeResolvedTwice() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-28,-14.50,EUR,BANK FEE
                """,
                0,
                -1_450);
        UUID breakId = report.bridge().getFirst().breakId();

        reconciliation.resolve(breakId, new ReconciliationService.ResolveCommand("5000", "fee", END, false), context);

        assertThatThrownBy(() -> reconciliation.resolve(
                        breakId, new ReconciliationService.ResolveCommand("5000", "again", END, false), context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.BREAK_ALREADY_CLOSED);

        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void reconciliationHasNoPrivilegedWritePathIntoTheJournal() {
        var report = importStatement(
                """
                value_date,amount,currency,description
                2026-06-28,-14.50,EUR,BANK FEE
                """,
                0,
                -1_450);

        reconciliation.resolve(
                report.bridge().getFirst().breakId(),
                new ReconciliationService.ResolveCommand("5000", "fee", END, false),
                context);

        // The adjustment is a normal entry: balanced, attributed, audited, and
        // subject to the same append-only rules as everything else.
        assertThat(jdbc.sql("SELECT COALESCE(SUM(signed_amount_minor), 0) FROM journal_line")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT source::text FROM journal_entry")
                        .query(String.class)
                        .single())
                .isEqualTo("ADJUSTMENT");
        assertThat(jdbc.sql("SELECT created_by FROM journal_entry")
                        .query(UUID.class)
                        .single())
                .isEqualTo(OPERATOR_ID);
    }

    // ---------------------------------------------------------------- helpers

    private void post(String date, long bankSignedMinor, String description, String externalRef) {
        // 1000 is an ASSET, so a bank-positive amount is a debit on the account.
        JournalLine onCash = bankSignedMinor > 0
                ? JournalLine.debit("1000", Money.of(bankSignedMinor, "EUR"))
                : JournalLine.credit("1000", Money.of(-bankSignedMinor, "EUR"));
        JournalLine contra = bankSignedMinor > 0
                ? JournalLine.credit("4000", Money.of(bankSignedMinor, "EUR"))
                : JournalLine.debit("5000", Money.of(-bankSignedMinor, "EUR"));

        JournalEntry entry = new JournalEntry(
                LocalDate.parse(date),
                description,
                java.util.Currency.getInstance("EUR"),
                EntrySource.API,
                externalRef,
                List.of(onCash, contra));

        posting.post(entry, PostingContext.of(OPERATOR_ID, "req-" + UUID.randomUUID()));
    }

    private ReconciliationReport importStatement(String csv, long openingMinor, long closingMinor) {
        return reconciliation.report(importId(csv, openingMinor, closingMinor));
    }

    private UUID importId(String csv, long openingMinor, long closingMinor) {
        return reconciliation.importStatement(
                new ReconciliationService.ImportCommand("1000", START, END, openingMinor, closingMinor, "test.csv"),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                OPERATOR_ID);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
