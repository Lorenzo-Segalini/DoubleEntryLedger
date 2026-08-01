package dev.lseg.ledger.api;

import java.util.UUID;

import dev.lseg.ledger.domain.Account;

public record AccountResponse(
        UUID id, String code, String name, String type, String currency, String status, UUID parentId) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(
                account.id(),
                account.code(),
                account.name(),
                account.type().name(),
                account.currency().getCurrencyCode(),
                account.status().name(),
                account.parentId());
    }
}
