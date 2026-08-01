package dev.lseg.ledger.ledger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.lseg.ledger.domain.Account;
import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.PostedEntry;

/**
 * Append-only access to the journal.
 *
 * <p>There is no {@code update} and no {@code delete}, and an ArchUnit rule
 * fails the build if one is added. The application's database role does not hold
 * those privileges either — see ADR-0001.
 */
public interface JournalRepository {

    PostedEntry insert(
            JournalEntry entry,
            Map<String, Account> accountsByCode,
            PostingContext context,
            UUID reversalOf,
            String reversalReason);

    Optional<PostedEntry> findById(UUID id);

    Optional<PostedEntry> findByExternalRef(String externalRef);

    Optional<UUID> findReversalOf(UUID entryId);
}
