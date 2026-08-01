package dev.lseg.ledger.domain;

import java.util.Currency;
import java.util.UUID;

/**
 * A named bucket money is measured in.
 *
 * <p>{@code type} and {@code currency} are immutable once the account has
 * postings — changing either would retroactively reinterpret every historical
 * line that touched it. A database trigger enforces that; this record simply
 * offers no way to express the change.
 */
public record Account(
        UUID id, String code, String name, AccountType type, Currency currency, AccountStatus status, UUID parentId) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public int balanceSign() {
        return type.balanceSign();
    }
}
