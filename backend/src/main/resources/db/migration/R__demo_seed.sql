-- R__demo_seed — Chart of accounts and demo journal for the public demo.
--
-- Repeatable: Flyway re-runs this whenever its checksum changes. Every statement
-- is therefore idempotent, and none of them delete or modify existing rows.
--
-- The journal is append-only, so demo entries are guarded on `external_ref`
-- rather than inserted blindly. Re-running this file adds nothing and changes
-- nothing; resetting the demo is done by restoring a Neon database branch, not
-- by deleting rows here. See docs/08-deployment.md §8.10 and ADR-0001.
--
-- Login users are NOT created here. Their bcrypt hashes come from environment
-- variables at boot, so a private deployment changes one variable rather than
-- one migration file (docs/08-deployment.md §8.8).

-- ---------------------------------------------------------------------------
-- The system principal
--
-- account.created_by and journal_entry.created_by are NOT NULL: seeded data needs
-- an author. The password hash is a sentinel that is not valid bcrypt, so no
-- password can ever authenticate as this user — it exists to be referenced, not
-- to log in.
-- ---------------------------------------------------------------------------

INSERT INTO app_user (id, email, display_name, password_hash, role, enabled)
VALUES ('00000000-0000-0000-0000-000000000001',
        'system@ledger.local', 'System (seed)', '!no-login', 'ADMIN', FALSE)
ON CONFLICT (email) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Chart of accounts
--
-- A small payments business: it produces every interesting reconciliation case
-- (receivables, a clearing account, fees) without requiring accounting knowledge
-- to read. See docs/01-domain-model.md §1.9.
-- ---------------------------------------------------------------------------

INSERT INTO account (code, name, type, currency, created_by)
VALUES
    ('1000', 'Cash at Bank',               'ASSET',     'EUR', '00000000-0000-0000-0000-000000000001'),
    ('1100', 'Payment Processor Clearing', 'ASSET',     'EUR', '00000000-0000-0000-0000-000000000001'),
    ('1200', 'Accounts Receivable',        'ASSET',     'EUR', '00000000-0000-0000-0000-000000000001'),
    ('2000', 'Accounts Payable',           'LIABILITY', 'EUR', '00000000-0000-0000-0000-000000000001'),
    ('2100', 'Customer Wallets Payable',   'LIABILITY', 'EUR', '00000000-0000-0000-0000-000000000001'),
    ('3000', 'Retained Earnings',          'EQUITY',    'EUR', '00000000-0000-0000-0000-000000000001'),
    ('4000', 'Revenue — Subscriptions',    'REVENUE',   'EUR', '00000000-0000-0000-0000-000000000001'),
    ('5000', 'Expense — Processor Fees',   'EXPENSE',   'EUR', '00000000-0000-0000-0000-000000000001'),
    ('9000', 'Reconciliation Differences', 'EXPENSE',   'EUR', '00000000-0000-0000-0000-000000000001')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Demo journal
--
-- Posted through a helper so each entry is written the way the application writes
-- it: entry first, then lines, with the deferred balance trigger verifying the
-- whole thing at COMMIT. The helper is a plain function, not a privileged path —
-- an unbalanced argument list fails exactly as an API call would.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION seed_entry(
    p_external_ref   TEXT,
    p_effective_date DATE,
    p_description    TEXT,
    p_lines          JSONB   -- [{"code":"1100","direction":"DEBIT","amount":9710,"memo":"…"}, …]
) RETURNS UUID AS $$
DECLARE
    v_entry_id UUID;
    v_line     JSONB;
    v_line_no  SMALLINT := 0;
BEGIN
    -- Idempotence: the journal is append-only, so a seeded entry is posted once
    -- and never again. Re-running returns the existing id.
    SELECT id INTO v_entry_id FROM journal_entry WHERE external_ref = p_external_ref;
    IF FOUND THEN
        RETURN v_entry_id;
    END IF;

    INSERT INTO journal_entry (
        effective_date, description, currency, source, external_ref, created_by, request_id
    ) VALUES (
        p_effective_date, p_description, 'EUR', 'SEED', p_external_ref,
        '00000000-0000-0000-0000-000000000001', 'seed:' || p_external_ref
    ) RETURNING id INTO v_entry_id;

    FOR v_line IN SELECT * FROM jsonb_array_elements(p_lines) LOOP
        v_line_no := v_line_no + 1;
        INSERT INTO journal_line (
            entry_id, line_no, account_id, direction, amount_minor, currency, memo, effective_date
        )
        SELECT v_entry_id,
               v_line_no,
               a.id,
               (v_line ->> 'direction')::direction,
               (v_line ->> 'amount')::BIGINT,
               a.currency,
               v_line ->> 'memo',
               p_effective_date
          FROM account a
         WHERE a.code = v_line ->> 'code';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'seed: unknown account code %', v_line ->> 'code';
        END IF;
    END LOOP;

    RETURN v_entry_id;
END;
$$ LANGUAGE plpgsql;

-- Opening balances, dated the day *before* the period they open.
--
-- Dated inside the period instead, they read to the reconciliation engine as an
-- ordinary June movement, while the bank statement treats the same money as an
-- opening balance. The bridge still closes — the two appear as equal and opposite
-- rows that cancel — but a report showing a 41,065.00 timing difference against a
-- 41,065.00 opening mismatch looks like a defect even though the arithmetic is
-- right. Demo data is read by people, so it should not need that explanation.
SELECT seed_entry('seed:opening-2026-06', DATE '2026-05-31', 'Opening balances', '[
    {"code": "1000", "direction": "DEBIT",  "amount": 4106500, "memo": "opening cash"},
    {"code": "3000", "direction": "CREDIT", "amount": 4106500, "memo": "opening equity"}
]'::jsonb);

-- A card settlement with a processor fee: one entry, three lines. The fee and the
-- settlement are the same event and must never be able to exist independently.
-- This is the case a two-line-only model gets wrong.
SELECT seed_entry('psp:pay_3Nk8Qz', DATE '2026-06-04', 'Card payment #4471 settled', '[
    {"code": "1100", "direction": "DEBIT",  "amount":  9710, "memo": "net settlement"},
    {"code": "5000", "direction": "DEBIT",  "amount":   290, "memo": "processor fee"},
    {"code": "4000", "direction": "CREDIT", "amount": 10000, "memo": "subscription revenue"}
]'::jsonb);

SELECT seed_entry('psp:payout_88213', DATE '2026-06-06', 'Processor payout to bank', '[
    {"code": "1000", "direction": "DEBIT",  "amount": 9710, "memo": "payout received"},
    {"code": "1100", "direction": "CREDIT", "amount": 9710, "memo": "clearing settled"}
]'::jsonb);

SELECT seed_entry('inv:2026-0142', DATE '2026-06-09', 'Invoice #2026-0142 issued', '[
    {"code": "1200", "direction": "DEBIT",  "amount": 250000, "memo": "ACME Srl"},
    {"code": "4000", "direction": "CREDIT", "amount": 250000, "memo": "annual subscription"}
]'::jsonb);

SELECT seed_entry('bank:ct-ACME-4471', DATE '2026-06-12', 'ACME Srl settles invoice #2026-0142', '[
    {"code": "1000", "direction": "DEBIT",  "amount": 250000, "memo": "SEPA credit transfer"},
    {"code": "1200", "direction": "CREDIT", "amount": 250000, "memo": "receivable cleared"}
]'::jsonb);

SELECT seed_entry('sup:INV-7781', DATE '2026-06-18', 'Hosting invoice INV-7781 booked', '[
    {"code": "5000", "direction": "DEBIT",  "amount": 48000, "memo": "infrastructure"},
    {"code": "2000", "direction": "CREDIT", "amount": 48000, "memo": "supplier payable"}
]'::jsonb);

SELECT seed_entry('bank:dd-7781', DATE '2026-06-20', 'Hosting invoice INV-7781 paid', '[
    {"code": "2000", "direction": "DEBIT",  "amount": 48000, "memo": "payable settled"},
    {"code": "1000", "direction": "CREDIT", "amount": 48000, "memo": "direct debit"}
]'::jsonb);

-- Deliberate duplicate: the same PSP settlement booked twice. Reconciliation is
-- expected to classify this as DUPLICATE_IN_LEDGER, and resolving it posts a
-- reversal rather than deleting anything.
SELECT seed_entry('psp:pay_3Nk8Qz#dup', DATE '2026-06-24', 'Card payment #4471 settled (duplicate)', '[
    {"code": "1100", "direction": "DEBIT",  "amount":  9710, "memo": "net settlement"},
    {"code": "5000", "direction": "DEBIT",  "amount":   290, "memo": "processor fee"},
    {"code": "4000", "direction": "CREDIT", "amount": 10000, "memo": "subscription revenue"}
]'::jsonb);

-- The payable the transfer below settles. Booked first so the liability account
-- closes the period at zero: a payable paid before it was ever recorded would
-- leave account 2000 with a debit balance, which is not wrong so much as it is
-- someone else's bug for the demo to explain.
SELECT seed_entry('sup:INV-7802', DATE '2026-06-27', 'Support retainer INV-7802 booked', '[
    {"code": "5000", "direction": "DEBIT",  "amount": 15000, "memo": "support retainer"},
    {"code": "2000", "direction": "CREDIT", "amount": 15000, "memo": "supplier payable"}
]'::jsonb);

-- Effective 2026-06-30 but cleared by the bank on 2026-07-02: a timing
-- difference, not an error. Reporting it as "missing" would send an operator
-- chasing a transaction that is fine.
SELECT seed_entry('bank:ct-late-4033', DATE '2026-06-30', 'Supplier transfer initiated', '[
    {"code": "2000", "direction": "DEBIT",  "amount": 15000, "memo": "payable settled"},
    {"code": "1000", "direction": "CREDIT", "amount": 15000, "memo": "clears 2026-07-02"}
]'::jsonb);

DROP FUNCTION IF EXISTS seed_entry(TEXT, DATE, TEXT, JSONB);
