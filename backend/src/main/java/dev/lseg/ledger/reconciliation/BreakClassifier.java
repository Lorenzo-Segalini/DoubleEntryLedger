package dev.lseg.ledger.reconciliation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import dev.lseg.ledger.domain.AccountType;

/**
 * Turns what the pipeline could not match into typed, signed differences.
 *
 * <p>Every break carries a {@code deltaMinor} in the ledger's signed convention,
 * defined so that
 *
 * <pre>ledger_closing + Σ(delta) = statement_closing</pre>
 *
 * <p>Read the sign as "what this break contributes to moving the ledger's number
 * onto the statement's". A movement the ledger has and the statement does not
 * contributes the negative of itself; one the statement has and the ledger does
 * not contributes itself.
 *
 * <p>Classification only ever changes the <em>label</em> and the detail, never
 * the delta. A timing difference contributes exactly what any other unmatched
 * journal line contributes — it is a real difference between the two documents.
 * What its type says is that no correcting entry is needed, which is advice to an
 * operator, not an adjustment to the arithmetic.
 */
@Component
public class BreakClassifier {

    /** How close to the cut-off an unmatched movement has to be to read as timing rather than error. */
    private static final int CUTOFF_WINDOW_DAYS = 5;

    public List<ClassifiedBreak> classify(
            MatchingPipeline.Result matching,
            AccountType accountType,
            String accountCurrency,
            LocalDate periodStart,
            LocalDate periodEnd) {

        List<ClassifiedBreak> breaks = new ArrayList<>();

        breaks.addAll(fromStatement(matching, accountType, accountCurrency));
        breaks.addAll(fromJournal(matching, periodStart, periodEnd));
        breaks.addAll(fromAmountMismatches(matching, accountType));

        return breaks;
    }

    private List<ClassifiedBreak> fromStatement(
            MatchingPipeline.Result matching, AccountType accountType, String accountCurrency) {

        // A repeated (date, amount, description) inside one statement is the bank
        // reporting the same movement twice. The first occurrence is treated as
        // genuine; the rest are duplicates.
        Map<String, Integer> seen = new HashMap<>();
        List<ClassifiedBreak> breaks = new ArrayList<>();

        for (StatementLine line : matching.unmatchedStatement()) {
            long delta = StatementSignConvention.toLedgerSigned(line.amountMinor(), accountType);

            if (!line.currency().equals(accountCurrency)) {
                breaks.add(new ClassifiedBreak(
                        BreakType.CURRENCY_MISMATCH,
                        // Zero, because an amount in another currency cannot be
                        // added to this account's balance at all. It needs a rate
                        // and a decision, which is phase 3.
                        0L,
                        line,
                        null,
                        Map.of(
                                "statementCurrency", line.currency(),
                                "accountCurrency", accountCurrency,
                                "amountMinor", line.amountMinor())));
                continue;
            }

            String fingerprint = line.valueDate() + "|" + line.amountMinor() + "|" + line.description();
            int occurrence = seen.merge(fingerprint, 1, Integer::sum);

            breaks.add(new ClassifiedBreak(
                    occurrence > 1 ? BreakType.DUPLICATE_IN_STATEMENT : BreakType.MISSING_IN_LEDGER,
                    delta,
                    line,
                    null,
                    Map.of(
                            "valueDate", line.valueDate().toString(),
                            "description", line.description(),
                            "amountMinor", line.amountMinor(),
                            "occurrence", occurrence)));
        }
        return breaks;
    }

    private List<ClassifiedBreak> fromJournal(
            MatchingPipeline.Result matching, LocalDate periodStart, LocalDate periodEnd) {

        // A movement identical to one that *did* match is the same fact booked
        // twice — the duplicate, not the original, is the problem.
        Map<String, Long> matchedFingerprints = new HashMap<>();
        for (MatchingPipeline.Pairing pairing : matching.pairings()) {
            matchedFingerprints.merge(fingerprintOf(pairing.movement()), 1L, Long::sum);
        }

        Map<String, Integer> seenUnmatched = new HashMap<>();
        List<ClassifiedBreak> breaks = new ArrayList<>();

        for (JournalMovement movement : matching.unmatchedJournal()) {
            long delta = -movement.signedAmountMinor();
            String fingerprint = fingerprintOf(movement);
            int occurrence = seenUnmatched.merge(fingerprint, 1, Integer::sum);

            BreakType type;
            if (matchedFingerprints.containsKey(fingerprint) || occurrence > 1) {
                type = BreakType.DUPLICATE_IN_LEDGER;
            } else if (nearCutoff(movement.effectiveDate(), periodStart, periodEnd)) {
                type = BreakType.TIMING_DIFFERENCE;
            } else {
                type = BreakType.MISSING_IN_STATEMENT;
            }

            breaks.add(new ClassifiedBreak(
                    type,
                    delta,
                    null,
                    movement,
                    Map.of(
                            "effectiveDate", movement.effectiveDate().toString(),
                            "description", movement.description(),
                            "sequenceNo", movement.sequenceNo(),
                            "signedAmountMinor", movement.signedAmountMinor())));
        }
        return breaks;
    }

    /**
     * Matched pairs whose amounts disagree.
     *
     * <p>The pipeline only pairs equal amounts, so this is empty today. It exists
     * because a manual match — an operator asserting that two rows are the same
     * movement despite a difference — produces exactly this, and the bridge has to
     * account for it when it does.
     */
    private List<ClassifiedBreak> fromAmountMismatches(MatchingPipeline.Result matching, AccountType accountType) {
        List<ClassifiedBreak> breaks = new ArrayList<>();
        for (MatchingPipeline.Pairing pairing : matching.pairings()) {
            long statementSigned = StatementSignConvention.toLedgerSigned(
                    pairing.statementLine().amountMinor(), accountType);
            long difference = statementSigned - pairing.movement().signedAmountMinor();

            if (difference != 0) {
                breaks.add(new ClassifiedBreak(
                        BreakType.AMOUNT_MISMATCH,
                        difference,
                        pairing.statementLine(),
                        pairing.movement(),
                        Map.of(
                                "statementAmountMinor", statementSigned,
                                "journalAmountMinor", pairing.movement().signedAmountMinor(),
                                "differenceMinor", difference)));
            }
        }
        return breaks;
    }

    private static boolean nearCutoff(LocalDate effectiveDate, LocalDate periodStart, LocalDate periodEnd) {
        return !effectiveDate.isBefore(periodEnd.minusDays(CUTOFF_WINDOW_DAYS))
                || !effectiveDate.isAfter(periodStart.plusDays(CUTOFF_WINDOW_DAYS));
    }

    private static String fingerprintOf(JournalMovement movement) {
        return Objects.requireNonNullElse(movement.externalRef(), movement.description())
                + "|"
                + movement.signedAmountMinor();
    }

    public record ClassifiedBreak(
            BreakType type,
            long deltaMinor,
            StatementLine statementLine,
            JournalMovement movement,
            Map<String, Object> detail) {}
}
