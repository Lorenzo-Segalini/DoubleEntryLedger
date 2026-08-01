package dev.lseg.ledger.ledger;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.lseg.ledger.domain.AccountType;
import dev.lseg.ledger.domain.Money;

/**
 * Balances derived from {@code journal_line}. Nothing is cached, and no balance
 * is stored anywhere — see ADR-0003.
 */
@Repository
class JdbcBalanceQuery implements BalanceQuery {

    /**
     * The date predicate sits in the {@code ON} clause, not in {@code WHERE}.
     * Moving it would drop accounts with no postings before {@code asOf} from the
     * result entirely instead of reporting zero — a mistake common enough in
     * reporting code that {@code BalanceQueryIT} has a dedicated test for it.
     */
    private static final String BALANCE =
            """
            SELECT a.id, a.code, a.currency, a.balance_sign,
                   COALESCE(SUM(l.signed_amount_minor), 0)                                        AS signed_minor,
                   COALESCE(SUM(l.amount_minor) FILTER (WHERE l.direction = 'DEBIT'), 0)          AS debit_minor,
                   COALESCE(SUM(l.amount_minor) FILTER (WHERE l.direction = 'CREDIT'), 0)         AS credit_minor,
                   COUNT(l.id)                                                                    AS line_count
              FROM account a
              LEFT JOIN journal_line l
                     ON l.account_id = a.id
                    AND l.effective_date <= :asOf
             WHERE a.id = :accountId
             GROUP BY a.id, a.code, a.currency, a.balance_sign
            """;

    private static final String TRIAL_BALANCE =
            """
            SELECT a.code, a.name, a.type, a.currency, a.balance_sign,
                   COALESCE(SUM(l.amount_minor) FILTER (WHERE l.direction = 'DEBIT'), 0)  AS debit_minor,
                   COALESCE(SUM(l.amount_minor) FILTER (WHERE l.direction = 'CREDIT'), 0) AS credit_minor,
                   COALESCE(SUM(l.signed_amount_minor), 0)                                AS signed_minor
              FROM account a
              LEFT JOIN journal_line l
                     ON l.account_id = a.id
                    AND l.effective_date <= :asOf
             WHERE a.currency = :currency
             GROUP BY a.code, a.name, a.type, a.currency, a.balance_sign
             ORDER BY a.code
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcBalanceQuery(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public AccountBalance asOf(UUID accountId, LocalDate asOf) {
        return jdbc.sql(BALANCE)
                .param("accountId", accountId)
                .param("asOf", asOf)
                .query((rs, rowNum) -> {
                    Currency currency =
                            Currency.getInstance(rs.getString("currency").trim());
                    long signed = rs.getLong("signed_minor");
                    int sign = rs.getInt("balance_sign");
                    return new AccountBalance(
                            rs.getObject("id", UUID.class),
                            rs.getString("code"),
                            asOf,
                            Money.of(signed * sign, currency),
                            Money.of(signed, currency),
                            Money.of(rs.getLong("debit_minor"), currency),
                            Money.of(rs.getLong("credit_minor"), currency),
                            rs.getLong("line_count"),
                            clock.instant());
                })
                .single();
    }

    @Override
    public AccountBalance current(UUID accountId) {
        return asOf(accountId, LocalDate.now(clock));
    }

    @Override
    public List<TrialBalanceRow> trialBalance(LocalDate asOf, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        return jdbc.sql(TRIAL_BALANCE)
                .param("asOf", asOf)
                .param("currency", currencyCode)
                .query((rs, rowNum) -> new TrialBalanceRow(
                        rs.getString("code"),
                        rs.getString("name"),
                        AccountType.valueOf(rs.getString("type")),
                        Money.of(rs.getLong("debit_minor"), currency),
                        Money.of(rs.getLong("credit_minor"), currency),
                        Money.of(rs.getLong("signed_minor") * rs.getInt("balance_sign"), currency)))
                .list();
    }

    @Override
    public long outOfBalanceMinor() {
        return jdbc.sql("SELECT COALESCE(SUM(signed_amount_minor), 0) FROM journal_line")
                .query(Long.class)
                .single();
    }
}
