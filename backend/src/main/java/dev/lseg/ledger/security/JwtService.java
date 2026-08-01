package dev.lseg.ledger.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues access tokens.
 *
 * <p>Short-lived on purpose. A stateless JWT cannot be revoked before it expires,
 * so the lifetime <em>is</em> the blast radius of a leaked token. Fifteen minutes
 * is the trade: long enough that refreshing is not constant, short enough that a
 * stolen token is worth little. Immediate revocation is the refresh family's job.
 */
@Service
public class JwtService {

    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;
    private final String issuer;

    JwtService(
            JwtEncoder encoder,
            Clock clock,
            @Value("${ledger.jwt.access-token-ttl:PT15M}") Duration ttl,
            @Value("${ledger.jwt.issuer:double-entry-ledger}") String issuer) {
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = ttl;
        this.issuer = issuer;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                // The subject is the user id, not the email: an email can change
                // and journal_entry.created_by must stay resolvable forever.
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expiry)
                .claim(ROLE_CLAIM, user.role().name())
                .claim("email", user.email())
                .claim("name", user.displayName())
                .build();

        String value = encoder.encode(
                        JwtEncoderParameters.from(JwsHeader.with(() -> "RS256").build(), claims))
                .getTokenValue();

        return new IssuedToken(value, expiry, ttl.toSeconds());
    }

    public Duration ttl() {
        return ttl;
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {}
}
