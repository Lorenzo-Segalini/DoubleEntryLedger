package dev.lseg.ledger.api;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.reconciliation.ReconciliationBreak;
import dev.lseg.ledger.reconciliation.ReconciliationReport;
import dev.lseg.ledger.reconciliation.ReconciliationService;
import dev.lseg.ledger.reconciliation.StatementImport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/reconciliations")
@Tag(name = "Reconciliation", description = "Statement import, matching and typed breaks")
class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final CurrentPrincipal principal;

    ReconciliationController(ReconciliationService reconciliation, CurrentPrincipal principal) {
        this.reconciliation = reconciliation;
        this.principal = principal;
    }

    /**
     * No {@code Idempotency-Key} here, and deliberately so: the file's SHA-256 is
     * a better natural key than anything a client would invent, so re-uploading
     * the same statement returns the existing import rather than creating a
     * second one.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a bank statement and reconcile it")
    ResponseEntity<ReconciliationReport> importStatement(
            @RequestParam("file") MultipartFile file,
            @RequestParam String accountCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam long openingBalanceMinor,
            @RequestParam long closingBalanceMinor)
            throws IOException {

        UUID importId = reconciliation.importStatement(
                new ReconciliationService.ImportCommand(
                        accountCode,
                        periodStart,
                        periodEnd,
                        openingBalanceMinor,
                        closingBalanceMinor,
                        file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename()),
                file.getInputStream(),
                principal.id());

        return ResponseEntity.created(URI.create("/api/v1/reconciliations/" + importId))
                .body(reconciliation.report(importId));
    }

    @GetMapping
    @Operation(summary = "List statement imports")
    List<StatementImport> list(@RequestParam(required = false) UUID accountId) {
        return reconciliation.list(accountId);
    }

    @GetMapping("/{id}/report")
    @Operation(
            summary = "The reconciliation bridge",
            description = "difference equals bridgeTotalMinor, or bridgeBalanced is false and the engine has a bug.")
    ReconciliationReport report(@PathVariable UUID id) {
        return reconciliation.report(id);
    }

    @GetMapping("/{id}/breaks")
    @Operation(summary = "Typed breaks for one import")
    List<ReconciliationBreak> breaks(@PathVariable UUID id) {
        return reconciliation.breaks(id);
    }

    @PostMapping("/{id}/breaks/{breakId}/explain")
    @Operation(summary = "Record why a break exists", description = "Moves no money. Correct for a timing difference.")
    ResponseEntity<Void> explain(
            @PathVariable UUID id, @PathVariable UUID breakId, @Valid @RequestBody ExplainRequest request) {

        reconciliation.explain(breakId, request.explanation());
        return ResponseEntity.noContent().build();
    }

    /**
     * Posts an adjusting entry through the ordinary posting service, so it needs
     * an idempotency key like any other money-moving operation.
     */
    @PostMapping("/{id}/breaks/{breakId}/resolve")
    @Operation(summary = "Close a break by posting an adjusting entry")
    ResponseEntity<ResolveResponse> resolve(
            @PathVariable UUID id,
            @PathVariable UUID breakId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ResolveRequest request,
            HttpServletRequest http) {

        Object attribute = http.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute == null ? UUID.randomUUID().toString() : attribute.toString();

        UUID entryId = reconciliation.resolve(
                breakId,
                new ReconciliationService.ResolveCommand(
                        request.counterAccountCode(),
                        request.explanation(),
                        request.effectiveDate(),
                        Boolean.TRUE.equals(request.writeOff())),
                new PostingContext(principal.id(), requestId, "recon|" + breakId + "|" + idempotencyKey));

        return ResponseEntity.ok(new ResolveResponse(breakId, entryId));
    }

    record ExplainRequest(@NotBlank @Size(max = 1000) String explanation) {}

    record ResolveRequest(
            @NotBlank String counterAccountCode,
            @NotBlank @Size(max = 1000) String explanation,
            LocalDate effectiveDate,
            Boolean writeOff) {}

    record ResolveResponse(@NotNull UUID breakId, @NotNull UUID adjustingEntryId) {}
}
