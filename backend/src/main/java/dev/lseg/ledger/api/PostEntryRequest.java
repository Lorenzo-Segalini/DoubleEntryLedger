package dev.lseg.ledger.api;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The general n-line posting request.
 *
 * <p>{@code amountMinor} is a {@code long}. Sending {@code 125.50} is a
 * deserialisation failure, not a rounding decision — which is the point.
 */
public record PostEntryRequest(
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @Size(max = 200) String externalRef,
        @NotEmpty @Size(min = 2, max = 100) @Valid List<Line> lines) {

    public record Line(
            @NotBlank String accountCode,
            @NotNull @Pattern(regexp = "DEBIT|CREDIT") String direction,
            @Positive long amountMinor,
            @Size(max = 500) String memo) {}
}
