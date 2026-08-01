package dev.lseg.ledger.ledger;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.lseg.ledger.domain.Account;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.JournalLine;
import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.domain.PostedEntry;

/**
 * The one way money enters the journal.
 *
 * <p>Every caller goes through here: the API, statement imports, reconciliation
 * break resolutions, the demo seeder. Reconciliation in particular gets no
 * privileged path — it cannot nudge a balance to make its own report come out
 * even, which is the failure mode that makes some reconciliation tools worse
 * than useless.
 *
 * <p>What this class adds over {@link JournalEntry}'s own validation is
 * everything that needs to look outside the entry: whether the accounts exist,
 * whether they are open, whether their currencies agree with the lines, and what
 * today's date is.
 *
 * <p>Authorisation is enforced <em>here</em>, not only on the controllers. A
 * controller added later without an annotation still hits a guarded service, and
 * AUDITOR is refused at the layer that actually writes rather than at the one
 * that happens to be in front of it today. See ADR-0007.
 */
@Service
public class PostingService {

    private final JournalRepository journal;
    private final AccountRepository accounts;
    private final Clock clock;

    public PostingService(JournalRepository journal, AccountRepository accounts, Clock clock) {
        this.journal = journal;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Posts an entry.
     *
     * <p>The entry is already balanced — {@link JournalEntry} refuses to exist
     * otherwise — so this resolves accounts, checks the date, and writes.
     *
     * <p>Note where the balance check ultimately lands: the deferred constraint
     * trigger fires at COMMIT, which is after this method returns. A defect that
     * slipped past the domain would surface as a transaction failure, not as a
     * bad row. That is the intended order of defences, not an oversight.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public PostedEntry post(JournalEntry entry, PostingContext context) {
        Map<String, Account> resolved = resolveAccounts(entry);
        rejectIfPostdated(entry.effectiveDate());
        return journal.insert(entry, resolved, context, null, null);
    }

    /**
     * Cancels an entry by posting its mirror.
     *
     * <p>The caller supplies an id and a reason, never lines. The reversal is
     * derived from the original, which is what makes invariant I9 hold: it cannot
     * be a partial cancellation of the entry it claims to reverse.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Transactional
    public PostedEntry reverse(UUID entryId, String reason, LocalDate reversalDate, PostingContext context) {
        if (reason == null || reason.isBlank()) {
            throw new LedgerException(
                    LedgerError.BLANK_DESCRIPTION, "a reversal must state why", Map.of("entryId", entryId));
        }

        PostedEntry original = journal.findById(entryId)
                .orElseThrow(() -> new LedgerException(
                        LedgerError.ENTRY_NOT_FOUND, "no entry %s".formatted(entryId), Map.of("entryId", entryId)));

        // Reversing a reversal is refused rather than chained. The intent is
        // almost always a fresh correcting entry, and allowing chains makes the
        // audit view ambiguous about which posting is currently in force.
        if (original.isReversal()) {
            throw new LedgerException(
                    LedgerError.REVERSAL_OF_REVERSAL,
                    "entry %d is itself a reversal; post a correcting entry instead".formatted(original.sequenceNo()),
                    Map.of("entryId", entryId));
        }

        // Advisory only. The authority is the partial unique index on
        // reversal_of_entry_id, which holds under concurrency where this read
        // cannot. Checking here buys a clean 409 in the common case.
        journal.findReversalOf(entryId).ifPresent(existing -> {
            throw new LedgerException(
                    LedgerError.ALREADY_REVERSED,
                    "entry %d was already reversed by %s".formatted(original.sequenceNo(), existing),
                    Map.of("entryId", entryId, "reversalEntryId", existing));
        });

        LocalDate effective = reversalDate == null ? LocalDate.now(clock) : reversalDate;
        JournalEntry reversal = original.reversal(effective, reason);

        Map<String, Account> resolved = resolveAccounts(reversal);
        rejectIfPostdated(effective);

        return journal.insert(reversal, resolved, context, original.id(), reason);
    }

    private Map<String, Account> resolveAccounts(JournalEntry entry) {
        List<String> codes = entry.accountCodes();
        Map<String, Account> found = accounts.findByCodes(codes);

        for (JournalLine line : entry.lines()) {
            Account account = found.get(line.accountCode());

            if (account == null) {
                throw new LedgerException(
                        LedgerError.UNKNOWN_ACCOUNT,
                        "no account with code %s".formatted(line.accountCode()),
                        Map.of("accountCode", line.accountCode()));
            }
            if (!account.isActive()) {
                throw new LedgerException(
                        LedgerError.ACCOUNT_ARCHIVED,
                        "account %s is archived and cannot be posted to".formatted(account.code()),
                        Map.of("accountCode", account.code()));
            }
            // Invariant I5. Also a composite foreign key in the schema; this
            // exists so the caller is told which line is wrong.
            if (!account.currency().equals(line.amount().currency())) {
                throw new LedgerException(
                        LedgerError.CURRENCY_MISMATCH,
                        "account %s is in %s but the line is in %s"
                                .formatted(
                                        account.code(),
                                        account.currency().getCurrencyCode(),
                                        line.amount().currency().getCurrencyCode()),
                        Map.of(
                                "accountCode", account.code(),
                                "accountCurrency", account.currency().getCurrencyCode(),
                                "lineCurrency", line.amount().currency().getCurrencyCode()));
            }
        }
        return found;
    }

    /**
     * Backdating is allowed and audited — a payment learned about late is
     * ordinary. Postdating is not: an entry cannot be effective in the future.
     */
    private void rejectIfPostdated(LocalDate effectiveDate) {
        LocalDate today = LocalDate.now(clock);
        if (effectiveDate.isAfter(today)) {
            throw new LedgerException(
                    LedgerError.POSTDATED_ENTRY,
                    "effective date %s is in the future (today is %s)".formatted(effectiveDate, today),
                    Map.of("effectiveDate", effectiveDate.toString(), "today", today.toString()));
        }
    }
}
