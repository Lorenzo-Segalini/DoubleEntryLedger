# 7. Testing Strategy

The claim this project makes is that the ledger's invariants hold. A claim like
that is either executable or it is marketing. Every invariant in
[§1.3](01-domain-model.md#13-the-invariants) has a test that fails if it is
broken, and CI will not merge a red build.

## 7.1 Layers

| Layer | Tool | Runs against | Count (target) | Wall time |
|---|---|---|---|---|
| Domain unit | JUnit 5 + AssertJ | Pure objects, no Spring | ~150 | < 2 s |
| Property | jqwik | Domain + repository | ~15 properties × 1000 cases | ~30 s |
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

A single container is reused across the whole integration suite
(`@Testcontainers` with a static container, `.withReuse(true)` locally). Isolation
comes from a per-test transaction rollback, except where a test must observe
commit behaviour — deferred triggers and idempotency conflicts both require real
commits, so those tests truncate instead.

## 7.2 Invariants as tests

Each invariant maps to a named test class, so a failure report names the
accounting rule that broke rather than a method:

| Invariant | Test |
|---|---|
| I1 entries balance | `EntryBalancesInvariantTest`, `BalancedEntryProperty` |
| I2 ≥ 2 lines | `MinimumLinesInvariantTest` |
| I3 positive integer minor units | `MoneyTest`, `NoFloatingPointArchTest` |
| I4 single currency per entry | `SingleCurrencyEntryInvariantTest` |
| I5 line currency = account currency | `AccountCurrencyForeignKeyTest` |
| I6 append-only | `AppendOnlyInvariantTest`, `NoMutatingRepositoryArchTest` |
| I7 journal nets to zero | `LedgerNetsToZeroProperty`, `LedgerBalanceHealthIndicatorTest` |
| I8 single reversal | `SingleReversalInvariantTest` (incl. concurrent) |
| I9 reversal mirrors original | `ReversalMirrorsOriginalTest`, `ReversalRestoresBalanceProperty` |

`AppendOnlyInvariantTest` is worth spelling out, because it tests the database
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

The third test asserts the *grant matrix*, not behaviour. A future migration
that widens permissions fails CI even if no code path uses the new privilege —
which is the point, since the danger of a broad grant is the code that does not
exist yet.

## 7.3 Property-based testing

Example-based tests check the cases you thought of. Properties check the ones
you did not. jqwik generates thousands of random-but-valid histories and asserts
the invariants survive all of them.

```java
@Property(tries = 1000)
void theLedgerAlwaysNetsToZero(@ForAll("validEntries") List<JournalEntry> history) {
    history.forEach(postingService::post);

    assertThat(jdbc.queryForObject(
            "SELECT COALESCE(SUM(signed_amount_minor), 0) FROM journal_line", Long.class))
        .isZero();
}

@Property(tries = 1000)
void reversingAnEntryRestoresEveryAffectedBalance(@ForAll("validEntry") JournalEntry entry) {
    Map<UUID, Long> before = balancesOf(entry.accountIds());
    UUID posted = postingService.post(entry).id();

    postingService.reverse(posted, "property test");

    assertThat(balancesOf(entry.accountIds())).isEqualTo(before);
}

@Property(tries = 1000)
void derivedBalanceEqualsTheSumOfMovements(@ForAll("validEntries") List<JournalEntry> history,
                                           @ForAll("account") Account account) {
    history.forEach(postingService::post);

    assertThat(balanceQuery.asOf(account.id(), LocalDate.MAX).signedMinor())
        .isEqualTo(movementQuery.all(account.id()).stream()
                                .mapToLong(Movement::signedAmountMinor).sum());
}
```

The generators are the interesting part. `validEntries` produces entries by
construction — random account pairs, random amounts, then a balancing line
computed as the negation of the rest — so the suite explores the space of *legal*
histories rather than wasting cases on inputs the API would reject at the door.
A complementary `invalidEntries` generator asserts every one of them is refused.

Generation is deliberately hostile: amounts near `Long.MAX_VALUE` to provoke
overflow, entries with up to 20 lines, effective dates spanning decades,
currencies with 0/2/3 decimal exponents, and long chains of postings against the
same account.

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
