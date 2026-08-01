package dev.lseg.ledger.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.lseg.ledger.ledger.AccountBalance;

/**
 * @param balance the natural balance — positive means the account holds what it
 *     is supposed to hold. What a report shows.
 * @param signedBalance debit-positive. What sums to zero across the ledger.
 * @param derivedAt makes the derivation visible: nothing here was cached, and the
 *     same {@code asOf} returns the same number in a year's time. See ADR-0003.
 */
public record BalanceResponse(
        UUID accountId,
        String code,
        LocalDate asOf,
        MoneyResponse balance,
        MoneyResponse signedBalance,
        MoneyResponse totalDebit,
        MoneyResponse totalCredit,
        long lineCount,
        Instant derivedAt) {

    public static BalanceResponse of(AccountBalance balance) {
        return new BalanceResponse(
                balance.accountId(),
                balance.code(),
                balance.asOf(),
                MoneyResponse.of(balance.natural()),
                MoneyResponse.of(balance.signed()),
                MoneyResponse.of(balance.totalDebit()),
                MoneyResponse.of(balance.totalCredit()),
                balance.lineCount(),
                balance.derivedAt());
    }
}
