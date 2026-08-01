package dev.lseg.ledger.ledger;

import dev.lseg.ledger.domain.AccountType;
import dev.lseg.ledger.domain.Money;

public record TrialBalanceRow(
        String accountCode, String accountName, AccountType type, Money debit, Money credit, Money balance) {}
