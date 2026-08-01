# Flyway migrations

Forward-only. There are no `undo` scripts: a mistake in a released migration is
corrected by a new migration, for the same reason a journal entry is corrected
by a reversal.

| Version | Contents |
|---|---|
| `V1__enums_and_users.sql` | Enum types, `citext`, `app_user`, `audit_event`, `reject_mutation()` |
| `V2__accounts.sql` | `account`, generated `balance_sign`, type/currency freeze trigger |
| `V3__journal.sql` | `journal_entry`, `journal_line`, deferred balance trigger, immutability triggers |
| `V4__idempotency.sql` | `idempotency_record` |
| `V5__reconciliation.sql` | `pg_trgm`, statement imports, lines, matches, typed breaks |
| `V6__grants.sql` | `ledger_app` role and the `REVOKE`/`GRANT` matrix |
| `R__demo_seed.sql` | Repeatable: system principal, chart of accounts, demo journal |

Specified in [`docs/02-data-model.md`](../../../../../../docs/02-data-model.md).

## Safe to re-run

Flyway runs each versioned migration exactly once per schema, tracked in
`flyway_schema_history`. Every statement is *also* written to survive being
executed again by hand — in DBeaver, in `psql`, or against a database that
already has these objects:

| Object | Idiom |
|---|---|
| Extensions | `CREATE EXTENSION IF NOT EXISTS` |
| Enum types | `DO` block trapping `duplicate_object` |
| Tables | `CREATE TABLE IF NOT EXISTS` |
| Indexes | `CREATE INDEX IF NOT EXISTS` |
| Functions | `CREATE OR REPLACE FUNCTION` |
| Triggers | `CREATE OR REPLACE TRIGGER` |
| Constraint triggers | `DO` block guarded on `pg_trigger` — PostgreSQL rejects `CREATE OR REPLACE CONSTRAINT TRIGGER`, and dropping to recreate would leave a window with the balance check detached |
| Grants | `REVOKE` then `GRANT`, both naturally idempotent |
| Seed rows | `ON CONFLICT DO NOTHING`, and journal entries guarded on `external_ref` |

**Nothing here drops, truncates or deletes user data.** The only `DROP` in the set
removes `seed_entry()`, a helper the seed file creates for its own use.

PostgreSQL has transactional DDL and Flyway wraps each migration in one
transaction, so a failure rolls the whole file back. The dangerous case for
`CREATE TABLE IF NOT EXISTS` — a table that exists but is structurally
incomplete — cannot arise.

Verified by applying the full set to an empty database and then re-running every
file twice: no errors, and identical row counts before and after.

## Compatibility

Migrations must stay backward compatible for one release. Rolling deploys briefly
run old code against the new schema, so drops and renames are two-step: stop
writing the column in release *n*, drop it in *n+1*.

## Running them by hand

```bash
pnpm db     # PostgreSQL on :5432
docker compose -f infra/compose.yaml exec -T postgres \
    psql -U ledger -d ledger -v ON_ERROR_STOP=1 < V1__enums_and_users.sql
```

Applying them by hand does **not** write `flyway_schema_history`. Flyway will then
try to apply them itself on the next boot; because they are idempotent it
succeeds, but the cleaner route is to let the application do it.
