package dev.lseg.ledger.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.lseg.ledger.domain.Direction;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.idempotency.IdempotentOutcome;
import dev.lseg.ledger.ledger.EntryCursor;
import dev.lseg.ledger.ledger.EntryPage;
import dev.lseg.ledger.ledger.JournalFilter;
import dev.lseg.ledger.ledger.JournalRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/journal-entries")
@Tag(name = "Journal", description = "Posting, reading and reversing entries")
class JournalEntryController {

    static final String POST_ENDPOINT = "POST /api/v1/journal-entries";
    static final String REVERSAL_ENDPOINT = "POST /api/v1/journal-entries/{id}/reversal";

    private final IdempotentPosting posting;
    private final JournalRepository journal;

    JournalEntryController(IdempotentPosting posting, JournalRepository journal) {
        this.posting = posting;
        this.journal = journal;
    }

    @PostMapping
    @Operation(summary = "Post a journal entry", description = "Requires an Idempotency-Key; safe to retry.")
    ResponseEntity<EntryResponse> post(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PostEntryRequest request,
            HttpServletRequest http) {

        JournalEntry entry = toDomain(request);
        var outcome = posting.post(idempotencyKey, POST_ENDPOINT, request, requestId(http), entry);
        return respond(outcome);
    }

    @PostMapping("/{id}/reversal")
    @Operation(
            summary = "Reverse an entry",
            description = "The server derives the mirrored lines from the original; the body cannot supply them.")
    ResponseEntity<EntryResponse> reverse(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReversalRequest request,
            HttpServletRequest http) {

        var outcome = posting.reverse(
                idempotencyKey,
                REVERSAL_ENDPOINT,
                Map.of("entryId", id.toString(), "reason", request.reason()),
                requestId(http),
                id,
                request.reason(),
                request.effectiveDate());
        return respond(outcome);
    }

    @GetMapping
    @Operation(
            summary = "Browse the journal, most recent first",
            description = "Cursor-paginated. Offset pagination over an append-only ledger repeats or skips rows as "
                    + "entries arrive mid-read, so it is not offered.")
    PageResponse<EntryResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) EntrySource source,
            @RequestParam(required = false) String externalRef,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {

        EntryPage page = journal.findPage(
                new JournalFilter(from, to, accountId, source, externalRef, limit),
                cursor == null || cursor.isBlank() ? null : EntryCursor.decode(cursor));

        return new PageResponse<>(
                page.items().stream().map(EntryResponse::of).toList(), page.nextCursor(), page.hasMore());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one entry")
    EntryResponse byId(@PathVariable UUID id) {
        return journal.findById(id)
                .map(EntryResponse::of)
                .orElseThrow(() -> new LedgerException(
                        LedgerError.ENTRY_NOT_FOUND, "no entry %s".formatted(id), Map.of("entryId", id)));
    }

    /**
     * {@code 201} when the work ran, {@code 200} when the original outcome was
     * returned again. The body is identical either way — that is the guarantee —
     * so the header is how a client tells the two apart.
     */
    private static ResponseEntity<EntryResponse> respond(IdempotentOutcome<PostedEntry> outcome) {
        EntryResponse body = EntryResponse.of(outcome.result());

        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotency-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/journal-entries/" + body.id()))
                .header("Idempotency-Replayed", "false")
                .body(body);
    }

    private static JournalEntry toDomain(PostEntryRequest request) {
        Currency currency = Currency.getInstance(request.currency());
        List<JournalLine> lines = request.lines().stream()
                .map(line -> new JournalLine(
                        line.accountCode(),
                        Direction.valueOf(line.direction()),
                        Money.of(line.amountMinor(), currency),
                        line.memo()))
                .toList();

        return new JournalEntry(
                request.effectiveDate(),
                request.description(),
                currency,
                EntrySource.API,
                request.externalRef(),
                lines);
    }

    private static String requestId(HttpServletRequest http) {
        Object attribute = http.getAttribute(RequestIdFilter.ATTRIBUTE);
        return attribute == null ? UUID.randomUUID().toString() : attribute.toString();
    }
}
