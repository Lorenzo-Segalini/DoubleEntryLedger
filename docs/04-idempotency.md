# 4. Idempotency

## 4.1 The failure this prevents

A client posts a transfer. The entry is written and committed. The response is
lost — the connection drops, a load balancer times out at 30s, the client's HTTP
library gives up, a Fly.io machine is replaced mid-response.

The client now knows nothing. It cannot distinguish "the transfer did not
happen" from "the transfer happened and I did not hear about it". Both retrying
and not retrying can be wrong. Left alone, the sensible-looking choice — retry —
posts the money twice.

This is not an exotic case. It is the normal behaviour of networks, and it is
the reason every payments API in production has an idempotency key.

The guarantee this system offers:

> A write request carrying an `Idempotency-Key` is applied **at most once**.
> A repeat of that request with the same body returns the original outcome —
> the same status, the same body, the same entry id — without posting again.

## 4.2 Where the guarantee actually comes from

Not from checking whether the key exists. That check is a read followed by a
write, and two concurrent requests can both read "absent" before either writes.
Retries frequently arrive concurrently — a client timeout fires while the
original request is still executing — so this is the common case, not the rare
one.

The guarantee comes from **the primary key on `idempotency_record`**. Claiming a
key is an `INSERT`. Postgres serialises it. Exactly one caller wins, and the
loser learns it lost from the database, not from a race it might have observed
wrongly.

Everything else in this design is bookkeeping around that one fact.

## 4.3 The flow

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as API
    participant DB as PostgreSQL

    C->>A: POST /transfers (Idempotency-Key: K, body B)
    A->>A: fingerprint = SHA-256(canonical(B))
    A->>DB: BEGIN
    A->>DB: INSERT INTO idempotency_record (K, endpoint, principal, fingerprint, IN_PROGRESS)<br/>ON CONFLICT DO NOTHING

    alt insert claimed the key (1 row)
        A->>DB: INSERT journal_entry + journal_line ×n
        A->>DB: deferred triggers verify balance at commit
        A->>DB: UPDATE idempotency_record SET status=COMPLETED, response_status, response_body, entry_id
        A->>DB: COMMIT
        A-->>C: 201 Created + entry
    else key already present (0 rows)
        A->>DB: ROLLBACK; SELECT the existing record
        alt COMPLETED and fingerprint matches
            A-->>C: 200 OK + stored response<br/>Idempotency-Replayed: true
        else COMPLETED and fingerprint differs
            A-->>C: 409 idempotency-key-conflict
        else still IN_PROGRESS
            A-->>C: 409 idempotency-request-in-flight<br/>Retry-After: 1
        end
    end
```

The critical property is that **the claim, the ledger write and the recorded
response are one transaction**. There is no window in which the entry exists but
the idempotency record does not, or vice versa. A crash anywhere rolls back
both, and the retry finds a clean slate and proceeds normally — which is the
correct outcome, since nothing was posted.

Storing the response body inside the same transaction is what makes a replay
byte-identical rather than a re-derivation that might differ.

## 4.4 Request fingerprinting

The fingerprint is `SHA-256` over a canonical form of the request: JSON keys
sorted, whitespace removed, numbers normalised, and volatile fields excluded
(`requestId`, client-supplied timestamps that do not affect the posting).

It answers one question: *is this the same request, or a different request that
happens to reuse the key?*

- Same key, same fingerprint → a retry. Replay the stored response.
- Same key, different fingerprint → a client bug (a reused UUID, a mutated
  payload). Reject with `409` and **do not** post. Silently applying the second
  request would be the worst outcome available: the caller believes both
  succeeded and exactly one did.

The endpoint and the authenticated principal are part of the primary key, so a
key scoped to one client can never interfere with another's, and a key used for
a transfer cannot short-circuit a reversal.

## 4.5 The `IN_PROGRESS` case

A row exists with `status = 'IN_PROGRESS'` when the original request is still
running (its transaction has not committed, so from another session's view the
row is invisible — or it committed the claim and crashed before completing).

Because the claim and the work share a transaction, an uncommitted claim is
invisible to other sessions; the second request's `INSERT` simply blocks on the
primary key until the first commits or rolls back, then proceeds correctly. The
visible `IN_PROGRESS` state therefore means the process died between commit
points, which cannot happen with a single transaction — it exists to make a
future asynchronous variant (roadmap: event outbox, long-running imports) safe
without changing the client contract.

The response is `409` with `Retry-After: 1` rather than a wait-and-poll, because
holding the connection open to wait for another request multiplies the timeouts
this mechanism exists to survive.

## 4.6 The second line of defence

`journal_entry.idempotency_key` carries its own partial unique index (§2.3).
If the idempotency store were bypassed, misconfigured, or wiped, the journal
itself still refuses the duplicate. The two mechanisms fail independently:

| Layer | Protects against | Failure mode if alone |
|---|---|---|
| `idempotency_record` PK | Concurrent and delayed retries; enables *replaying* the original response | If cleared by TTL, a very late retry could post again |
| `journal_entry` unique index | Any duplicate posting, ever | Returns a conflict, cannot reconstruct the original response |

Together: the store gives a good answer, the index guarantees a safe one.

## 4.7 Retention

Records expire after **24 hours** (`expires_at`), swept by a scheduled job every
15 minutes. The window comfortably exceeds any realistic client retry schedule
while keeping the table small.

After expiry the semantics degrade gracefully rather than dangerously: a retry
arriving on day three is not recognised as a replay, but the unique index on
`journal_entry.idempotency_key` still rejects it with `409`. The caller gets a
worse error message, never a duplicate posting.

## 4.8 Scope

Required on: `POST /journal-entries`, `POST /transfers`,
`POST /journal-entries/{id}/reversal`, `POST /reconciliations/{id}/breaks/{id}/resolve`.

Not applicable to `GET` (already idempotent) or to `POST /auth/login`
(intentionally repeatable). `POST /reconciliations` (statement upload) is
protected differently — by `UNIQUE (account_id, content_sha256)`, since the file
content is a natural idempotency key better than anything a client would invent.

`PATCH /accounts/{id}` uses optimistic locking via `If-Match` on a `version`
ETag instead. Idempotency keys answer "did this happen twice"; optimistic
locking answers "did someone else change this underneath me". Different
questions, different tools.

## 4.9 How this is proven

The claim "no endpoint posts twice under retry" is worth nothing as prose. It is
tested as behaviour, against a real Postgres via Testcontainers:

1. **Sequential retry** — same key twice, second returns `200` with
   `Idempotency-Replayed: true` and an identical body; `SELECT count(*)` on
   `journal_entry` is `1`.
2. **Concurrent storm** — 32 threads fire the identical request through a
   `CyclicBarrier` so they collide inside the same millisecond. Exactly one
   `201`, thirty-one replays, exactly one entry row. This is the test that
   would fail on a naive check-then-insert implementation.
3. **Key reuse with a different body** — `409`, and no second entry.
4. **Crash mid-transaction** — the ledger insert is forced to throw after the
   claim. The transaction rolls back, no record and no entry survive, and a
   subsequent retry succeeds cleanly with `201`.
5. **Post-expiry retry** — the record is aged past `expires_at` and swept; the
   retry is rejected by the journal's unique index rather than posting again.

Details in [Testing §7.4](07-testing.md#74-idempotency-under-concurrency).

---

Next: [Reconciliation](05-reconciliation.md).
