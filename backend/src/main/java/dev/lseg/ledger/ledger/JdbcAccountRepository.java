package dev.lseg.ledger.ledger;

import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.lseg.ledger.domain.Account;
import dev.lseg.ledger.domain.AccountStatus;
import dev.lseg.ledger.domain.AccountType;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("type")),
            Currency.getInstance(rs.getString("currency").trim()),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getObject("parent_id", UUID.class));

    private static final String SELECT = "SELECT id, code, name, type, currency, status, parent_id FROM account ";

    private final JdbcClient jdbc;

    JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findByCode(String code) {
        return jdbc.sql(SELECT + "WHERE code = :code")
                .param("code", code)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Map<String, Account> findByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(SELECT + "WHERE code = ANY(:codes)")
                .param("codes", codes.toArray(String[]::new))
                .query(MAPPER)
                .list()
                .stream()
                .collect(Collectors.toMap(Account::code, Function.identity()));
    }

    @Override
    public List<Account> findAll() {
        return jdbc.sql(SELECT + "ORDER BY code").query(MAPPER).list();
    }
}
