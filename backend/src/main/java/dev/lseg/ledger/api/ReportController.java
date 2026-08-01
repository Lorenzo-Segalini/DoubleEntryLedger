package dev.lseg.ledger.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.ledger.BalanceQuery;
import dev.lseg.ledger.ledger.TrialBalanceRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Trial balance")
class ReportController {

    private final BalanceQuery balances;
    private final Clock clock;

    ReportController(BalanceQuery balances, Clock clock) {
        this.balances = balances;
        this.clock = clock;
    }

    @GetMapping("/trial-balance")
    @Operation(
            summary = "Trial balance as of a date",
            description = "outOfBalanceMinor is always computed and always returned.")
    TrialBalanceResponse trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "EUR") String currency) {

        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        List<TrialBalanceRow> rows = balances.trialBalance(date, currency);
        Currency unit = Currency.getInstance(currency);

        long debits = rows.stream().mapToLong(r -> r.debit().amountMinor()).sum();
        long credits = rows.stream().mapToLong(r -> r.credit().amountMinor()).sum();

        // Not asserted, computed. A report that renders only when it balances
        // cannot tell you the one thing you would ever run it to find out.
        long outOfBalance = debits - credits;

        return new TrialBalanceResponse(
                date,
                currency,
                rows.stream()
                        .map(row -> new TrialBalanceResponse.Row(
                                row.accountCode(),
                                row.accountName(),
                                row.type().name(),
                                MoneyResponse.of(row.debit()),
                                MoneyResponse.of(row.credit()),
                                MoneyResponse.of(row.balance())))
                        .toList(),
                MoneyResponse.of(Money.of(debits, unit)),
                MoneyResponse.of(Money.of(credits, unit)),
                outOfBalance,
                outOfBalance == 0);
    }
}
