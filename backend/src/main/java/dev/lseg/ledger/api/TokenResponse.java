package dev.lseg.ledger.api;

import java.time.Instant;

/**
 * The access token and who it belongs to.
 *
 * <p>The refresh token is deliberately absent: it goes back as an
 * {@code HttpOnly} cookie the browser's JavaScript cannot read. Putting it in the
 * body would undo that in one line.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        String email,
        String displayName,
        String role) {}
