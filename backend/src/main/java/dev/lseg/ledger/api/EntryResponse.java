package dev.lseg.ledger.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.lseg.ledger.domain.PostedEntry;

public record EntryResponse(
        UUID id,
        long sequenceNo,
        LocalDate effectiveDate,
        Instant postedAt,
        String description,
        String currency,
        String source,
        String externalRef,
        UUID reversalOfEntryId,
        String reversalReason,
        MoneyResponse totalDebit,
        MoneyResponse totalCredit,
        List<EntryLineResponse> lines,
        UUID createdBy,
        String requestId) {

    public static EntryResponse of(PostedEntry entry) {
        return new EntryResponse(
                entry.id(),
                entry.sequenceNo(),
                entry.effectiveDate(),
                entry.postedAt(),
                entry.description(),
                entry.currency().getCurrencyCode(),
                entry.source().name(),
                entry.externalRef(),
                entry.reversalOfEntryId(),
                entry.reversalReason(),
                new MoneyResponse(
                        entry.totalDebitMinor(),
                        entry.currency().getCurrencyCode(),
                        dev.lseg.ledger.domain.Money.of(entry.totalDebitMinor(), entry.currency())
                                .toDecimalString()),
                new MoneyResponse(
                        entry.totalCreditMinor(),
                        entry.currency().getCurrencyCode(),
                        dev.lseg.ledger.domain.Money.of(entry.totalCreditMinor(), entry.currency())
                                .toDecimalString()),
                entry.lines().stream().map(EntryLineResponse::of).toList(),
                entry.createdBy(),
                entry.requestId());
    }
}
