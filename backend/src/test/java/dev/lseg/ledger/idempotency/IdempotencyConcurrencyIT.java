package dev.lseg.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;
import dev.lseg.ledger.support.PostgresIT;

/**
 * The test this whole layer exists for.
 *
 * <p>A retry does not politely wait for the original to finish. A client timeout
 * fires <em>while</em> the first request is still running, so duplicates arrive
 * concurrently — that is the common case, not the rare one. An implementation
 * that reads before it writes passes every sequential test and fails here.
 *
 * <p>See docs/04-idempotency.md §4.9.
 */
class IdempotencyConcurrencyIT extends PostgresIT {

    private static final int THREADS = 32;
    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    PostingService posting;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        truncateJournal();
        jdbc.sql("DELETE FROM idempotency_record").update();

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "operator@demo.local", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));

        // A SecurityContext lives in a ThreadLocal, so worker threads start
        // anonymous and every posting would be denied. Wrapping the pool
        // propagates it — the same thing production has to do for any async work,
        // which is why this is a wrapper rather than a global inheritable mode.
        executor = new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(THREADS));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void thirtyTwoConcurrentRetriesPostExactlyOneEntry() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = transferRequest(key);

        // A CyclicBarrier, not just thirty-two submit() calls. Without it the
        // requests spread over milliseconds and a check-then-insert
        // implementation passes by luck. The barrier forces genuine contention on
        // the primary key, which is the only thing this test is about.
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        List<Future<IdempotentOutcome<PostedEntry>>> futures = IntStream.range(0, THREADS)
                .mapToObj(i -> executor.submit((Callable<IdempotentOutcome<PostedEntry>>) () -> {
                    startTogether.await();
                    return post(request);
                }))
                .toList();

        List<IdempotentOutcome<PostedEntry>> outcomes = new java.util.ArrayList<>();
        for (Future<IdempotentOutcome<PostedEntry>> future : futures) {
            outcomes.add(future.get());
        }

        assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
        assertThat(outcomes).filteredOn(IdempotentOutcome::replayed).hasSize(THREADS - 1);

        // Every caller was told about the same entry.
        assertThat(outcomes)
                .extracting(o -> o.result().id())
                .containsOnly(outcomes.getFirst().result().id());

        // And the money moved exactly once.
        assertThat(count("journal_entry")).isEqualTo(1);
        assertThat(count("journal_line")).isEqualTo(2);
    }

    @Test
    void concurrentRetriesReplayTheOriginalResponseNotARecomputedOne() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = transferRequest(key);
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        List<Future<IdempotentOutcome<PostedEntry>>> futures = IntStream.range(0, THREADS)
                .mapToObj(i -> executor.submit((Callable<IdempotentOutcome<PostedEntry>>) () -> {
                    startTogether.await();
                    return post(request);
                }))
                .toList();

        PostedEntry applied = null;
        List<PostedEntry> replays = new java.util.ArrayList<>();
        for (Future<IdempotentOutcome<PostedEntry>> future : futures) {
            IdempotentOutcome<PostedEntry> outcome = future.get();
            if (outcome.replayed()) {
                replays.add(outcome.result());
            } else {
                applied = outcome.result();
            }
        }

        assertThat(applied).isNotNull();
        final PostedEntry original = applied;

        // Not merely the same id: the same sequence number, the same posting
        // instant, the same lines. A replay returns what was stored, not a fresh
        // read that might have drifted.
        assertThat(replays).allSatisfy(replay -> {
            assertThat(replay.id()).isEqualTo(original.id());
            assertThat(replay.sequenceNo()).isEqualTo(original.sequenceNo());
            assertThat(replay.postedAt()).isEqualTo(original.postedAt());
            assertThat(replay.lines()).hasSameSizeAs(original.lines());
        });
    }

    @Test
    void concurrentRequestsWithDifferentKeysAllPost() throws Exception {
        // The complement of the test above: idempotency must not serialise
        // unrelated traffic into a single posting.
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        List<Future<IdempotentOutcome<PostedEntry>>> futures = IntStream.range(0, THREADS)
                .mapToObj(i -> executor.submit((Callable<IdempotentOutcome<PostedEntry>>) () -> {
                    startTogether.await();
                    return post(transferRequest(UUID.randomUUID().toString()));
                }))
                .toList();

        for (Future<IdempotentOutcome<PostedEntry>> future : futures) {
            assertThat(future.get().replayed()).isFalse();
        }

        assertThat(count("journal_entry")).isEqualTo(THREADS);
    }

    /**
     * Run repeatedly, because a concurrency test that passed once has
     * demonstrated very little.
     */
    @RepeatedTest(5)
    void theGuaranteeHoldsUnderRepetition() throws Exception {
        truncateJournal();
        jdbc.sql("DELETE FROM idempotency_record").update();

        String key = UUID.randomUUID().toString();
        var request = transferRequest(key);
        CyclicBarrier startTogether = new CyclicBarrier(THREADS);

        List<Future<IdempotentOutcome<PostedEntry>>> futures = IntStream.range(0, THREADS)
                .mapToObj(i -> executor.submit((Callable<IdempotentOutcome<PostedEntry>>) () -> {
                    startTogether.await();
                    return post(request);
                }))
                .toList();

        for (Future<IdempotentOutcome<PostedEntry>> future : futures) {
            future.get();
        }

        assertThat(count("journal_entry")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private record TransferRequest(String key, String from, String to, long amountMinor, String currency) {}

    private TransferRequest transferRequest(String key) {
        return new TransferRequest(key, "1000", "1100", 250_000, "EUR");
    }

    private IdempotentOutcome<PostedEntry> post(TransferRequest request) {
        var idempotencyRequest = new IdempotencyRequest(request.key(), "POST /api/v1/transfers", SYSTEM_USER, request);

        return idempotency.execute(
                idempotencyRequest,
                PostedEntry.class,
                () -> posting.post(
                        JournalEntry.transfer(
                                TODAY,
                                "concurrent transfer",
                                request.from(),
                                request.to(),
                                Money.of(request.amountMinor(), request.currency())),
                        new PostingContext(SYSTEM_USER, "req-" + UUID.randomUUID(), idempotencyRequest.scopedKey())),
                PostedEntry::id);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
