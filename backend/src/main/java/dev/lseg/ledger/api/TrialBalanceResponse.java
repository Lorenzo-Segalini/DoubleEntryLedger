package dev.lseg.ledger.api;

import java.time.LocalDate;
import java.util.List;

/**
 * @param outOfBalanceMinor always present and always computed. A trial balance
 *     that only renders when it balances cannot tell you the one thing you would
 *     ever run it to find out.
 */
public record TrialBalanceResponse(
        LocalDate asOf,
        String currency,
        List<Row> rows,
        MoneyResponse totalDebit,
        MoneyResponse totalCredit,
        long outOfBalanceMinor,
        boolean balanced) {

    public record Row(
            String accountCode,
            String accountName,
            String type,
            MoneyResponse debit,
            MoneyResponse credit,
            MoneyResponse balance) {}
}
