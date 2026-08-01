package dev.lseg.ledger.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.ledger.AccountRepository;
import dev.lseg.ledger.ledger.BalanceQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Chart of accounts and derived balances")
class AccountController {

    private final AccountRepository accounts;
    private final BalanceQuery balances;
    private final Clock clock;

    AccountController(AccountRepository accounts, BalanceQuery balances, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List the chart of accounts")
    List<AccountResponse> all() {
        return accounts.findAll().stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one account")
    AccountResponse byId(@PathVariable UUID id) {
        return accounts.findById(id).map(AccountResponse::of).orElseThrow(() -> notFound(id));
    }

    @GetMapping("/{id}/balance")
    @Operation(
            summary = "Balance as of a date",
            description = "Derived from the journal at read time; nothing is cached. See ADR-0003.")
    BalanceResponse balance(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        accounts.findById(id).orElseThrow(() -> notFound(id));
        return BalanceResponse.of(balances.asOf(id, asOf == null ? LocalDate.now(clock) : asOf));
    }

    private static LedgerException notFound(UUID id) {
        return new LedgerException(LedgerError.UNKNOWN_ACCOUNT, "no account %s".formatted(id), Map.of("accountId", id));
    }
}
