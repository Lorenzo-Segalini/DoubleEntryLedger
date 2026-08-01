# 5. Reconciliation

## 5.1 What "reconciled" has to mean

Most reconciliation tooling reports a number: *the ledger says 48,219.00 and the
bank says 48,104.50, difference 114.50*. That tells an operator that something
is wrong and nothing about what. The work — finding which transactions account
for the 114.50 — is left to a human with two spreadsheets.

The goal here is different:

> The report must **explain** the difference, line by line, such that the
> explanations sum exactly to the difference. If they do not, the reconciliation
> is not finished.

That turns reconciliation from a comparison into a proof. It is also what makes
the feature demonstrable: seeded data contains a deliberate duplicate, a timing
difference, a fee posted at the wrong amount, and a bank charge that was never
booked. The report identifies all four by type and shows how they add up.

## 5.2 Inputs

An operator uploads a statement for one account and one period:

```http
POST /api/v1/reconciliations
Content-Type: multipart/form-data

file:              june-2026-bank.csv
accountCode:       1000
periodStart:       2026-06-01
periodEnd:         2026-06-30
openingBalanceMinor: 4106500
closingBalanceMinor: 4810450
currency:          EUR
```

Expected CSV columns (a normaliser maps common bank layouts onto this shape):

```csv
value_date,amount,currency,description,external_id,counterparty_ref
2026-06-03,-250.00,EUR,"SEPA CT ACME SRL",TX-4471,IT60X0542811101
2026-06-04,1000.00,EUR,"CARD SETTLEMENT",TX-4472,PSP-3Nk8Qz
```

Amounts are signed **from the bank's perspective** — money leaving the account
is negative. The importer converts each to the ledger's signed convention for
the account being reconciled, which for an asset account means bank-positive is
a debit. Getting this mapping wrong inverts every match, so it is isolated in
one small class with a table-driven test per account type.

Two guards at import:

- `UNIQUE (account_id, content_sha256)` — re-uploading the same file returns the
  existing import rather than creating a second one. This is idempotency by
  natural key (§4.8).
- The statement's own arithmetic is checked first:
  `opening + Σ(lines) = closing`. If the file does not internally add up, the
  import fails with `422 statement-not-internally-consistent` before any
  matching runs. Reconciling against an inconsistent statement produces
  confident nonsense.

## 5.3 The matching pipeline

Four passes, each over what the previous pass left unmatched. Every match
records which rule produced it and a confidence score, so the back office can
show *why* two rows were considered the same.

| Pass | Rule | Criteria | Confidence |
|---|---|---|---|
| 1 | `EXACT_REFERENCE` | `statement.external_id = journal_entry.external_ref` and amounts equal | `1.000` |
| 2 | `EXACT_AMOUNT_DATE` | Amount equal, `value_date = effective_date`, unique candidate | `0.950` |
| 3 | `AMOUNT_DATE_WINDOW` | Amount equal, dates within ±3 days, unique candidate | `0.800` |
| 4 | `FUZZY_DESCRIPTION` | Amount equal, ±7 days, description trigram similarity ≥ 0.55 | `0.550`–`0.750` |

Design rules that keep the output trustworthy:

- **A match is one-to-one.** `UNIQUE (import_id, statement_line_id)` and
  `UNIQUE (import_id, journal_line_id)` mean neither side can be consumed twice.
  Without this a single ledger line can "explain" two statement lines and the
  difference silently stops adding up.
- **Ambiguity is not resolved by guessing.** If a pass finds more than one
  candidate at the same score, nothing is matched. Both remain unmatched into
  the next pass, and if they survive to the end they surface as a break the
  operator resolves by hand. A wrong automatic match is far more expensive than
  an unmatched line, because it looks finished.
- **Passes are ordered by decreasing certainty** and never revisited. Pass 4 uses
  `pg_trgm` similarity, which is a heuristic; it can only claim what the exact
  rules have already declined.
- **The whole run is deterministic.** Same input file, same journal state, same
  matches — asserted by a test that runs the pipeline twice and compares. A
  reconciliation you cannot reproduce is not evidence of anything.

## 5.4 The bridge invariant

Anything unmatched becomes a typed `reconciliation_break` carrying a signed
`delta_minor`. The types, and what each contributes to the difference:

| Break type | Meaning | `delta_minor` |
|---|---|---|
| `MISSING_IN_LEDGER` | On the statement, never booked (e.g. a bank fee) | `+statement.amount` |
| `MISSING_IN_STATEMENT` | Booked, never appeared at the bank | `−journal.amount` |
| `AMOUNT_MISMATCH` | Matched pair, different amounts | `statement − journal` |
| `TIMING_DIFFERENCE` | Booked in the period, expected to clear after it | `−journal.amount`, exactly as `MISSING_IN_STATEMENT` |
| `DUPLICATE_IN_LEDGER` | Same movement booked twice | `−duplicate.amount` |
| `DUPLICATE_IN_STATEMENT` | Bank reported it twice | `+duplicate.amount` |
| `CURRENCY_MISMATCH` | Statement line in a different currency | `0`, flagged for manual handling |

The invariant that binds them:

```
ledger_closing_balance + Σ(delta_minor over all OPEN and EXPLAINED breaks)
    = statement_closing_balance
```

This is checked at the end of every run and returned as `bridgeBalanced` in the
report. If it is `false`, the matching engine has a bug — a double-consumed
line, a sign error, a missed classification — and the report says so loudly
rather than presenting a plausible-looking list. It is also the property test in
[§7.5](07-testing.md#75-reconciliation-properties): for randomly generated
journals and statements with randomly injected discrepancies, the bridge must
close every time.

`TIMING_DIFFERENCE` deserves its own type rather than being folded into
`MISSING_IN_STATEMENT`. A payment made on 30 June and cleared on 2 July is not
an error and needs no correcting entry — it will resolve itself next period.
Reporting it as "missing" sends an operator chasing a transaction that is fine.
This is exactly the distinction that the two-date model in
[§1.5](01-domain-model.md#15-two-dates-deliberately) makes possible.

**But its delta is not zero.** Classification changes the label and the advice,
never the arithmetic: the payment really is in the ledger and really is not on
the statement, so it contributes what any other unmatched journal line
contributes. A type that read as a genuine difference while adding nothing to the
bridge would break the one invariant this feature exists to uphold. The type says
"no action needed"; the number says what it is worth.

The only delta that is genuinely zero is `CURRENCY_MISMATCH`, because an amount
in another currency cannot be added to this account's balance at all — it needs a
rate and a decision, which is [phase 3](09-roadmap.md#93-phase-3--full-multi-currency).

## 5.5 The report

```
GET /api/v1/reconciliations/{id}/report
```

```json
{
  "importId": "b21c...",
  "account": { "code": "1000", "name": "Cash at Bank" },
  "period": { "start": "2026-06-01", "end": "2026-06-30" },
  "currency": "EUR",
  "ledgerClosing":    { "amountMinor": 4821900, "currency": "EUR", "amount": "48219.00" },
  "statementClosing": { "amountMinor": 4825450, "currency": "EUR", "amount": "48254.50" },
  "difference":       { "amountMinor":    3550, "currency": "EUR", "amount": "35.50" },
  "matched":   { "count": 128, "amountMinor": 3910200 },
  "unmatched": { "statementLines": 2, "journalLines": 2 },
  "bridge": [
    { "breakId": "…", "type": "MISSING_IN_LEDGER",   "deltaMinor":  -1450,
      "detail": "Bank charge 14.50 EUR on 2026-06-28 not booked", "status": "OPEN" },
    { "breakId": "…", "type": "DUPLICATE_IN_LEDGER", "deltaMinor": -10000,
      "detail": "Entry #4012 and #4019 both book PSP settlement psp:pay_3Nk8Qz", "status": "OPEN" },
    { "breakId": "…", "type": "TIMING_DIFFERENCE",   "deltaMinor":  15000,
      "detail": "Booked 2026-06-30, expected to clear after the period end — no action needed",
      "status": "EXPLAINED" }
  ],
  "bridgeTotalMinor": 3550,
  "bridgeBalanced": true,
  "matchRate": 0.984,
  "generatedAt": "2026-08-01T10:31:00Z"
}
```

`difference` equals `bridgeTotalMinor`. That equality is the deliverable, and it
is asserted directly — not read off the `bridgeBalanced` flag, so a bug in the
flag cannot hide a bug in the arithmetic.

Only `OPEN` and `EXPLAINED` breaks contribute. A `RESOLVED` break has had an
adjusting entry posted against it, so the ledger already moved and counting it
again would double it.

## 5.6 Break lifecycle

```
OPEN ──explain──▶ EXPLAINED ──resolve──▶ RESOLVED
  │                    │
  └────write off───────┴──────────────▶ WRITTEN_OFF
```

- **`explain`** attaches a reason and no money moves. Correct for timing
  differences and for anything awaiting a counterparty.
- **`resolve`** posts an adjusting journal entry through the ordinary posting
  service — same validation, same idempotency requirement, same audit trail —
  and stores its id in `resolving_entry_id`. Its effective date defaults to the
  **period being reconciled**, not to today: an operator resolving a June break
  means "book this into June", and dating it now would leave June's difference
  open. That is accounting-correct and not what was asked, so an explicit
  `effectiveDate` is how you book into the current period instead. Reconciliation has **no privileged
  write path into the journal**. It cannot nudge a balance to make its own
  report come out even, which is the failure mode that makes some reconciliation
  tools worse than useless.
- **`write_off`** posts an entry to a designated difference account and closes
  the break, requiring `ADMIN` and a mandatory explanation.

Resolving a `DUPLICATE_IN_LEDGER` break is not an edit or a delete — it is a
reversal of the duplicate entry, produced through
`POST /journal-entries/{id}/reversal`. Every break resolution is therefore
itself visible in the journal and can be reconciled next period.

## 5.7 Where this runs

Import and matching run synchronously for demo-scale files (up to ~5,000 lines
completes well inside the request timeout), inside one transaction per import so
a failed run leaves no partial matches. Larger files would move to a job with
the import in `MATCHING` status and the client polling `GET /reconciliations/{id}`
— the status enum and the `IN_PROGRESS` idempotency case already anticipate
this, which is why they exist now (§4.5).

---

Next: [Frontend](06-frontend.md).
