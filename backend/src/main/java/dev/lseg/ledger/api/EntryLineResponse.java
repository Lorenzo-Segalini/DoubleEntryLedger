package dev.lseg.ledger.api;

import java.util.UUID;

import dev.lseg.ledger.domain.PostedLine;

public record EntryLineResponse(
        UUID id,
        int lineNo,
        UUID accountId,
        String accountCode,
        String accountName,
        String direction,
        MoneyResponse amount,
        String memo) {

    public static EntryLineResponse of(PostedLine line) {
        return new EntryLineResponse(
                line.id(),
                line.lineNo(),
                line.accountId(),
                line.accountCode(),
                line.accountName(),
                line.direction().name(),
                MoneyResponse.of(line.amount()),
                line.memo());
    }
}
