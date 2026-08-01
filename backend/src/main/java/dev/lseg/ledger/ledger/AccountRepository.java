package dev.lseg.ledger.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.lseg.ledger.domain.Account;

public interface AccountRepository {

    Optional<Account> findByCode(String code);

    Optional<Account> findById(UUID id);

    /**
     * Resolves many codes in one round trip.
     *
     * <p>A three-line entry would otherwise issue three queries inside the write
     * transaction, holding it open longer than the work requires.
     */
    Map<String, Account> findByCodes(Collection<String> codes);

    List<Account> findAll();
}
