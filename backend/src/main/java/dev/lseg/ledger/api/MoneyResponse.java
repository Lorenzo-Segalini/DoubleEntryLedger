package dev.lseg.ledger.api;

import dev.lseg.ledger.domain.Money;

/**
 * Money on the wire.
 *
 * <p>{@code amountMinor} is the integer count of minor units and is the only
 * field the server ever reads. {@code amount} is a formatted decimal
 * <em>string</em>, present so a response is readable, ignored on input.
 *
 * <p>Never a bare JSON number for the decimal value: a {@code double} that has
 * been through a JavaScript client has already lost the argument. See ADR-0002.
 */
public record MoneyResponse(long amountMinor, String currency, String amount) {

    public static MoneyResponse of(Money money) {
        return new MoneyResponse(money.amountMinor(), money.currency().getCurrencyCode(), money.toDecimalString());
    }
}
