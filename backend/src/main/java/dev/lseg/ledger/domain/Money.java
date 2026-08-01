package dev.lseg.ledger.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An exact monetary amount: an integer count of a currency's smallest unit,
 * never separated from the currency it is denominated in.
 *
 * <p>No {@code double}, and no bare {@code BigDecimal}. Addition and subtraction
 * — which is all a ledger does — are integer operations with no rounding
 * decisions at all, and the value survives the trip through JSON into a
 * JavaScript client intact. See ADR-0002.
 *
 * <p>Arithmetic uses {@link Math#addExact}: an overflow is a loud failure rather
 * than a balance that silently wraps negative.
 */
public record Money(long amountMinor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public Money abs() {
        return amountMinor < 0 ? negated() : this;
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    public boolean isPositive() {
        return amountMinor > 0L;
    }

    public boolean isNegative() {
        return amountMinor < 0L;
    }

    public boolean hasSameCurrencyAs(Money other) {
        return currency.equals(other.currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /**
     * Decimal rendering for logs and messages only.
     *
     * <p>This is the single place an amount becomes fractional. Nothing computes
     * with the result. The exponent comes from the currency, so JPY (0) and TND
     * (3) render correctly without special-casing.
     */
    public String toDecimalString() {
        int exponent = Math.max(currency.getDefaultFractionDigits(), 0);
        if (exponent == 0) {
            return Long.toString(amountMinor);
        }
        long scale = (long) Math.pow(10, exponent);
        long units = amountMinor / scale;
        long fraction = Math.abs(amountMinor % scale);
        // -0.05 divides to units 0, which would print as "0.05" without this.
        String sign = amountMinor < 0 && units == 0 ? "-" : "";
        return sign + units + "." + String.format("%0" + exponent + "d", fraction);
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new LedgerException(
                    LedgerError.MIXED_CURRENCY_ENTRY,
                    "cannot combine %s and %s".formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
