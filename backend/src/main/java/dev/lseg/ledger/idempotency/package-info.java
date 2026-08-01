/**
 * At-most-once semantics for write endpoints.
 *
 * <p>Correctness rests on the primary key of {@code idempotency_record}, not on a
 * check-then-insert: the claim, the ledger write and the stored response share one
 * transaction. See {@code docs/04-idempotency.md} and ADR-0004.
 */
package dev.lseg.ledger.idempotency;
