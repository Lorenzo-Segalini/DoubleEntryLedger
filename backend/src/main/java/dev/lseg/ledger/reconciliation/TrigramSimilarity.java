package dev.lseg.ledger.reconciliation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Jaccard similarity over trigrams, following PostgreSQL's {@code pg_trgm}.
 *
 * <p>Computed in the application rather than in SQL so the matching pipeline is
 * one deterministic function over loaded collections, testable without a database
 * and without a round trip per candidate pair. The {@code pg_trgm} GIN index
 * remains, and serves description search in the back office.
 */
final class TrigramSimilarity {

    private TrigramSimilarity() {}

    static double between(String left, String right) {
        if (left == null || right == null) {
            return 0.0;
        }
        Set<String> a = trigrams(left);
        Set<String> b = trigrams(right);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);

        int union = a.size() + b.size() - shared.size();
        return union == 0 ? 0.0 : (double) shared.size() / union;
    }

    /** Words padded as pg_trgm does: two leading spaces and one trailing. */
    private static Set<String> trigrams(String text) {
        String normalised =
                text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        Set<String> result = new HashSet<>();
        for (String word : normalised.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                result.add(padded.substring(i, i + 3));
            }
        }
        return result;
    }
}
