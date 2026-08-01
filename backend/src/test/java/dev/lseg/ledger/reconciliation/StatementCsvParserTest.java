package dev.lseg.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.lseg.ledger.domain.LedgerException;

class StatementCsvParserTest {

    private final StatementCsvParser parser = new StatementCsvParser();

    private List<StatementLine> parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTheNormalCase() {
        List<StatementLine> lines = parse(
                """
                value_date,amount,currency,description,external_id,counterparty_ref
                2026-06-03,-250.00,EUR,SEPA CT ACME SRL,TX-4471,IT60X0542811101
                2026-06-04,1000.00,EUR,CARD SETTLEMENT,TX-4472,PSP-3Nk8Qz
                """);

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst().valueDate()).isEqualTo(LocalDate.of(2026, 6, 3));
        // Signed from the bank's perspective: money leaving is negative.
        assertThat(lines.getFirst().amountMinor()).isEqualTo(-25_000);
        assertThat(lines.getFirst().externalId()).isEqualTo("TX-4471");
        assertThat(lines.get(1).amountMinor()).isEqualTo(100_000);
    }

    @Test
    void readsColumnsByNameRatherThanPosition() {
        // Every bank exports the same fields in a different order.
        List<StatementLine> lines = parse(
                """
                description,currency,amount,value_date
                CARD SETTLEMENT,EUR,1000.00,2026-06-04
                """);

        assertThat(lines.getFirst().amountMinor()).isEqualTo(100_000);
        assertThat(lines.getFirst().description()).isEqualTo("CARD SETTLEMENT");
    }

    @Test
    void handlesQuotedFieldsContainingCommas() {
        List<StatementLine> lines = parse(
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,"ACME SRL, MILANO"
                """);

        assertThat(lines.getFirst().description()).isEqualTo("ACME SRL, MILANO");
    }

    @Test
    void handlesEuropeanDecimalCommas() {
        List<StatementLine> lines = parse(
                """
                value_date,amount,currency,description
                2026-06-04,"1.250,50",EUR,EUROPEAN FORMAT
                2026-06-05,"1,250.50",EUR,ANGLO FORMAT
                """);

        assertThat(lines.getFirst().amountMinor()).isEqualTo(125_050);
        assertThat(lines.get(1).amountMinor()).isEqualTo(125_050);
    }

    @Test
    void honoursTheCurrencyExponent() {
        List<StatementLine> lines = parse(
                """
                value_date,amount,currency,description
                2026-06-04,1000,JPY,YEN HAS NO MINOR UNIT
                2026-06-05,1.234,TND,DINAR HAS THREE
                """);

        assertThat(lines.getFirst().amountMinor()).isEqualTo(1_000);
        assertThat(lines.get(1).amountMinor()).isEqualTo(1_234);
    }

    @Test
    void refusesMoreDecimalsThanTheCurrencyAllows() {
        // A statement showing 1.005 EUR is a file to investigate, not a number to
        // round on the operator's behalf.
        assertThatThrownBy(
                        () -> parse(
                                """
                        value_date,amount,currency,description
                        2026-06-04,1.005,EUR,TOO PRECISE
                        """))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void reportsTheRowNumberOfABadValue() {
        assertThatThrownBy(
                        () -> parse(
                                """
                        value_date,amount,currency,description
                        2026-06-04,1000.00,EUR,FINE
                        2026-06-05,not-a-number,EUR,BROKEN
                        """))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("row 2");
    }

    @Test
    void refusesAFileMissingARequiredColumn() {
        assertThatThrownBy(
                        () -> parse(
                                """
                        value_date,currency,description
                        2026-06-04,EUR,NO AMOUNT COLUMN
                        """))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void refusesAnEmptyFileAndAHeaderWithNoRows() {
        assertThatThrownBy(() -> parse("")).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> parse("value_date,amount,currency,description\n"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("no rows");
    }

    @Test
    void skipsBlankLinesWithoutRenumberingTheRest() {
        List<StatementLine> lines = parse(
                """
                value_date,amount,currency,description
                2026-06-04,1000.00,EUR,FIRST

                2026-06-05,2000.00,EUR,SECOND
                """);

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(StatementLine::rowNo).containsExactly(1, 2);
    }
}
