package dev.lseg.ledger.reconciliation;

/**
 * <pre>
 * OPEN ──explain──▶ EXPLAINED ──resolve──▶ RESOLVED
 *   │                    │
 *   └────write off───────┴──────────────▶ WRITTEN_OFF
 * </pre>
 *
 * <p>{@code EXPLAINED} moves no money — correct for a timing difference, or for
 * anything awaiting a counterparty. {@code RESOLVED} means an adjusting entry was
 * posted through the ordinary posting service.
 */
public enum BreakStatus {
    OPEN,
    EXPLAINED,
    RESOLVED,
    WRITTEN_OFF;

    /** Open and explained breaks still describe a live difference, so both count in the bridge. */
    public boolean countsTowardsTheBridge() {
        return this == OPEN || this == EXPLAINED;
    }
}
