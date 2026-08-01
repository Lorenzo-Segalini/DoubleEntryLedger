package dev.lseg.ledger.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh and logout.
 *
 * <p>See ADR-0007. The interesting part is {@link #refresh}: refresh tokens
 * rotate, and presenting one that has already been rotated revokes the whole
 * family.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AuditService audit;
    private final Duration refreshTtl;

    /**
     * A bcrypt hash of a value nobody knows, used to keep the work factor in play
     * when the email does not exist. Without it, "unknown user" returns in
     * microseconds and "wrong password" in ~200 ms, and the difference tells an
     * attacker which emails are registered.
     */
    private final String dummyHash;

    AuthenticationService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwords,
            JwtService jwt,
            AuditService audit,
            @Value("${ledger.jwt.refresh-token-ttl:P7D}") Duration refreshTtl) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.audit = audit;
        this.refreshTtl = refreshTtl;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Tokens login(String email, String password, RequestMetadata metadata) {
        Optional<AppUser> found = users.findByEmail(email == null ? "" : email.trim());

        if (found.isEmpty() || !found.get().enabled()) {
            passwords.matches(password == null ? "" : password, dummyHash);
            audit.loginDenied(email, metadata, found.isEmpty() ? "unknown-email" : "disabled");
            throw new AuthenticationFailedException();
        }

        AppUser user = found.get();
        if (!passwords.matches(password == null ? "" : password, user.passwordHash())) {
            audit.loginDenied(email, metadata, "bad-password");
            throw new AuthenticationFailedException();
        }

        UUID familyId = UUID.randomUUID();
        Tokens tokens = issue(user, familyId, metadata);
        audit.loginSucceeded(user, metadata);
        return tokens;
    }

    /**
     * Rotates a refresh token.
     *
     * <p>Every refresh mints a new token and marks the old one used. So a token
     * that is presented twice means two parties hold the same credential — the
     * legitimate client and whoever copied it — and there is no way to tell which
     * one is calling. The only safe response is to revoke the family and make both
     * log in again, which is the standard detection for a stolen refresh token.
     */
    /**
     * {@code noRollbackFor} is load-bearing, not a convenience. Detecting reuse
     * revokes the family and then rejects the call — and a plain
     * {@code @Transactional} would roll the revocation back on the way out,
     * leaving the attacker's session alive while the logs claimed it had been
     * killed. The revocation is a deliberate consequence of the rejection and
     * must outlive it.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public Tokens refresh(String presentedToken, RequestMetadata metadata) {
        byte[] hash = hash(presentedToken);
        RefreshToken stored = refreshTokens.findByHash(hash).orElseThrow(AuthenticationFailedException::new);

        if (stored.isUsed() || stored.isRevoked()) {
            int revoked = refreshTokens.revokeFamily(stored.familyId());
            log.warn(
                    "refresh token reuse detected for user {}; revoked {} token(s) in family {}",
                    stored.userId(),
                    revoked,
                    stored.familyId());
            audit.refreshTokenReuseDetected(stored.userId(), stored.familyId(), metadata);
            throw new AuthenticationFailedException();
        }

        if (stored.expiresAt().isBefore(java.time.Instant.now())) {
            throw new AuthenticationFailedException();
        }

        AppUser user = users.findById(stored.userId())
                .filter(AppUser::enabled)
                .orElseThrow(AuthenticationFailedException::new);

        Tokens tokens = issue(user, stored.familyId(), metadata);
        refreshTokens.markUsed(stored.id(), tokens.refreshTokenId());
        audit.refreshed(user, metadata);
        return tokens;
    }

    @Transactional
    public void logout(String presentedToken, RequestMetadata metadata) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        refreshTokens.findByHash(hash(presentedToken)).ifPresent(token -> {
            // The whole family, not just this token: logging out on one device
            // should not leave a rotated sibling alive.
            refreshTokens.revokeFamily(token.familyId());
            audit.loggedOut(token.userId(), metadata);
        });
    }

    @Transactional
    public int revokeAllSessions(UUID userId) {
        return refreshTokens.revokeAllForUser(userId);
    }

    private Tokens issue(AppUser user, UUID familyId, RequestMetadata metadata) {
        JwtService.IssuedToken access = jwt.issue(user);

        // 256 bits of entropy, opaque, never stored in the clear.
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refreshValue = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        UUID refreshId = refreshTokens.store(
                familyId, user.id(), hash(refreshValue), refreshTtl, metadata.userAgent(), metadata.ipAddress());

        return new Tokens(access, refreshValue, refreshId, refreshTtl, user);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record Tokens(
            JwtService.IssuedToken accessToken,
            String refreshToken,
            UUID refreshTokenId,
            Duration refreshTtl,
            AppUser user) {}

    public record RequestMetadata(String userAgent, String ipAddress) {
        public static RequestMetadata none() {
            return new RequestMetadata(null, null);
        }
    }

    /**
     * One exception for every authentication failure.
     *
     * <p>Deliberately carries no detail. "No such user" and "wrong password" are
     * the same answer to a caller; telling them apart is an account enumeration
     * oracle. The distinction is recorded in the audit log, where it belongs.
     */
    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException() {
            super("invalid credentials");
        }
    }
}
