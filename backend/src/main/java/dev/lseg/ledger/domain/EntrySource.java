package dev.lseg.ledger.domain;

/** How an entry came to exist. Part of the audit trail, never inferred. */
public enum EntrySource {
    API,
    TRANSFER,
    REVERSAL,
    IMPORT,
    ADJUSTMENT,
    SEED
}
