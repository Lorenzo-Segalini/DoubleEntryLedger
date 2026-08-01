/**
 * Statement import, the four-pass matching pipeline, and typed breaks.
 *
 * <p>Holds no privileged write path into the journal: resolving a break posts an
 * ordinary entry through the posting service. See {@code docs/05-reconciliation.md}.
 */
package dev.lseg.ledger.reconciliation;
