package dev.lseg.ledger.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note what is absent: lines.
 *
 * <p>The server builds the mirrored lines from the original. That is what makes
 * invariant I9 hold — a reversal cannot be a partial or subtly different
 * cancellation of the entry it claims to reverse, because nobody gets to
 * describe it.
 *
 * <p>{@code reason} is mandatory and free text. It is what an auditor reads six
 * months later.
 */
public record ReversalRequest(LocalDate effectiveDate, @NotBlank @Size(max = 500) String reason) {}
