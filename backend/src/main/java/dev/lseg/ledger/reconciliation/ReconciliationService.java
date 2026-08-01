package dev.lseg.ledger.reconciliation;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.lseg.ledger.domain.Account;
import dev.lseg.ledger.domain.EntrySource;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.ledger.AccountRepository;
import dev.lseg.ledger.ledger.PostingContext;
import dev.lseg.ledger.ledger.PostingService;

/**
 * Imports a statement, matches it against the journal, and explains what differs.
 *
 * <p>It holds <strong>no privileged write path</strong> into the journal.
 * Resolving a break posts an ordinary entry through {@link PostingService}, with
 * the same validation, the same authorisation and the same audit trail as any
 * other posting. That matters more than it sounds: a reconciliation tool that can
 * nudge a balance is able to make its own report come out even, which is the
 * failure mode that makes some of them worse than useless.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repository;
    private final AccountRepository accounts;
    private final StatementCsvParser parser;
    private final MatchingPipeline pipeline;
    private final BreakClassifier classifier;
    private final PostingService posting;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    ReconciliationService(
            ReconciliationRepository repository,
            AccountRepository accounts,
            StatementCsvParser parser,
            MatchingPipeline pipeline,
            BreakClassifier classifier,
            PostingService posting,
            Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.parser = parser;
        this.pipeline = pipeline;
        this.classifier = classifier;
        this.posting = posting;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public UUID importStatement(ImportCommand command, InputStream content, UUID importedBy) {
        Account account = accounts.findByCode(command.accountCode())
                .orElseThrow(() -> new LedgerException(
                        LedgerError.UNKNOWN_ACCOUNT,
                        "no account with code %s".formatted(command.accountCode()),
                        Map.of("accountCode", command.accountCode())));

        byte[] bytes = readAll(content);
        byte[] sha256 = sha256(bytes);

        // Idempotency by natural key: the file's content is a better key than
        // anything a client would invent, so re-uploading returns what exists.
        var existing = repository.findImportIdByContent(account.id(), sha256);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<StatementLine> lines = parser.parse(new java.io.ByteArrayInputStream(bytes));
        assertInternallyConsistent(command, lines, account);

        UUID importId = repository.createImport(
                account.id(),
                account.currency().getCurrencyCode(),
                command.periodStart(),
                command.periodEnd(),
                command.openingBalanceMinor(),
                command.closingBalanceMinor(),
                command.filename(),
                sha256,
                importedBy);

        List<StatementLine> stored = new ArrayList<>(lines.size());
        for (StatementLine line : lines) {
            stored.add(new StatementLine(
                    repository.insertStatementLine(importId, line),
                    line.rowNo(),
                    line.valueDate(),
                    line.amountMinor(),
                    line.currency(),
                    line.description(),
                    line.externalId(),
                    line.counterpartyRef()));
        }

        run(importId, account, command, stored);
        return importId;
    }

    /**
     * The statement's own arithmetic is checked before anything is matched.
     *
     * <p>Reconciling against a file that does not internally add up produces
     * confident nonsense: the bridge would balance against a closing figure the
     * statement's own rows contradict.
     */
    private void assertInternallyConsistent(ImportCommand command, List<StatementLine> lines, Account account) {
        long sum = lines.stream().mapToLong(StatementLine::amountMinor).sum();
        long expected = command.openingBalanceMinor() + sum;

        if (expected != command.closingBalanceMinor()) {
            throw new LedgerException(
                    LedgerError.STATEMENT_NOT_INTERNALLY_CONSISTENT,
                    "opening %d plus %d row(s) totalling %d gives %d, but the statement declares closing %d"
                            .formatted(
                                    command.openingBalanceMinor(),
                                    lines.size(),
                                    sum,
                                    expected,
                                    command.closingBalanceMinor()),
                    Map.of(
                            "openingBalanceMinor",
                            command.openingBalanceMinor(),
                            "rowTotalMinor",
                            sum,
                            "impliedClosingMinor",
                            expected,
                            "declaredClosingMinor",
                            command.closingBalanceMinor()));
        }

        List<String> foreign = lines.stream()
                .map(StatementLine::currency)
                .filter(c -> !c.equals(account.currency().getCurrencyCode()))
                .distinct()
                .toList();
        if (!foreign.isEmpty()) {
            log.info("statement for {} contains {} foreign currency line(s)", account.code(), foreign.size());
        }
    }

    private void run(UUID importId, Account account, ImportCommand command, List<StatementLine> lines) {
        List<JournalMovement> movements =
                repository.movementsIn(account.id(), command.periodStart(), command.periodEnd());

        MatchingPipeline.Result matching = pipeline.match(lines, movements, account.type());

        for (MatchingPipeline.Pairing pairing : matching.pairings()) {
            repository.insertMatch(
                    importId,
                    pairing.statementLine().id(),
                    pairing.movement().journalLineId(),
                    pairing.rule(),
                    pairing.confidence());
        }

        List<BreakClassifier.ClassifiedBreak> classified = classifier.classify(
                matching,
                account.type(),
                account.currency().getCurrencyCode(),
                command.periodStart(),
                command.periodEnd());

        // The opening gap, if any. Closing = opening + movements on both sides, so
        // classifying only the movements leaves this unexplained and the bridge
        // cannot close however good the matching is.
        long ledgerOpening = repository.ledgerClosingSigned(
                account.id(), command.periodStart().minusDays(1));
        long statementOpening = StatementSignConvention.toLedgerSigned(command.openingBalanceMinor(), account.type());

        if (ledgerOpening != statementOpening) {
            repository.insertBreak(
                    importId,
                    BreakType.OPENING_BALANCE_MISMATCH,
                    statementOpening - ledgerOpening,
                    account.currency().getCurrencyCode(),
                    null,
                    null,
                    Map.of(
                            "ledgerOpeningMinor", ledgerOpening,
                            "statementOpeningMinor", statementOpening,
                            "asOf", command.periodStart().minusDays(1).toString()));
        }

        for (BreakClassifier.ClassifiedBreak candidate : classified) {
            repository.insertBreak(
                    importId,
                    candidate.type(),
                    candidate.deltaMinor(),
                    account.currency().getCurrencyCode(),
                    candidate.statementLine() == null
                            ? null
                            : candidate.statementLine().id(),
                    candidate.movement() == null ? null : candidate.movement().journalLineId(),
                    candidate.detail());
        }

        repository.markCompleted(importId);
    }

    @Transactional(readOnly = true)
    public ReconciliationReport report(UUID importId) {
        StatementImport statement = repository
                .findImport(importId)
                .orElseThrow(() -> new LedgerException(
                        LedgerError.RECONCILIATION_NOT_FOUND,
                        "no reconciliation %s".formatted(importId),
                        Map.of("importId", importId)));

        Account account = accounts.findById(statement.accountId()).orElseThrow();

        long ledgerClosing = repository.ledgerClosingSigned(statement.accountId(), statement.periodEnd());
        long statementClosing = StatementSignConvention.toLedgerSigned(statement.closingBalanceMinor(), account.type());

        List<ReconciliationBreak> breaks = repository.findBreaks(importId);
        Map<UUID, Map<String, Object>> details = detailsById(importId);

        List<ReconciliationReport.BridgeRow> bridge = breaks.stream()
                .map(b -> new ReconciliationReport.BridgeRow(
                        b.id(),
                        b.type(),
                        b.status(),
                        b.status().countsTowardsTheBridge() ? b.deltaMinor() : 0L,
                        describe(b, details.get(b.id())),
                        details.getOrDefault(b.id(), Map.of())))
                .toList();

        long bridgeTotal = bridge.stream()
                .mapToLong(ReconciliationReport.BridgeRow::deltaMinor)
                .sum();
        long difference = statementClosing - ledgerClosing;

        // The invariant. If it does not hold, the engine has a bug — a
        // double-consumed line, a sign error, a missed classification — and saying
        // so is worth more than presenting a plausible-looking list.
        boolean balanced = bridgeTotal == difference;
        if (!balanced) {
            log.error(
                    "bridge does not close for import {}: difference {} but breaks total {}",
                    importId,
                    difference,
                    bridgeTotal);
        }

        int matched = repository.countMatches(importId);
        int totalStatementLines = repository.countStatementLines(importId);

        return new ReconciliationReport(
                importId,
                account.code(),
                account.name(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.currency(),
                ledgerClosing,
                statementClosing,
                difference,
                matched,
                repository.matchedAmountMinor(importId),
                (int) breaks.stream().filter(b -> b.statementLineId() != null).count(),
                (int) breaks.stream().filter(b -> b.journalLineId() != null).count(),
                bridge,
                bridgeTotal,
                balanced,
                totalStatementLines == 0 ? 1.0 : (double) matched / totalStatementLines,
                clock.instant());
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public void explain(UUID breakId, String explanation) {
        ReconciliationBreak found = requireOpen(breakId);
        repository.explainBreak(found.id(), explanation);
    }

    /**
     * Closes a break by posting an adjusting entry.
     *
     * <p>Through {@link PostingService}, not around it. Resolving a duplicate is
     * therefore a reversal of the duplicated entry, which is itself visible in the
     * journal and reconcilable next period — not a deletion.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public UUID resolve(UUID breakId, ResolveCommand command, PostingContext context) {
        ReconciliationBreak found = requireOpen(breakId);
        StatementImport statement = repository.findImport(found.importId()).orElseThrow();
        Account account = accounts.findById(statement.accountId()).orElseThrow();

        // The adjusting entry moves exactly the break's delta onto the account, so
        // posting it makes that break's contribution to the bridge disappear.
        long deltaSigned = found.deltaMinor();
        if (deltaSigned == 0) {
            throw new LedgerException(
                    LedgerError.BREAK_ALREADY_CLOSED,
                    "this break has no monetary difference to post",
                    Map.of("breakId", breakId, "type", found.type().name()));
        }

        Money amount = Money.of(Math.abs(deltaSigned), account.currency());
        JournalLine onAccount = deltaSigned > 0
                ? JournalLine.debit(account.code(), amount)
                : JournalLine.credit(account.code(), amount);
        JournalLine contra = deltaSigned > 0
                ? JournalLine.credit(command.counterAccountCode(), amount)
                : JournalLine.debit(command.counterAccountCode(), amount);

        // Defaults to the period being reconciled, not to today. An operator
        // resolving a June break means "book this into June"; dating it now would
        // leave June's difference open — accounting-correct, and not what was
        // asked. An explicit effectiveDate still wins, which is how you book the
        // correction into the current period instead.
        LocalDate effectiveDate = command.effectiveDate() != null
                ? command.effectiveDate()
                : earlierOf(statement.periodEnd(), LocalDate.now(clock));

        JournalEntry adjustment = new JournalEntry(
                effectiveDate,
                "Reconciliation adjustment: " + command.explanation(),
                account.currency(),
                EntrySource.ADJUSTMENT,
                "recon:" + breakId,
                List.of(onAccount, contra));

        PostedEntry posted = posting.post(adjustment, context);

        repository.resolveBreak(
                breakId,
                posted.id(),
                context.actorId(),
                command.explanation(),
                command.writeOff() ? BreakStatus.WRITTEN_OFF : BreakStatus.RESOLVED);

        return posted.id();
    }

    /** Postdating is refused by the posting service, so a future period end cannot be used. */
    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private ReconciliationBreak requireOpen(UUID breakId) {
        ReconciliationBreak found = repository
                .findBreak(breakId)
                .orElseThrow(() -> new LedgerException(
                        LedgerError.BREAK_NOT_FOUND, "no break %s".formatted(breakId), Map.of("breakId", breakId)));

        if (found.status() == BreakStatus.RESOLVED || found.status() == BreakStatus.WRITTEN_OFF) {
            throw new LedgerException(
                    LedgerError.BREAK_ALREADY_CLOSED,
                    "break %s is already %s".formatted(breakId, found.status()),
                    Map.of("breakId", breakId, "status", found.status().name()));
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<StatementImport> list(UUID accountId) {
        return repository.findImports(accountId);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationBreak> breaks(UUID importId) {
        return repository.findBreaks(importId);
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private Map<UUID, Map<String, Object>> detailsById(UUID importId) {
        Map<UUID, Map<String, Object>> result = new java.util.HashMap<>();
        for (Map<String, Object> row : repository.findBreakDetails(importId)) {
            Object detail = row.get("detail");
            Map<String, Object> parsed = Map.of();
            if (detail != null) {
                try {
                    parsed = json.readValue(detail.toString(), Map.class);
                } catch (Exception e) {
                    // A break whose detail will not parse is still a real break;
                    // losing its description beats losing the whole report.
                    log.warn("break detail for import {} is not readable", importId, e);
                }
            }
            result.put((UUID) row.get("id"), parsed);
        }
        return result;
    }

    private static String describe(ReconciliationBreak found, Map<String, Object> detail) {
        Map<String, Object> data = detail == null ? Map.of() : detail;
        return switch (found.type()) {
            case MISSING_IN_LEDGER -> "On the statement (%s, %s) but never booked"
                    .formatted(data.get("valueDate"), data.get("description"));
            case MISSING_IN_STATEMENT -> "Booked (%s, %s) but absent from the statement"
                    .formatted(data.get("effectiveDate"), data.get("description"));
            case TIMING_DIFFERENCE -> "Booked %s, expected to clear after the period end — no action needed"
                    .formatted(data.get("effectiveDate"));
            case DUPLICATE_IN_LEDGER -> "The same movement appears twice in the journal (%s)"
                    .formatted(data.get("description"));
            case DUPLICATE_IN_STATEMENT -> "The bank reported this movement twice (%s)"
                    .formatted(data.get("description"));
            case AMOUNT_MISMATCH -> "Matched, but the amounts differ by %s minor units"
                    .formatted(data.get("differenceMinor"));
            case CURRENCY_MISMATCH -> "Statement line is in %s, which this account does not hold"
                    .formatted(data.get("statementCurrency"));
                // No raw minor units in the sentence: the delta column beside it already
                // shows the amount, formatted with the currency's own exponent. A
                // description printing 4106500 next to a column reading €41,065.00
                // reads as two different figures.
            case OPENING_BALANCE_MISMATCH -> "The journal and the statement disagree about the opening balance — the previous period is unreconciled";
        };
    }

    private static byte[] readAll(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new LedgerException(LedgerError.STATEMENT_NOT_READABLE, "the uploaded file could not be read");
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record ImportCommand(
            String accountCode,
            LocalDate periodStart,
            LocalDate periodEnd,
            long openingBalanceMinor,
            long closingBalanceMinor,
            String filename) {}

    public record ResolveCommand(
            String counterAccountCode, String explanation, LocalDate effectiveDate, boolean writeOff) {}
}
