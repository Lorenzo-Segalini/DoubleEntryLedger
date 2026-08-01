# Flyway migrations

Forward-only. There are no `undo` scripts: a mistake in a released migration is
corrected by a new migration, for the same reason a journal entry is corrected
by a reversal.

Planned sequence (specified in [`docs/02-data-model.md`](../../../../../../docs/02-data-model.md#213-migration-order)):

| Version | Contents |
|---|---|
| `V1__enums_and_users.sql` | Enum types, `app_user`, `audit_event`, `reject_mutation()` |
| `V2__accounts.sql` | `account` + generated `balance_sign` + freeze trigger |
| `V3__journal.sql` | `journal_entry`, `journal_line`, balance + immutability triggers |
| `V4__idempotency.sql` | `idempotency_record` |
| `V5__reconciliation.sql` | Statement import, lines, matches, breaks |
| `V6__grants.sql` | `ledger_app` role, `REVOKE`/`GRANT` matrix |
| `R__demo_seed.sql` | Repeatable, idempotent demo chart of accounts and data |

Migrations must stay backward compatible for one release: rolling deploys briefly
run old code against the new schema, so drops and renames are two-step.
