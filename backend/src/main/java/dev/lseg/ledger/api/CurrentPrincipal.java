package dev.lseg.ledger.api;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import dev.lseg.ledger.security.AppRole;
import dev.lseg.ledger.security.JwtService;
import dev.lseg.ledger.security.LedgerUserDetails;

/**
 * Who is making the request, read from the verified access token.
 *
 * <p>The subject claim is the user id, which is what lands on
 * {@code journal_entry.created_by}. An email would have been friendlier to read
 * and wrong to store: emails change, and a posting's author must stay resolvable
 * for as long as the entry exists.
 */
@Component
public class CurrentPrincipal {

    public Optional<LedgerUserDetails> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(new LedgerUserDetails(
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaimAsString("email"),
                    AppRole.valueOf(jwt.getClaimAsString(JwtService.ROLE_CLAIM))));
        }

        // Tests that populate the context directly rather than through a token.
        return authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .map(a -> new LedgerUserDetails(
                        UUID.nameUUIDFromBytes(authentication.getName().getBytes()),
                        authentication.getName(),
                        AppRole.valueOf(a.substring("ROLE_".length()))));
    }

    public LedgerUserDetails require() {
        return current()
                .orElseThrow(() -> new IllegalStateException(
                        "no authenticated principal; this endpoint should be behind authentication"));
    }

    public UUID id() {
        return require().id();
    }
}
