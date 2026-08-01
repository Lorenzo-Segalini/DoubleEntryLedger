package dev.lseg.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.lseg.ledger.domain.Direction;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.support.PostgresIT;

class PostingServiceIT extends PostgresIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @Autowired
    PostingService posting;

    @Autowired
    BalanceQuery balances;

    @Autowired
    AccountRepository accounts;

    private PostingContext context;

    @BeforeEach
    void setUp() {
        truncateJournal();
        context = PostingContext.of(SYSTEM_USER, "req-" + UUID.randomUUID());
    }

    private static Money eur(long minor) {
        return Money.of(minor, EUR);
    }

    private UUID accountId(String code) {
        return accounts.findByCode(code).orElseThrow().id();
    }

    private long balanceOf(String code) {
        return balances.asOf(accountId(code), TODAY).natural().amountMinor();
    }

    @Test
    void postingAThreeLineEntryMovesEveryAffectedBalance() {
        long clearingBefore = balanceOf("1100");
        long feesBefore = balanceOf("5000");
        long revenueBefore = balanceOf("4000");

        posting.post(
                JournalEntry.of(
                        TODAY,
                        "Card payment settled",
                        EUR,
                        EntrySource.API,
                        JournalLine.debit("1100", eur(9_710)),
                        JournalLine.debit("5000", eur(290)),
                        JournalLine.credit("4000", eur(10_000))),
                context);

        // Natural balances: assets and expenses rise on debit, revenue on credit.
        assertThat(balanceOf("1100")).isEqualTo(clearingBefore + 9_710);
        assertThat(balanceOf("5000")).isEqualTo(feesBefore + 290);
        assertThat(balanceOf("4000")).isEqualTo(revenueBefore + 10_000);
    }

    @Test
    void theLedgerStillNetsToZeroAfterPosting() {
        posting.post(JournalEntry.transfer(TODAY, "float top-up", "1000", "1100", eur(250_000)), context);

        assertThat(balances.outOfBalanceMinor()).isZero();
    }

    @Test
    void aPostedEntryCarriesItsProvenance() {
        PostedEntry posted = posting.post(
                JournalEntry.transfer(TODAY, "float top-up", "1000", "1100", eur(250_000))
                        .withExternalRef("psp:test-1"),
                context);

        assertThat(posted.id()).isNotNull();
        assertThat(posted.sequenceNo()).isPositive();
        assertThat(posted.postedAt()).isNotNull();
        assertThat(posted.createdBy()).isEqualTo(SYSTEM_USER);
        assertThat(posted.requestId()).isEqualTo(context.requestId());
        assertThat(posted.externalRef()).isEqualTo("psp:test-1");
        assertThat(posted.lines()).hasSize(2).allSatisfy(l -> assertThat(l.id()).isNotNull());
    }

    @Test
    void effectiveDateAndPostedAtAreRecordedSeparately() {
        // A payment that happened on 30 June but was learned about on 15 July.
        // The gap is what reconciliation classifies as a timing difference.
        PostedEntry posted = posting.post(
                JournalEntry.transfer(LocalDate.of(2026, 6, 30), "backdated", "1000", "1100", eur(1_000)), context);

        // posted_at is assigned by the database (clock_timestamp()), not by the
        // injected clock: when we learned about a fact is a property of the write,
        // not something the application gets to declare. What matters is that the
        // two dates are recorded independently.
        assertThat(posted.effectiveDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(posted.postedAt()).isNotNull();
        assertThat(posted.postedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .isAfter(posted.effectiveDate());
    }

    @Test
    void anEntryEffectiveInTheFutureIsRefused() {
        assertThatThrownBy(() -> posting.post(
                        JournalEntry.transfer(TODAY.plusDays(1), "tomorrow", "1000", "1100", eur(1_000)), context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.POSTDATED_ENTRY);
    }

    @Test
    void anUnknownAccountCodeIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() ->
                        posting.post(JournalEntry.transfer(TODAY, "nowhere", "1000", "9999", eur(1_000)), context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.UNKNOWN_ACCOUNT);

        assertThat(count("journal_entry")).isZero();
    }

    @Test
    void aLineInADifferentCurrencyFromItsAccountIsRefused() {
        // Account 1000 is EUR. The entry claims JPY throughout, so the domain
        // accepts it as internally consistent and the service catches I5.
        JournalEntry jpyEntry = new JournalEntry(
                TODAY,
                "wrong currency",
                Currency.getInstance("JPY"),
                EntrySource.API,
                null,
                List.of(
                        JournalLine.debit("1000", Money.of(1_000, "JPY")),
                        JournalLine.credit("1100", Money.of(1_000, "JPY"))));

        assertThatThrownBy(() -> posting.post(jpyEntry, context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.CURRENCY_MISMATCH);
    }

    @Test
    void reversingAnEntryRestoresEveryBalanceItTouched() {
        long cashBefore = balanceOf("1000");
        long clearingBefore = balanceOf("1100");

        PostedEntry original =
                posting.post(JournalEntry.transfer(TODAY, "float top-up", "1000", "1100", eur(250_000)), context);
        assertThat(balanceOf("1000")).isNotEqualTo(cashBefore);

        posting.reverse(original.id(), "sent to the wrong account", TODAY, context);

        assertThat(balanceOf("1000")).isEqualTo(cashBefore);
        assertThat(balanceOf("1100")).isEqualTo(clearingBefore);
    }

    @Test
    void aReversalMirrorsTheOriginalRatherThanBeingDescribedByTheCaller() {
        PostedEntry original = posting.post(
                JournalEntry.of(
                        TODAY,
                        "Card payment settled",
                        EUR,
                        EntrySource.API,
                        JournalLine.debit("1100", eur(9_710)),
                        JournalLine.debit("5000", eur(290)),
                        JournalLine.credit("4000", eur(10_000))),
                context);

        PostedEntry reversal = posting.reverse(original.id(), "duplicate webhook", TODAY, context);

        assertThat(reversal.reversalOfEntryId()).isEqualTo(original.id());
        assertThat(reversal.source()).isEqualTo(EntrySource.REVERSAL);
        assertThat(reversal.reversalReason()).isEqualTo("duplicate webhook");
        assertThat(reversal.lines()).hasSize(3);

        // Same accounts, same amounts, flipped directions.
        for (int i = 0; i < original.lines().size(); i++) {
            var from = original.lines().get(i);
            var to = reversal.lines().get(i);
            assertThat(to.accountCode()).isEqualTo(from.accountCode());
            assertThat(to.amount()).isEqualTo(from.amount());
            assertThat(to.direction()).isEqualTo(from.direction().opposite());
        }
    }

    @Test
    void theOriginalEntrySurvivesItsReversal() {
        // The whole point of ADR-0001: correcting does not erase.
        PostedEntry original =
                posting.post(JournalEntry.transfer(TODAY, "mistake", "1000", "1100", eur(20_000)), context);

        posting.reverse(original.id(), "wrong amount", TODAY, context);

        assertThat(count("journal_entry")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entry WHERE id = :id")
                        .param("id", original.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anEntryCannotBeReversedTwice() {
        PostedEntry original =
                posting.post(JournalEntry.transfer(TODAY, "mistake", "1000", "1100", eur(20_000)), context);
        posting.reverse(original.id(), "first", TODAY, context);

        assertThatThrownBy(() -> posting.reverse(original.id(), "second", TODAY, context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.ALREADY_REVERSED);
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        PostedEntry original =
                posting.post(JournalEntry.transfer(TODAY, "mistake", "1000", "1100", eur(20_000)), context);
        PostedEntry reversal = posting.reverse(original.id(), "wrong", TODAY, context);

        assertThatThrownBy(() -> posting.reverse(reversal.id(), "undo the undo", TODAY, context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.REVERSAL_OF_REVERSAL);
    }

    @Test
    void aReversalWithoutAReasonIsRefused() {
        PostedEntry original =
                posting.post(JournalEntry.transfer(TODAY, "mistake", "1000", "1100", eur(20_000)), context);

        assertThatThrownBy(() -> posting.reverse(original.id(), "  ", TODAY, context))
                .isInstanceOf(LedgerException.class);
    }

    @Test
    void reversingAnEntryThatDoesNotExistIsANotFound() {
        assertThatThrownBy(() -> posting.reverse(UUID.randomUUID(), "reason", TODAY, context))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.ENTRY_NOT_FOUND);
    }

    @Test
    void aReversalCanBeEffectiveInALaterPeriodThanTheOriginal() {
        PostedEntry original = posting.post(
                JournalEntry.transfer(LocalDate.of(2026, 6, 30), "June entry", "1000", "1100", eur(20_000)), context);

        PostedEntry reversal = posting.reverse(original.id(), "found in July", LocalDate.of(2026, 7, 2), context);

        assertThat(reversal.effectiveDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        // June closes with the original still in effect; the correction lands in July.
        assertThat(balances.asOf(accountId("1100"), LocalDate.of(2026, 6, 30))
                        .natural()
                        .amountMinor())
                .isEqualTo(20_000);
        assertThat(balances.asOf(accountId("1100"), LocalDate.of(2026, 7, 31))
                        .natural()
                        .amountMinor())
                .isZero();
    }

    @Test
    void directionSignsAreAppliedConsistentlyAcrossAccountTypes() {
        // Liability: a credit increases it. Getting this backwards is the classic
        // reporting bug the balance_sign column exists to prevent.
        posting.post(
                JournalEntry.of(
                        TODAY,
                        "customer deposit",
                        EUR,
                        EntrySource.API,
                        JournalLine.debit("1000", eur(50_000)),
                        JournalLine.credit("2100", eur(50_000))),
                context);

        assertThat(balanceOf("2100")).isEqualTo(50_000);
        assertThat(balances.asOf(accountId("2100"), TODAY).signed().amountMinor())
                .isEqualTo(-50_000);
    }

    @Test
    void everyLineOfAnEntryCarriesTheEntrysEffectiveDate() {
        PostedEntry posted = posting.post(
                JournalEntry.transfer(LocalDate.of(2026, 6, 30), "backdated", "1000", "1100", eur(1_000)), context);

        assertThat(jdbc.sql("SELECT DISTINCT effective_date FROM journal_line WHERE entry_id = :id")
                        .param("id", posted.id())
                        .query(LocalDate.class)
                        .list())
                .containsExactly(LocalDate.of(2026, 6, 30));
    }

    @Test
    void lineNumbersAreAssignedInOrder() {
        PostedEntry posted = posting.post(
                JournalEntry.of(
                        TODAY,
                        "three lines",
                        EUR,
                        EntrySource.API,
                        JournalLine.debit("1100", eur(9_710)),
                        JournalLine.debit("5000", eur(290)),
                        JournalLine.credit("4000", eur(10_000))),
                context);

        assertThat(posted.lines()).extracting(l -> l.lineNo()).containsExactly(1, 2, 3);
        assertThat(posted.lines())
                .extracting(l -> l.direction())
                .containsExactly(Direction.DEBIT, Direction.DEBIT, Direction.CREDIT);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
