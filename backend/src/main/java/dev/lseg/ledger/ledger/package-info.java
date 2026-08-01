/**
 * Posting service, reversals, and the balance and movement queries.
 *
 * <p>Balances are derived from {@code journal_line}, never stored — see ADR-0003.
 * All reads go through a single {@code BalanceQuery} interface so the snapshot
 * optimisation on the roadmap is one implementation rather than a refactor.
 */
package dev.lseg.ledger.ledger;
