# 7. Testing Strategy

The claim this project makes is that the ledger's invariants hold. A claim like
that is either executable or it is marketing. Every invariant in
[§1.3](01-domain-model.md#13-the-invariants) has a test that fails if it is
broken, and CI will not merge a red build.

## 7.1 Layers

| Layer | Tool | Runs against | Count (target) | Wall time |
|---|---|---|---|---|
| Domain unit | JUnit 5 + AssertJ | Pure objects, no Spring | ~150 | < 2 s |
| Domain property | jqwik | Pure domain, no Docker | 9 properties × 200–1000 tries | < 1 s |
| Database property | jqwik + Testcontainers | Real schema and queries | 6 properties × 100–200 tries | ~20 s |
| Persistence | Testcontainers Postgres | Real schema, real triggers | ~50 | ~40 s |
| API integration | `@SpringBootTest` + Testcontainers + RestAssured | Full stack, real HTTP | ~70 | ~90 s |
| Architecture | ArchUnit | Bytecode | ~12 rules | < 5 s |
| Frontend unit/component | Vitest + Testing Library + MSW | Components | ~120 | ~20 s |
| E2E | Playwright | Compose stack | ~15 flows | ~3 min |

**H2 is not used, at any layer.** Half the invariants in this system live in
Postgres-specific features — deferred constraint triggers, generated columns,
partial unique indexes, `pg_trgm`. A test against H2 would pass while the
production behaviour it claims to verify does not exist. Testcontainers with the
same Postgres major version as production is the only configuration that means
anything here.

One container serves the whole suite, held in a static singleton
(`support/LedgerPostgres`) and reclaimed by Testcontainers' Ryuk sidecar at JVM
exit. It is deliberately **not** annotated `@Container`: that extension stops the
container when its declaring class finishes, while Spring keeps the application
context cached across classes — the next test class then fails on a dead database.

Isolation is by truncation between tests, not by transaction rollback. The
balance rule is a `DEFERRABLE INITIALLY DEFERRED` trigger that only fires at
COMMIT, so a test that never commits never exercises the rule it is testing.

## 7.2 Invariants as tests

Each invariant maps to a named test class, so a failure report names the
accounting rule that broke rather than a method:

| Invariant | Domain | Database |
|---|---|---|
| I1 entries balance | `JournalEntryInvariantTest.Balancing`, `LedgerInvariantProperties` | `AppendOnlyInvariantIT.anUnbalancedEntryCannotBeCommitted…` |
| I2 ≥ 2 lines | `JournalEntryInvariantTest.MinimumLines` | `AppendOnlyInvariantIT.aSingleLineEntryCannotBeCommitted…` |
| I3 positive integer minor units | `MoneyTest`, `JournalEntryInvariantTest.PositiveAmounts` | `AppendOnlyInvariantIT.aNegativeAmountIsRejected…` |
| I4 single currency per entry | `JournalEntryInvariantTest.SingleCurrency` | balance trigger (`assert_entry_is_balanced`) |
| I5 line currency = account currency | `PostingServiceIT.aLineInADifferentCurrency…` | `AppendOnlyInvariantIT.aLineCannotReferenceAnAccountIn…` |
| I6 append-only | — (no mutator exists) | `AppendOnlyInvariantIT` ×5, incl. the grant matrix |
| I7 journal nets to zero | `LedgerInvariantProperties.signedAmountsAlwaysSumToZero` | `LedgerDatabasePropertyIT.theWholeJournalAlwaysNetsToZero` |
| I8 single reversal | — | `PostingServiceIT.anEntryCannotBeReversedTwice` + unique index |
| I9 reversal mirrors original | `LedgerInvariantProperties.mirroringAnEntry…` | `PostingServiceIT.aReversalMirrorsTheOriginal…`, `LedgerDatabasePropertyIT.reversingEveryEntryRestoresEveryBalance` |

Every row above exists and passes today. Rows for the API and reconciliation
layers will join it as those land; nothing is listed here before it is written.

`AppendOnlyInvariantIT` is worth spelling out, because it tests the database
rather than the code:

```java
@Test
void updatingAPostedLineIsRejectedByTheDatabase() {
    UUID entryId = postBalancedEntry(EUR, 10_000);

    assertThatThrownBy(() -> jdbc.update(
            "UPDATE journal_line SET amount_minor = 1 WHERE entry_id = ?", entryId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
}

@Test
void deletingAPostedEntryIsRejectedByTheDatabase() { /* … */ }

@Test
void theApplicationRoleHoldsNoUpdateOrDeleteGrant() {
    assertThat(jdbc.queryForList("""
            SELECT privilege_type FROM information_schema.role_table_grants
             WHERE grantee = 'ledger_app' AND table_name IN ('journal_entry','journal_line')
            """, String.class))
        .containsExactlyInAnyOrder("SELECT", "INSERT", "SELECT", "INSERT");
}
```

The last test asserts the *grant matrix*, not behaviour. A future migration
that widens permissions fails CI even if no code path uses the new privilege —
which is the point, since the danger of a broad grant is the code that does not
exist yet.

## 7.3 Property-based testing

Example-based tests check the cases you thought of. Properties check the ones you
did not. jqwik generates random-but-valid histories and asserts the invariants
survive all of them.

There are two property suites, split by what they can reach:

| Suite | Runs on | Asserts against |
|---|---|---|
| `LedgerInvariantProperties` | Surefire, no Docker | The domain model in isolation |
| `LedgerDatabasePropertyIT` | Failsafe, Testcontainers | The real schema, triggers and queries |

### Domain properties

Pure objects, no Spring, milliseconds to run: every constructed entry balances,
signed amounts sum to zero, mirroring an entry produces the exact opposite
movement, perturbing any line by any non-zero amount makes the entry
unconstructible, and `balanceSign` is its own inverse.

### Database properties

```java
@Property(tries = 200)
void theWholeJournalAlwaysNetsToZero(@ForAll("histories") List<JournalEntry> history) {
    history.forEach(entry -> posting.post(entry, context()));

    // Non-vacuity guard: a sum over an empty journal is also zero.
    assertThat(countEntries()).isEqualTo(history.size());

    assertThat(balances.outOfBalanceMinor()).isZero();
}

@Property(tries = 100)
void historicalBalancesAreStableAsNewEntriesArrive(@ForAll("histories") List<JournalEntry> history) {
    history.forEach(entry -> posting.post(entry, context()));
    Map<String, Long> asOfCutoff = snapshotBalancesAsOf(cutoff);

    history.forEach(entry -> posting.post(entry, context()));

    assertThat(snapshotBalancesAsOf(cutoff)).isEqualTo(asOfCutoff);
}
```

Also asserted: a derived balance equals a raw sum over the same account's lines
(two independent paths to one number — the claim ADR-0003 rests on), reversing
every entry restores every balance while doubling the entry count rather than
erasing anything, the trial balance sums to zero, and every line carries its
entry's effective date so the denormalised copy cannot drift.

**These are not `@SpringBootTest` classes.** jqwik runs on its own JUnit Platform
engine and does not process Jupiter extensions, so `SpringExtension` — and with
it `@SpringBootTest`, `@Autowired` and `@ServiceConnection` — has no effect. The
context is built by hand in a `@BeforeContainer` hook against the container the
rest of the suite already shares, and `@BeforeTry` truncates the journal so each
try starts empty. This is the one place in the codebase where Spring's test
support is bypassed, and it is worth knowing why before changing it.

Because the context is hand-built, the container's connection details must be
passed as **command-line arguments**:

```java
new SpringApplicationBuilder(LedgerApplication.class)
        .web(WebApplicationType.NONE)
        .run("--spring.datasource.url=" + LedgerPostgres.INSTANCE.getJdbcUrl(), …);
```

Not `SpringApplicationBuilder.properties()`. That method registers *default*
properties, which sit at the bottom of Spring's precedence order and lose to
`application.yml` — whose datasource URL falls back to `localhost:5432`.

The consequence is worse than a failed test. This suite truncates the journal
before every try, so a context pointed at the wrong database destroys data and
still reports green. That is precisely what happened: the suite passed on a
developer machine running `pnpm db` — against the Compose database, which it was
quietly wiping — and only failed in CI, where nothing listens on that port.

`assertConnectedToTheTestContainer()` now runs immediately after the context
starts and refuses to proceed unless the connection's port matches the
container's mapped port. A wrong connection has to be a loud failure, never a
silent one.

> **The general lesson.** A destructive test fixture must verify what it is about
> to destroy. Any future suite that truncates, deletes or resets shared state
> belongs behind the same check.

### Generators

`entries()` produces entries balanced by construction: one to three debit lines
against a single credit line absorbing their total. That shape is deliberate — it
mirrors the settlement-plus-fee case that makes a two-line-only model wrong,
rather than a plain transfer. Dates land anywhere in the past 400 days.

Overflow at `Long.MAX_VALUE` is deliberately *not* generated: summing twenty such
lines would throw inside the generator rather than inside a property, testing the
fixture instead of the domain. That boundary has targeted assertions in
`MoneyTest`.

### Non-vacuity, and proof the properties have teeth

A property that passes trivially is worse than no property, so two things guard
against it. The properties assert the journal is actually non-empty and at least
one balance actually moved before comparing anything.

And the suite was checked by deliberately breaking the code:

| Injected defect | Caught by |
|---|---|
| `Direction.CREDIT` sign flipped to `+1` | 6 of 9 domain properties |
| Balance query's date filter moved from `ON` to `WHERE` — the classic reporting bug | 3 of 6 database properties, plus 2 example-based tests |

The second one matters most: it is a defect invisible to the domain model, which
only the database-level properties could find.

## 7.4 Idempotency under concurrency

The headline test, described in [§4.9](04-idempotency.md#49-how-this-is-proven):

```java
@Test
void thirtyTwoConcurrentRetriesPostExactlyOneEntry() throws Exception {
    String key = UUID.randomUUID().toString();
    TransferRequest request = transfer("1000", "1100", 250_000, EUR);

    var barrier = new CyclicBarrier(32);
    var responses = IntStream.range(0, 32)
        .mapToObj(i -> executor.submit(() -> { barrier.await(); return post(request, key); }))
        .toList().stream().map(this::get).toList();

    assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
    assertThat(responses).filteredOn(r -> r.statusCode() == 200).hasSize(31);
    assertThat(responses).extracting(r -> r.jsonPath().getString("id")).containsOnly(entryId(responses));

    assertThat(count("journal_entry")).isEqualTo(1);
    assertThat(count("journal_line")).isEqualTo(2);
}
```

`CyclicBarrier` rather than just launching threads: without it the requests
arrive spread over milliseconds and a check-then-insert implementation passes by
luck. The barrier forces genuine contention on the primary key, which is the
only thing this test is actually about.

This test is run 20 times in a row in a nightly job. A concurrency test that
passes once has demonstrated very little.

## 7.5 Reconciliation properties

```java
@Property(tries = 500)
void theBridgeAlwaysClosesTheDifference(
        @ForAll("journalHistory") List<JournalEntry> journal,
        @ForAll("discrepancies") List<Discrepancy> injected) {

    var statement = StatementBuilder.from(journal).applying(injected).build();
    var report = reconciliationService.run(statement);

    long ledgerClosing    = balanceQuery.asOf(statement.accountId(), statement.periodEnd()).signedMinor();
    long bridgeTotal      = report.breaks().stream().mapToLong(Break::deltaMinor).sum();

    assertThat(ledgerClosing + bridgeTotal).isEqualTo(statement.closingBalanceMinor());
    assertThat(report.bridgeBalanced()).isTrue();
}
```

`discrepancies` injects a random mix of dropped lines, duplicated lines, amount
perturbations, and date shifts across the period boundary. If the engine
misclassifies any of them or double-consumes a line, the bridge fails to close
and the property fails with a shrunk counterexample.

Determinism gets its own test: the same import run twice must produce identical
matches, in the same order, with the same confidences.

## 7.6 Architecture tests

ArchUnit rules that encode decisions the compiler cannot:

```java
@ArchTest
static final ArchRule noFloatingPointMoney = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().areAssignableTo(Double.class)
    .orShould().dependOnClassesThat().areAssignableTo(Float.class)
    .because("money is integer minor units; see ADR-0002");

@ArchTest
static final ArchRule journalIsAppendOnly = noClasses()
    .that().resideInAPackage("..ledger..")
    .should().callMethodWhere(target(nameMatching("delete.*|update.*"))
        .and(owner(assignableTo(JournalEntryRepository.class))))
    .because("corrections are reversals; see ADR-0001");

@ArchTest
static final ArchRule domainDoesNotDependOnSpring = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

@ArchTest
static final ArchRule writeEndpointsRequireIdempotency = classes()
    .that().areAnnotatedWith(RestController.class)
    .should(haveIdempotencyKeyParameterOnEveryPostMapping());
```

The last one is the most valuable: it means adding a new write endpoint without
an idempotency key fails the build. Otherwise the guarantee decays the first
time someone adds a controller in a hurry.

## 7.7 Mutation testing

PIT runs against `..domain..` and `..ledger..` on a nightly schedule with a
mutation-score threshold of 85%. It answers the question line coverage cannot:
*if the balance check were inverted, would any test notice?* A codebase whose
entire pitch is "the invariants hold" should be able to prove its tests would
detect a broken invariant, and mutation score is the closest available measure.

## 7.8 CI gates

A pull request merges only when all of these pass:

- Backend compiles with `-Werror`; Spotless and Checkstyle clean
- All unit, property, persistence and integration tests green
- Line coverage ≥ 85% overall and ≥ 95% in `..domain..` (JaCoCo, enforced)
- ArchUnit rules green
- OpenAPI diff shows no undeclared breaking change (`oasdiff`)
- Frontend: `tsc --noEmit`, oxlint, Vitest, production build
- Playwright E2E green against the Compose stack
- Trivy: no `HIGH`/`CRITICAL` vulnerabilities in the image

The coverage split matters more than the number. 85% everywhere would let the
domain be thinly tested while controllers pad the figure; the domain is where a
gap is expensive.

---

Next: [Deployment](08-deployment.md).
