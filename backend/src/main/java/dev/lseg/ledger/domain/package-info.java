/**
 * Entities, value objects and the invariants of double-entry bookkeeping.
 *
 * <p>Framework-free by design: an ArchUnit rule forbids any dependency on Spring
 * from this package, so the accounting rules can be read and tested in isolation.
 * See {@code docs/01-domain-model.md}.
 */
package dev.lseg.ledger.domain;
