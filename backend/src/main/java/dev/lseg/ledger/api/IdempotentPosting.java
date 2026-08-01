package dev.lseg.ledger.api;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.idempotency.IdempotencyRequest;
import dev.lseg.ledger.idempotency.IdempotencyService;
import dev.lseg.ledger.idempotency.IdempotentOutcome;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;

/**
 * The one place the idempotency layer is wired to the posting layer.
 *
 * <p>Every write endpoint funnels through here rather than repeating the
 * wiring, which is what makes "an idempotency key is required on every write" a
 * property of the code rather than of everyone's memory.
 */
@Component
class IdempotentPosting {

    private final IdempotencyService idempotency;
    private final PostingService posting;
    private final CurrentPrincipal principal;

    IdempotentPosting(IdempotencyService idempotency, PostingService posting, CurrentPrincipal principal) {
        this.idempotency = idempotency;
        this.posting = posting;
        this.principal = principal;
    }

    IdempotentOutcome<PostedEntry> post(
            String key, String endpoint, Object body, String requestId, JournalEntry entry) {
        return execute(key, endpoint, body, requestId, (context) -> posting.post(entry, context));
    }

    IdempotentOutcome<PostedEntry> reverse(
            String key, String endpoint, Object body, String requestId, UUID entryId, String reason, LocalDate on) {
        return execute(key, endpoint, body, requestId, (context) -> posting.reverse(entryId, reason, on, context));
    }

    private IdempotentOutcome<PostedEntry> execute(
            String key, String endpoint, Object body, String requestId, Function<PostingContext, PostedEntry> action) {

        UUID actor = principal.id();
        IdempotencyRequest request = new IdempotencyRequest(key, endpoint, actor, body);

        // The journal stores the *scoped* key, matching how the idempotency store
        // identifies a request. See docs/04-idempotency.md §4.4.
        PostingContext context = new PostingContext(actor, requestId, request.scopedKey());

        return idempotency.execute(request, PostedEntry.class, () -> action.apply(context), PostedEntry::id);
    }
}
