package dev.lseg.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;

import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;
import dev.lseg.ledger.support.PostgresIT;

@WithMockUser(username = "operator@demo.local", roles = "OPERATOR")
class IdempotencyServiceIT extends PostgresIT {

    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final String ENDPOINT = "POST /api/v1/transfers";

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    PostingService posting;

    @Autowired
    IdempotencyRepository records;

    @BeforeEach
    void setUp() {
        truncateJournal();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql(
                        """
                        INSERT INTO app_user (id, email, display_name, password_hash, role, enabled)
                        VALUES (:id, 'other@demo.local', 'Other', '!no-login', 'OPERATOR', FALSE)
                        ON CONFLICT (email) DO NOTHING
                        """)
                .param("id", OTHER_USER)
                .update();
    }

    @Test
    void aRetryReplaysTheOriginalOutcomeAndPostsNothing() {
        String key = UUID.randomUUID().toString();

        var first = post(key, SYSTEM_USER, 250_000);
        var second = post(key, SYSTEM_USER, 250_000);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.result().id()).isEqualTo(first.result().id());
        assertThat(second.result().sequenceNo()).isEqualTo(first.result().sequenceNo());
        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void aReplayIsTheStoredResponseNotAFreshRead() {
        String key = UUID.randomUUID().toString();
        var first = post(key, SYSTEM_USER, 250_000);

        var replay = (IdempotentOutcome.Replayed<PostedEntry>) post(key, SYSTEM_USER, 250_000);

        assertThat(replay.originalStatus()).isEqualTo(201);
        assertThat(replay.originallyCompletedAt()).isNotNull();
        assertThat(replay.result().postedAt()).isEqualTo(first.result().postedAt());
        assertThat(replay.result().lines()).hasSameSizeAs(first.result().lines());
    }

    @Test
    void reusingAKeyForADifferentBodyIsRejectedAndPostsNothing() {
        String key = UUID.randomUUID().toString();
        post(key, SYSTEM_USER, 250_000);

        // The worst outcome available would be applying this quietly: the caller
        // would believe both succeeded when exactly one did.
        assertThatThrownBy(() -> post(key, SYSTEM_USER, 999_999))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void theSameKeyFromADifferentPrincipalIsADifferentRequest() {
        String key = UUID.randomUUID().toString();

        var mine = post(key, SYSTEM_USER, 250_000);
        var theirs = post(key, OTHER_USER, 250_000);

        assertThat(mine.replayed()).isFalse();
        assertThat(theirs.replayed()).isFalse();
        assertThat(theirs.result().id()).isNotEqualTo(mine.result().id());
        assertThat(count("journal_entry")).isEqualTo(2);
    }

    @Test
    void theSameKeyOnADifferentEndpointIsADifferentRequest() {
        String key = UUID.randomUUID().toString();
        post(key, SYSTEM_USER, 250_000);

        var otherEndpoint = new IdempotencyRequest(key, "POST /api/v1/journal-entries", SYSTEM_USER, body(250_000));
        var elsewhere = idempotency.execute(
                otherEndpoint,
                PostedEntry.class,
                () -> posting.post(
                        JournalEntry.transfer(TODAY, "other endpoint", "1000", "1100", Money.of(250_000, "EUR")),
                        new PostingContext(SYSTEM_USER, "req-" + UUID.randomUUID(), otherEndpoint.scopedKey())),
                PostedEntry::id);

        assertThat(elsewhere.replayed()).isFalse();
        assertThat(count("journal_entry")).isEqualTo(2);
    }

    @Test
    void aFailedActionLeavesNoClaimSoTheRetrySucceeds() {
        String key = UUID.randomUUID().toString();

        // The ledger write throws; the claim must roll back with it. Otherwise the
        // key would be spent on a posting that never happened and no retry could
        // ever go through.
        var failing = new IdempotencyRequest(key, ENDPOINT, SYSTEM_USER, body(250_000));
        assertThatThrownBy(() -> idempotency.execute(
                        failing,
                        PostedEntry.class,
                        () -> posting.post(
                                JournalEntry.transfer(TODAY, "nowhere", "1000", "9999", Money.of(250_000, "EUR")),
                                new PostingContext(SYSTEM_USER, "req-x", failing.scopedKey())),
                        PostedEntry::id))
                .isInstanceOf(LedgerException.class);

        assertThat(records.find(key, ENDPOINT, SYSTEM_USER)).isEmpty();
        assertThat(count("journal_entry")).isZero();

        var retry = post(key, SYSTEM_USER, 250_000);
        assertThat(retry.replayed()).isFalse();
        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void theStoredRecordLinksBackToTheEntryItProduced() {
        String key = UUID.randomUUID().toString();
        var posted = post(key, SYSTEM_USER, 250_000);

        var record = records.find(key, ENDPOINT, SYSTEM_USER).orElseThrow();

        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.entryId()).isEqualTo(posted.result().id());
        assertThat(record.responseStatus()).isEqualTo(201);
        assertThat(record.expiresAt()).isAfter(record.createdAt());
    }

    @Test
    void aRetryAfterExpiryIsStoppedByTheJournalsOwnUniqueIndex() {
        String key = UUID.randomUUID().toString();
        post(key, SYSTEM_USER, 250_000);

        // Age the record past its TTL and sweep it, exactly as the scheduled job
        // would. The replay path is now gone.
        jdbc.sql(
                        """
                        UPDATE idempotency_record
                           SET created_at = now() - interval '25 hours',
                               expires_at = now() - interval '1 hour'
                        """)
                .update();
        assertThat(records.deleteExpired()).isEqualTo(1);

        // The second line of defence: journal_entry.idempotency_key is uniquely
        // indexed, so the duplicate is refused by the journal itself. The caller
        // gets a worse error message — never a duplicate posting.
        assertThatThrownBy(() -> post(key, SYSTEM_USER, 250_000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("entry_idempotency_key_idx");

        assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void aBlankKeyIsRefusedOutright() {
        assertThatThrownBy(() -> new IdempotencyRequest("  ", ENDPOINT, SYSTEM_USER, body(1)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void sweepingLeavesLiveRecordsAlone() {
        post(UUID.randomUUID().toString(), SYSTEM_USER, 250_000);

        assertThat(records.deleteExpired()).isZero();
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private record TransferBody(String from, String to, long amountMinor, String currency) {}

    private static TransferBody body(long amountMinor) {
        return new TransferBody("1000", "1100", amountMinor, "EUR");
    }

    private IdempotentOutcome<PostedEntry> post(String key, UUID principal, long amountMinor) {
        var request = new IdempotencyRequest(key, ENDPOINT, principal, body(amountMinor));
        return idempotency.execute(
                request,
                PostedEntry.class,
                () -> posting.post(
                        JournalEntry.transfer(TODAY, "transfer", "1000", "1100", Money.of(amountMinor, "EUR")),
                        new PostingContext(principal, "req-" + UUID.randomUUID(), request.scopedKey())),
                PostedEntry::id);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
