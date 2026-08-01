package dev.lseg.ledger.security;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.RSAKey;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Publishes the public half of the signing key.
 *
 * <p>Anything downstream can verify a token without being trusted to mint one —
 * the whole reason for RS256 over a shared secret.
 */
@RestController
@Tag(name = "Auth", description = "Authentication")
class JwksController {

    private final RSAKey key;

    JwksController(RSAKey key) {
        this.key = key;
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "JSON Web Key Set for verifying access tokens")
    Map<String, List<Map<String, Object>>> jwks() {
        // toPublicJWK() strips the private parameters. Never serialise `key`.
        return Map.of("keys", List.of(key.toPublicJWK().toJSONObject()));
    }
}
