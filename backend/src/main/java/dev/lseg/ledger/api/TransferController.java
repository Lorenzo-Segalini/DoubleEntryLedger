package dev.lseg.ledger.api;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "The two-line convenience over the journal")
class TransferController {

    static final String ENDPOINT = "POST /api/v1/transfers";

    private final IdempotentPosting posting;

    TransferController(IdempotentPosting posting) {
        this.posting = posting;
    }

    @PostMapping
    @Operation(
            summary = "Move money between two accounts",
            description = "Expands to an ordinary two-line entry and runs through the identical validation.")
    ResponseEntity<EntryResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest http) {

        JournalEntry entry = JournalEntry.transfer(
                        request.effectiveDate(),
                        request.description(),
                        request.fromAccountCode(),
                        request.toAccountCode(),
                        Money.of(request.amountMinor(), request.currency()))
                .withExternalRef(request.externalRef());

        Object attribute = http.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute == null ? UUID.randomUUID().toString() : attribute.toString();

        var outcome = posting.post(idempotencyKey, ENDPOINT, request, requestId, entry);
        EntryResponse body = EntryResponse.of(outcome.result());

        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotency-Replayed", "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/journal-entries/" + body.id()))
                .header("Idempotency-Replayed", "false")
                .body(body);
    }
}
