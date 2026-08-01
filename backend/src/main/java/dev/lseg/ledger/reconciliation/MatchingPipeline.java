package dev.lseg.ledger.reconciliation;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.lseg.ledger.domain.AccountType;

/**
 * Pairs statement lines with journal movements.
 *
 * <p>Four passes over what the previous pass left unmatched, ordered by
 * decreasing certainty and never revisited. Three rules keep the output
 * trustworthy:
 *
 * <ul>
 *   <li><strong>A match is one-to-one.</strong> Neither side can be consumed
 *       twice. Without that, a single ledger line can "explain" two statement
 *       lines and the difference silently stops adding up.
 *   <li><strong>Ambiguity is never resolved by guessing.</strong> If a pass finds
 *       more than one candidate at the same quality, nothing is matched and both
 *       fall through. A wrong automatic match costs far more than an unmatched
 *       line, because it looks finished.
 *   <li><strong>The run is deterministic.</strong> Candidates are ordered
 *       explicitly, so the same inputs produce the same matches. A reconciliation
 *       you cannot reproduce is not evidence of anything.
 * </ul>
 */
@Component
public class MatchingPipeline {

    private static final int WINDOW_DAYS = 3;
    private static final int FUZZY_WINDOW_DAYS = 7;
    private static final double FUZZY_THRESHOLD = 0.55;

    public Result match(List<StatementLine> statementLines, List<JournalMovement> movements, AccountType accountType) {

        List<StatementLine> statement = statementLines.stream()
                .sorted(Comparator.comparingInt(StatementLine::rowNo))
                .toList();
        List<JournalMovement> journal = movements.stream()
                .sorted(Comparator.comparing(JournalMovement::effectiveDate)
                        .thenComparingLong(JournalMovement::sequenceNo)
                        .thenComparing(JournalMovement::journalLineId))
                .toList();

        List<Pairing> pairings = new ArrayList<>();
        Set<Integer> takenStatement = new HashSet<>();
        Set<UUID> takenJournal = new HashSet<>();

        for (MatchRule rule : List.of(
                MatchRule.EXACT_REFERENCE,
                MatchRule.EXACT_AMOUNT_DATE,
                MatchRule.AMOUNT_DATE_WINDOW,
                MatchRule.FUZZY_DESCRIPTION)) {

            for (StatementLine line : statement) {
                if (takenStatement.contains(line.rowNo())) {
                    continue;
                }
                long ledgerSigned = StatementSignConvention.toLedgerSigned(line.amountMinor(), accountType);

                List<JournalMovement> candidates = journal.stream()
                        .filter(movement -> !takenJournal.contains(movement.journalLineId()))
                        .filter(movement -> matches(rule, line, ledgerSigned, movement))
                        .toList();

                // Exactly one, or nothing. Two equally good candidates is a
                // question for an operator, not a coin toss.
                if (candidates.size() != 1) {
                    continue;
                }

                JournalMovement matched = candidates.getFirst();
                pairings.add(new Pairing(line, matched, rule, confidenceOf(rule, line, matched)));
                takenStatement.add(line.rowNo());
                takenJournal.add(matched.journalLineId());
            }
        }

        List<StatementLine> unmatchedStatement = statement.stream()
                .filter(l -> !takenStatement.contains(l.rowNo()))
                .toList();
        List<JournalMovement> unmatchedJournal = journal.stream()
                .filter(m -> !takenJournal.contains(m.journalLineId()))
                .toList();

        return new Result(pairings, unmatchedStatement, unmatchedJournal);
    }

    private boolean matches(MatchRule rule, StatementLine line, long ledgerSigned, JournalMovement movement) {
        return switch (rule) {
            case EXACT_REFERENCE -> line.externalId() != null
                    && Objects.equals(line.externalId(), movement.externalRef())
                    && movement.signedAmountMinor() == ledgerSigned;

            case EXACT_AMOUNT_DATE -> movement.signedAmountMinor() == ledgerSigned
                    && movement.effectiveDate().equals(line.valueDate());

            case AMOUNT_DATE_WINDOW -> movement.signedAmountMinor() == ledgerSigned
                    && withinDays(line, movement, WINDOW_DAYS);

            case FUZZY_DESCRIPTION -> movement.signedAmountMinor() == ledgerSigned
                    && withinDays(line, movement, FUZZY_WINDOW_DAYS)
                    && TrigramSimilarity.between(line.description(), movement.description()) >= FUZZY_THRESHOLD;

            case MANUAL -> false;
        };
    }

    private static boolean withinDays(StatementLine line, JournalMovement movement, int days) {
        return Math.abs(ChronoUnit.DAYS.between(line.valueDate(), movement.effectiveDate())) <= days;
    }

    private static double confidenceOf(MatchRule rule, StatementLine line, JournalMovement movement) {
        if (rule != MatchRule.FUZZY_DESCRIPTION) {
            return rule.confidence();
        }
        // Report what the heuristic actually scored, not a flat value: an operator
        // reviewing a fuzzy match deserves to know how close it really was.
        double similarity = TrigramSimilarity.between(line.description(), movement.description());
        return Math.min(0.750, Math.max(0.550, similarity));
    }

    public record Pairing(StatementLine statementLine, JournalMovement movement, MatchRule rule, double confidence) {}

    public record Result(
            List<Pairing> pairings, List<StatementLine> unmatchedStatement, List<JournalMovement> unmatchedJournal) {}
}
