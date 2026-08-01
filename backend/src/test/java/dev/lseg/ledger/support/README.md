# Test support

Shared fixtures for the suites described in [`docs/07-testing.md`](../../../../../../../../docs/07-testing.md).

Planned contents:

- `PostgresTestcontainer` — a single reused container for the whole integration
  suite. There is no H2 fallback: deferred constraint triggers, generated columns
  and `pg_trgm` do not exist there, so an H2 test would verify behaviour that
  production does not have.
- `LedgerFixtures` — chart of accounts and balanced-entry builders.
- `Generators` — jqwik generators for valid and invalid entry histories.
