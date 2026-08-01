package dev.lseg.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.support.PostgresIT;

@WithMockUser(username = "operator@demo.local", roles = "OPERATOR")
class JournalPaginationIT extends PostgresIT {

    private static final LocalDate BASE = LocalDate.of(2026, 6, 1);

    @Autowired
    JournalRepository journal;

    @Autowired
    PostingService posting;

    @Autowired
    AccountRepository accounts;

    @BeforeEach
    void setUp() {
        truncateJournal();
    }

    @Test
    void returnsTheMostRecentFirst() {
        post(BASE.plusDays(1), "first");
        post(BASE.plusDays(2), "second");
        post(BASE.plusDays(3), "third");

        EntryPage page = journal.findPage(JournalFilter.unfiltered(), null);

        assertThat(page.items()).extracting(PostedEntry::description).containsExactly("third", "second", "first");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void walksTheWholeJournalInPagesWithoutRepeatingOrSkipping() {
        for (int i = 1; i <= 25; i++) {
            post(BASE.plusDays(i % 10), "entry-" + i);
        }

        List<String> seen = collectAllPages(new JournalFilter(null, null, null, null, null, 7));

        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
    }

    /**
     * The reason this is a cursor and not an offset.
     *
     * <p>Entries arrive while a user reads. Under offset pagination every row
     * shifts down and page two repeats rows from page one — a bug that never
     * appears in a test with a frozen table, and always appears in production.
     */
    @Test
    void newEntriesArrivingMidReadCannotDuplicateOrHideARow() {
        for (int i = 1; i <= 10; i++) {
            post(BASE.plusDays(i), "original-" + i);
        }

        JournalFilter filter = new JournalFilter(null, null, null, null, null, 4);

        EntryPage first = journal.findPage(filter, null);
        List<String> seen = new ArrayList<>(
                first.items().stream().map(PostedEntry::description).toList());

        // Five more entries land between page one and page two, all of them
        // newer than everything already read.
        for (int i = 1; i <= 5; i++) {
            post(BASE.plusDays(20 + i), "arrived-later-" + i);
        }

        String cursor = first.nextCursor();
        while (cursor != null) {
            EntryPage next = journal.findPage(filter, EntryCursor.decode(cursor));
            seen.addAll(next.items().stream().map(PostedEntry::description).toList());
            cursor = next.nextCursor();
        }

        // Every original row appears exactly once. The newcomers are simply not
        // seen — they sort ahead of the cursor, which is the correct answer for a
        // reader who started before they existed.
        assertThat(seen).doesNotHaveDuplicates();
        for (int i = 1; i <= 10; i++) {
            assertThat(seen).contains("original-" + i);
        }
        assertThat(seen).noneMatch(description -> description.startsWith("arrived-later"));
    }

    @Test
    void entriesSharingAnEffectiveDateArePagedWithoutTies() {
        // The date alone is not a position: several entries routinely share one.
        // sequence_no is what makes the cursor unambiguous.
        for (int i = 1; i <= 9; i++) {
            post(BASE, "same-day-" + i);
        }

        List<String> seen = collectAllPages(new JournalFilter(null, null, null, null, null, 2));

        assertThat(seen).hasSize(9).doesNotHaveDuplicates();
    }

    @Test
    void reportsHasMoreEvenWhenThePageIsExactlyFull() {
        for (int i = 1; i <= 6; i++) {
            post(BASE.plusDays(i), "entry-" + i);
        }

        EntryPage page = journal.findPage(new JournalFilter(null, null, null, null, null, 3), null);

        // A caller must not have to infer "there is more" from a full page: a page
        // can be full and final.
        assertThat(page.items()).hasSize(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void theLastPageSaysSoRatherThanReturningAnEmptyOne() {
        post(BASE, "only");

        EntryPage page = journal.findPage(new JournalFilter(null, null, null, null, null, 1), null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void filtersByDateRange() {
        post(BASE.plusDays(1), "before");
        post(BASE.plusDays(5), "inside");
        post(BASE.plusDays(9), "after");

        EntryPage page =
                journal.findPage(new JournalFilter(BASE.plusDays(3), BASE.plusDays(7), null, null, null, 50), null);

        assertThat(page.items()).extracting(PostedEntry::description).containsExactly("inside");
    }

    @Test
    void filtersByAccountAndReturnsAnEntryOnceEvenIfItTouchesTheAccountTwice() {
        UUID cash = accounts.findByCode("1000").orElseThrow().id();

        // Two lines on the same account, plus a third to balance. A join would
        // return this entry twice.
        posting.post(
                JournalEntry.of(
                        BASE,
                        "two lines on cash",
                        java.util.Currency.getInstance("EUR"),
                        EntrySource.API,
                        dev.lseg.ledger.domain.JournalLine.debit("1000", Money.of(600, "EUR")),
                        dev.lseg.ledger.domain.JournalLine.debit("1000", Money.of(400, "EUR")),
                        dev.lseg.ledger.domain.JournalLine.credit("4000", Money.of(1000, "EUR"))),
                context());
        post(BASE, "unrelated", "1100", "4000");

        EntryPage page = journal.findPage(new JournalFilter(null, null, cash, null, null, 50), null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().description()).isEqualTo("two lines on cash");
    }

    @Test
    void filtersBySourceAndExternalReference() {
        posting.post(
                JournalEntry.transfer(BASE, "a transfer", "1000", "1100", Money.of(500, "EUR"))
                        .withExternalRef("psp:abc"),
                context());
        post(BASE, "an api entry");

        assertThat(journal.findPage(new JournalFilter(null, null, null, EntrySource.TRANSFER, null, 50), null)
                        .items())
                .hasSize(1);
        assertThat(journal.findPage(new JournalFilter(null, null, null, null, "psp:abc", 50), null)
                        .items())
                .hasSize(1);
        assertThat(journal.findPage(new JournalFilter(null, null, null, null, "psp:missing", 50), null)
                        .items())
                .isEmpty();
    }

    @Test
    void theLimitIsCappedRatherThanRefused() {
        // A caller asking for 10,000 wants as many as they can have; refusing
        // outright only makes them retry with a smaller number.
        assertThat(new JournalFilter(null, null, null, null, null, 10_000).limit())
                .isEqualTo(JournalFilter.MAX_LIMIT);
        assertThat(new JournalFilter(null, null, null, null, null, 0).limit()).isEqualTo(JournalFilter.DEFAULT_LIMIT);
        assertThat(new JournalFilter(null, null, null, null, null, -5).limit()).isEqualTo(JournalFilter.DEFAULT_LIMIT);
    }

    @Test
    void aCursorRoundTripsAndAMalformedOneIsRefused() {
        EntryCursor cursor = new EntryCursor(LocalDate.of(2026, 6, 30), 4012);

        assertThat(EntryCursor.decode(cursor.encode())).isEqualTo(cursor);

        // Silently starting from the beginning would look like the list resetting
        // itself, which is worse than an error.
        assertThatThrownBy(() -> EntryCursor.decode("not-a-cursor"))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.INVALID_CURSOR);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> collectAllPages(JournalFilter filter) {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int guard = 0;

        do {
            EntryPage page = journal.findPage(filter, cursor == null ? null : EntryCursor.decode(cursor));
            seen.addAll(page.items().stream().map(PostedEntry::description).toList());
            cursor = page.nextCursor();
        } while (cursor != null && ++guard < 100);

        return seen;
    }

    private void post(LocalDate date, String description) {
        post(date, description, "1000", "4000");
    }

    private void post(LocalDate date, String description, String debit, String credit) {
        posting.post(
                JournalEntry.of(
                        date,
                        description,
                        java.util.Currency.getInstance("EUR"),
                        EntrySource.API,
                        dev.lseg.ledger.domain.JournalLine.debit(debit, Money.of(1000, "EUR")),
                        dev.lseg.ledger.domain.JournalLine.credit(credit, Money.of(1000, "EUR"))),
                context());
    }

    private PostingContext context() {
        return PostingContext.of(OPERATOR_ID, "req-" + UUID.randomUUID());
    }
}
