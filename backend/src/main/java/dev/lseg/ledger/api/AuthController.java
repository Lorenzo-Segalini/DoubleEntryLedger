package dev.lseg.ledger.api;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.lseg.ledger.security.AppRole;
import dev.lseg.ledger.security.AuthenticationService;
import dev.lseg.ledger.security.LedgerUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication")
class AuthController {

    static final String REFRESH_COOKIE = "ledger_refresh";

    private final AuthenticationService auth;
    private final CurrentPrincipal principal;
    private final boolean secureCookie;

    AuthController(
            AuthenticationService auth,
            CurrentPrincipal principal,
            @Value("${ledger.jwt.cookie-secure:true}") boolean secureCookie) {
        this.auth = auth;
        this.principal = principal;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Exchange credentials for an access token",
            description = "The refresh token is returned as an HttpOnly cookie, not in the body.")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        var tokens = auth.login(request.email(), request.password(), metadata(http));
        return respond(tokens);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate the refresh token",
            description = "Presenting an already-rotated token revokes the whole family.")
    ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthenticationService.AuthenticationFailedException();
        }
        return respond(auth.refresh(refreshToken, metadata(http)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session's refresh token family")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {

        auth.logout(refreshToken, metadata(http));
        // Cleared unconditionally: logging out must not depend on the server
        // recognising the token being logged out.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated caller and what they may do")
    MeResponse me() {
        LedgerUserDetails user = principal.require();
        return new MeResponse(user.id(), user.email(), null, user.role().name(), permissionsOf(user.role()));
    }

    private ResponseEntity<TokenResponse> respond(AuthenticationService.Tokens tokens) {
        TokenResponse body = new TokenResponse(
                tokens.accessToken().value(),
                "Bearer",
                tokens.accessToken().expiresInSeconds(),
                tokens.accessToken().expiresAt(),
                tokens.user().email(),
                tokens.user().displayName(),
                tokens.user().role().name());

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(tokens.refreshToken(), tokens.refreshTtl())
                                .toString())
                .body(body);
    }

    /**
     * {@code HttpOnly} so injected script cannot read it, {@code SameSite=Strict}
     * so it never rides along on a cross-site request, and scoped to the refresh
     * path so it is not attached to ordinary API calls that have no use for it.
     *
     * <p>{@code Secure} is configurable only so local development over plain HTTP
     * works; it defaults to true and stays true everywhere else.
     */
    private ResponseCookie refreshCookie(String value, Duration ttl) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(ttl)
                .build();
    }

    private static AuthenticationService.RequestMetadata metadata(HttpServletRequest http) {
        return new AuthenticationService.RequestMetadata(http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }

    /** What the UI uses to hide controls. The server enforces the same rules independently. */
    private static List<String> permissionsOf(AppRole role) {
        return switch (role) {
            case AUDITOR -> List.of("ledger:read");
            case OPERATOR -> List.of("ledger:read", "ledger:post", "reconciliation:write");
            case ADMIN -> List.of("ledger:read", "ledger:post", "reconciliation:write", "admin:manage");
        };
    }
}
