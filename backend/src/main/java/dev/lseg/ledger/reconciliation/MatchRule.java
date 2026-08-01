package dev.lseg.ledger.reconciliation;

/**
 * The passes, in order of decreasing certainty.
 *
 * <p>They are never revisited: a later, weaker rule can only claim what the
 * stronger ones have already declined. {@code FUZZY_DESCRIPTION} is a heuristic
 * and is allowed to be one precisely because everything exact has been tried.
 */
public enum MatchRule {
    EXACT_REFERENCE(1.000),
    EXACT_AMOUNT_DATE(0.950),
    AMOUNT_DATE_WINDOW(0.800),
    FUZZY_DESCRIPTION(0.650),
    MANUAL(1.000);

    private final double confidence;

    MatchRule(double confidence) {
        this.confidence = confidence;
    }

    public double confidence() {
        return confidence;
    }
}
