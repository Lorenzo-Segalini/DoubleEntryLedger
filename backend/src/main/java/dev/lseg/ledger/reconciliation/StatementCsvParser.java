package dev.lseg.ledger.reconciliation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;

/**
 * Reads a bank statement CSV into normalised {@link StatementLine}s.
 *
 * <p>Expected header: {@code value_date, amount, currency, description,
 * external_id, counterparty_ref}. Column order is read from the header rather
 * than assumed, because every bank exports the same fields in a different order.
 *
 * <p>Amounts are parsed as decimal text and converted to minor units using the
 * currency's exponent. This is one of only two places in the system where a
 * decimal becomes an integer, and it refuses rather than rounds: a statement
 * showing 1.005 EUR is a file to investigate, not a number to guess at.
 */
@Component
public class StatementCsvParser {

    private static final List<String> REQUIRED = List.of("value_date", "amount", "currency", "description");
    private static final int MAX_ROWS = 20_000;

    public List<StatementLine> parse(InputStream input) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw invalid("the file is empty", Map.of());
            }

            Map<String, Integer> columns = headerIndex(headerLine);
            List<StatementLine> lines = new ArrayList<>();

            String row;
            int rowNo = 0;
            while ((row = reader.readLine()) != null) {
                if (row.isBlank()) {
                    continue;
                }
                rowNo++;
                if (rowNo > MAX_ROWS) {
                    throw invalid("statement exceeds %d rows".formatted(MAX_ROWS), Map.of("maxRows", MAX_ROWS));
                }
                lines.add(toLine(rowNo, splitCsv(row), columns));
            }

            if (lines.isEmpty()) {
                throw invalid("the statement has a header but no rows", Map.of());
            }
            return lines;

        } catch (IOException e) {
            throw invalid("the file could not be read", Map.of());
        }
    }

    private Map<String, Integer> headerIndex(String headerLine) {
        List<String> header = splitCsv(headerLine);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim().toLowerCase().replace(' ', '_'), i);
        }

        List<String> missing =
                REQUIRED.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw invalid("missing required column(s): " + String.join(", ", missing), Map.of("missing", missing));
        }
        return index;
    }

    private StatementLine toLine(int rowNo, List<String> values, Map<String, Integer> columns) {
        String currencyCode = value(values, columns, "currency", rowNo).toUpperCase();
        Currency currency = currency(currencyCode, rowNo);

        return StatementLine.parsed(
                rowNo,
                date(value(values, columns, "value_date", rowNo), rowNo),
                minorUnits(value(values, columns, "amount", rowNo), currency, rowNo),
                currencyCode,
                value(values, columns, "description", rowNo),
                optional(values, columns, "external_id"),
                optional(values, columns, "counterparty_ref"));
    }

    private static long minorUnits(String raw, Currency currency, int rowNo) {
        String cleaned = raw.trim().replace(" ", "").replace("'", "");
        // A European export may use a comma as the decimal separator, and a
        // thousands separator of the opposite kind. Decide from the last one seen.
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma > lastDot) {
            cleaned = cleaned.replace(".", "").replace(',', '.');
        } else {
            cleaned = cleaned.replace(",", "");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw invalid("row %d: '%s' is not an amount".formatted(rowNo, raw), Map.of("rowNo", rowNo));
        }

        int exponent = Math.max(currency.getDefaultFractionDigits(), 0);
        if (amount.scale() > exponent) {
            throw invalid(
                    "row %d: %s has more decimal places than %s allows"
                            .formatted(rowNo, raw, currency.getCurrencyCode()),
                    Map.of("rowNo", rowNo, "currency", currency.getCurrencyCode()));
        }
        return amount.movePointRight(exponent).longValueExact();
    }

    private static LocalDate date(String raw, int rowNo) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw invalid("row %d: '%s' is not an ISO date".formatted(rowNo, raw), Map.of("rowNo", rowNo));
        }
    }

    private static Currency currency(String code, int rowNo) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw invalid("row %d: '%s' is not a currency code".formatted(rowNo, code), Map.of("rowNo", rowNo));
        }
    }

    private static String value(List<String> values, Map<String, Integer> columns, String column, int rowNo) {
        int index = columns.get(column);
        if (index >= values.size() || values.get(index).isBlank()) {
            throw invalid("row %d: %s is missing".formatted(rowNo, column), Map.of("rowNo", rowNo, "column", column));
        }
        return values.get(index).trim();
    }

    private static String optional(List<String> values, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= values.size() || values.get(index).isBlank()) {
            return null;
        }
        return values.get(index).trim();
    }

    /** Minimal RFC 4180: comma separated, double quotes escape commas and are doubled to escape themselves. */
    private static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static LedgerException invalid(String message, Map<String, Object> details) {
        return new LedgerException(LedgerError.STATEMENT_NOT_READABLE, message, details);
    }
}
