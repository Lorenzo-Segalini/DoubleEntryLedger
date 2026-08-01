package dev.lseg.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import dev.lseg.ledger.domain.JournalEntry;
import dev.lseg.ledger.domain.Money;
import dev.lseg.ledger.domain.PostedEntry;
import dev.lseg.ledger.support.PostgresIT;

/**
 * Invariant I6 and the deferred balance trigger, tested against the database
 * rather than against the code that is supposed to respect them.
 *
 * <p>These issue raw SQL on purpose. A test that only exercised
 * {@link PostingService} would prove the service is well behaved; it would say
 * nothing about a migration, a support script or a psql session. The claim being
 * made is stronger than "the application does not do this" — it is "the database
 * will not permit it".
 */
class AppendOnlyInvariantIT extends PostgresIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @Autowired
    PostingService posting;

    private PostedEntry entry;

    @BeforeEach
    void setUp() {
        truncateJournal();
        entry = posting.post(
                JournalEntry.transfer(TODAY, "seed for this test", "1000", "1100", Money.of(10_000, EUR)),
                PostingContext.of(SYSTEM_USER, "req-" + UUID.randomUUID()));
    }

    @Test
    void updatingAPostedLineIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_line SET amount_minor = 1 WHERE entry_id = :id")
                        .param("id", entry.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void updatingAPostedEntryIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entry SET description = 'edited' WHERE id = :id")
                        .param("id", entry.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deletingAPostedEntryIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entry WHERE id = :id")
                        .param("id", entry.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deletingAPostedLineIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_line WHERE entry_id = :id")
                        .param("id", entry.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void theApplicationRoleHoldsOnlySelectAndInsertOnTheJournal() {
        // Asserts the grant matrix, not behaviour. A future migration that widens
        // permissions fails here even if no code uses the new privilege — the
        // danger of a broad grant is the code that does not exist yet.
        List<String> privileges = jdbc.sql(
                        """
                        SELECT DISTINCT privilege_type
                          FROM information_schema.role_table_grants
                         WHERE grantee = 'ledger_app'
                           AND table_name IN ('journal_entry', 'journal_line')
                        """)
                .query(String.class)
                .list();

        assertThat(privileges).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    void theAuditLogIsAppendOnlyToo() {
        List<String> privileges = jdbc.sql(
                        """
                        SELECT DISTINCT privilege_type
                          FROM information_schema.role_table_grants
                         WHERE grantee = 'ledger_app' AND table_name = 'audit_event'
                        """)
                .query(String.class)
                .list();

        assertThat(privileges).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    void anUnbalancedEntryCannotBeCommittedEvenBypassingTheApplication() {
        // Hand-written SQL, exactly what a careless migration or support script
        // would do. The deferred constraint trigger fires at COMMIT.
        assertThatThrownBy(() -> insertRawUnbalancedEntry())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void aSingleLineEntryCannotBeCommittedEvenBypassingTheApplication() {
        assertThatThrownBy(() -> insertRawSingleLineEntry())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("at least 2 required");
    }

    @Test
    void aNegativeAmountIsRejectedByACheckConstraint() {
        assertThatThrownBy(() -> jdbc.sql(
                                """
                                INSERT INTO journal_line
                                    (entry_id, line_no, account_id, direction, amount_minor, currency, effective_date)
                                SELECT :entryId, 99, (SELECT id FROM account WHERE code = '1000'),
                                       'DEBIT', -5, 'EUR', :date
                                """)
                        .param("entryId", entry.id())
                        .param("date", TODAY)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("line_amount_positive");
    }

    @Test
    void aLineCannotReferenceAnAccountInADifferentCurrency() {
        // Invariant I5, enforced by the composite foreign key rather than by code.
        jdbc.sql(
                        """
                        INSERT INTO account (code, name, type, currency, created_by)
                        VALUES ('1901', 'Cash JPY', 'ASSET', 'JPY', :user)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("user", SYSTEM_USER)
                .update();

        assertThatThrownBy(() -> jdbc.sql(
                                """
                                INSERT INTO journal_line
                                    (entry_id, line_no, account_id, direction, amount_minor, currency, effective_date)
                                SELECT :entryId, 98, (SELECT id FROM account WHERE code = '1901'),
                                       'DEBIT', 100, 'EUR', :date
                                """)
                        .param("entryId", entry.id())
                        .param("date", TODAY)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("line_account_currency_fk");
    }

    @Test
    void anAccountWithPostingsCannotChangeTypeOrCurrency() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE account SET type = 'EXPENSE' WHERE code = '1000'")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void renamingAnAccountIsStillAllowed() {
        // The name is a label; the type is meaning. Only one of them is frozen.
        int updated = jdbc.sql("UPDATE account SET name = 'Cash at Bank' WHERE code = '1000'")
                .update();

        assertThat(updated).isEqualTo(1);
    }

    private void insertRawUnbalancedEntry() {
        inTransaction.executeWithoutResult(status -> {
            UUID id = UUID.randomUUID();
            insertRawEntry(id, "unbalanced by hand");
            insertRawLine(id, 1, "1000", "DEBIT", 10_000);
            insertRawLine(id, 2, "1100", "CREDIT", 9_000);
        });
    }

    private void insertRawSingleLineEntry() {
        inTransaction.executeWithoutResult(status -> {
            UUID id = UUID.randomUUID();
            insertRawEntry(id, "one line by hand");
            insertRawLine(id, 1, "1000", "DEBIT", 10_000);
        });
    }

    private void insertRawEntry(UUID id, String description) {
        jdbc.sql(
                        """
                        INSERT INTO journal_entry
                            (id, effective_date, description, currency, source, created_by, request_id)
                        VALUES (:id, :date, :description, 'EUR', 'API', :user, 'raw-sql')
                        """)
                .param("id", id)
                .param("date", TODAY)
                .param("description", description)
                .param("user", SYSTEM_USER)
                .update();
    }

    private void insertRawLine(UUID entryId, int lineNo, String accountCode, String direction, long amountMinor) {
        jdbc.sql(
                        """
                        INSERT INTO journal_line
                            (entry_id, line_no, account_id, direction, amount_minor, currency, effective_date)
                        SELECT :entryId, :lineNo, (SELECT id FROM account WHERE code = :code),
                               CAST(:direction AS direction), :amount, 'EUR', :date
                        """)
                .param("entryId", entryId)
                .param("lineNo", lineNo)
                .param("code", accountCode)
                .param("direction", direction)
                .param("amount", amountMinor)
                .param("date", TODAY)
                .update();
    }
}
