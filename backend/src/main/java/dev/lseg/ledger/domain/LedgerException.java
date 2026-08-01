package dev.lseg.ledger.domain;

import java.util.Map;
import java.util.Objects;

/**
 * A ledger rule was violated.
 *
 * <p>One exception type carrying a {@link LedgerError}, rather than a class per
 * failure: callers switch on the enum, and the API layer maps it to a status and
 * a problem type in one place.
 *
 * <p>{@code details} carries the values a caller needs to fix the request — the
 * amount an entry is out by, the account code that does not exist — so the error
 * message does not have to be parsed to be useful.
 */
public class LedgerException extends RuntimeException {

    private final transient LedgerError error;
    private final transient Map<String, Object> details;

    public LedgerException(LedgerError error, String message) {
        this(error, message, Map.of());
    }

    public LedgerException(LedgerError error, String message, Map<String, Object> details) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
        this.details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    public LedgerError error() {
        return error;
    }

    public Map<String, Object> details() {
        return details;
    }
}
