package dev.lseg.ledger.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.lseg.ledger.domain.Money;

/**
 * A balance derived from the journal at read time.
 *
 * <p>{@code natural} is the figure a report shows — positive means the account
 * holds what it is supposed to hold. {@code signed} is debit-positive, which is
 * what sums to zero across the ledger. Reports need the first and arithmetic
 * needs the second, so both are returned rather than one being recomputed at
 * each call site with a sign that might be wrong.
 *
 * <p>{@code derivedAt} exists to make the derivation visible: nothing here was
 * cached, and the same query on the same {@code asOf} will return the same
 * number in a year's time.
 */
public record AccountBalance(
        UUID accountId,
        String code,
        LocalDate asOf,
        Money natural,
        Money signed,
        Money totalDebit,
        Money totalCredit,
        long lineCount,
        Instant derivedAt) {}
