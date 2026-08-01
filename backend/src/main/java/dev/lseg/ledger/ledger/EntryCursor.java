package dev.lseg.ledger.ledger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;

/**
 * A position in the journal, as {@code (effectiveDate, sequenceNo)}.
 *
 * <p>Not an offset. Offset pagination over an append-only ledger is wrong in a
 * way that looks fine in testing: entries arrive while a user is reading, every
 * row shifts down, and page two repeats rows from page one or skips them
 * entirely. A cursor names a row rather than a count, so what follows it does not
 * depend on what was inserted before it.
 *
 * <p>The pair is the same one the index is built on, so seeking is a range scan
 * rather than a sort. {@code sequenceNo} breaks ties within a day, which matters
 * because several entries routinely share an effective date.
 *
 * <p>Encoded as base64 to say "opaque, do not construct these" — not as
 * security. A caller who decodes one learns a date and a sequence number they
 * could already read off the response.
 */
public record EntryCursor(LocalDate effectiveDate, long sequenceNo) {

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("%s|%d".formatted(effectiveDate, sequenceNo).getBytes(StandardCharsets.UTF_8));
    }

    public static EntryCursor decode(String encoded) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 2) {
                throw new IllegalArgumentException("expected two parts");
            }
            return new EntryCursor(LocalDate.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // A malformed cursor is a client bug, not a reason to silently start
            // from the beginning — that would look like the list resetting itself.
            throw new LedgerException(
                    LedgerError.INVALID_CURSOR, "the cursor is not one this API issued", Map.of("cursor", encoded));
        }
    }
}
