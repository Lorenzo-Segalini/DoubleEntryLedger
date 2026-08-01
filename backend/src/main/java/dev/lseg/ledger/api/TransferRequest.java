package dev.lseg.ledger.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Sugar over the two-line case.
 *
 * <p>It exists because the two-account movement is most of real traffic, and
 * forcing every caller to hand-write a balanced line array invites arithmetic
 * mistakes at the edge. It expands to an ordinary entry and runs through the
 * identical validation and idempotency path.
 */
public record TransferRequest(
        @NotNull LocalDate effectiveDate,
        @NotBlank String fromAccountCode,
        @NotBlank String toAccountCode,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 200) String externalRef) {}
