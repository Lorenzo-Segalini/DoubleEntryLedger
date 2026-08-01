package dev.lseg.ledger.domain;

/**
 * Every way a ledger operation can be refused.
 *
 * <p>A closed enum rather than an exception class per case: the API layer maps
 * these exhaustively onto RFC 9457 problem types, so adding a failure mode
 * without deciding how callers see it is a compile error rather than an
 * accidental 500.
 *
 * <p>{@code problemType} is the slug under {@code /problems/} in the published
 * error document. See docs/03-api.md §3.1.
 */
public enum LedgerError {
    UNBALANCED_ENTRY("unbalanced-entry", 422),
    INSUFFICIENT_LINES("insufficient-lines", 422),
    NON_POSITIVE_AMOUNT("non-positive-amount", 422),
    MIXED_CURRENCY_ENTRY("mixed-currency-entry", 422),
    CURRENCY_MISMATCH("currency-mismatch", 422),
    UNKNOWN_ACCOUNT("unknown-account", 422),
    ACCOUNT_ARCHIVED("account-archived", 422),
    POSTDATED_ENTRY("postdated-entry", 422),
    BLANK_DESCRIPTION("blank-description", 422),
    DUPLICATE_LINE_ACCOUNT("duplicate-line-account", 422),
    ENTRY_NOT_FOUND("entry-not-found", 404),
    ALREADY_REVERSED("already-reversed", 409),
    REVERSAL_OF_REVERSAL("reversal-of-reversal", 422);

    private final String problemType;
    private final int httpStatus;

    LedgerError(String problemType, int httpStatus) {
        this.problemType = problemType;
        this.httpStatus = httpStatus;
    }

    public String problemType() {
        return problemType;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
