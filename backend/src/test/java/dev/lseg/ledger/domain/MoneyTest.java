package dev.lseg.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void additionIsExactWhereFloatingPointIsNot() {
        // 0.1 + 0.2 != 0.3 in binary floating point. In minor units it is 10 + 20.
        Money sum = Money.of(10, EUR).plus(Money.of(20, EUR));

        assertThat(sum.amountMinor()).isEqualTo(30);
    }

    @Test
    void summingAThousandCentsAccumulatesNoError() {
        Money total = IntStream.range(0, 1_000).mapToObj(i -> Money.of(1, EUR)).reduce(Money.zero(EUR), Money::plus);

        assertThat(total.amountMinor()).isEqualTo(1_000);
    }

    @Test
    void refusesToCombineDifferentCurrencies() {
        assertThatThrownBy(() -> Money.of(100, EUR).plus(Money.of(100, JPY)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).error())
                .isEqualTo(LedgerError.MIXED_CURRENCY_ENTRY);
    }

    @Test
    void overflowThrowsRatherThanWrappingIntoANegativeBalance() {
        // The failure mode this guards: Long.MAX_VALUE + 1 silently becomes
        // Long.MIN_VALUE, turning the largest possible balance into the smallest.
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, EUR).plus(Money.of(1, EUR)))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, EUR).minus(Money.of(1, EUR)))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, EUR).negated()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparisonRequiresTheSameCurrency() {
        assertThatThrownBy(() -> Money.of(1, EUR).compareTo(Money.of(1, JPY))).isInstanceOf(LedgerException.class);

        assertThat(Money.of(100, EUR)).isGreaterThan(Money.of(99, EUR));
    }

    @Test
    void equalityIsValueEquality() {
        // The BigDecimal trap: new BigDecimal("1.50") is not equal to
        // new BigDecimal("1.5"). Integers have one representation.
        assertThat(Money.of(150, EUR)).isEqualTo(Money.of(150, EUR)).hasSameHashCodeAs(Money.of(150, EUR));
    }

    @ParameterizedTest
    @CsvSource({
        "12500, EUR, 125.00",
        "1, EUR, 0.01",
        "0, EUR, 0.00",
        "-4250, EUR, -42.50",
        "-5, EUR, -0.05",
        "1000, JPY, 1000",
        "1234, TND, 1.234"
    })
    void rendersUsingTheCurrencyExponent(long minor, String code, String expected) {
        // JPY has 0 decimals, TND has 3. The exponent comes from the currency, so
        // no code outside this method needs to know it.
        assertThat(Money.of(minor, code).toDecimalString()).isEqualTo(expected);
    }

    @Test
    void nullCurrencyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
    }
}
