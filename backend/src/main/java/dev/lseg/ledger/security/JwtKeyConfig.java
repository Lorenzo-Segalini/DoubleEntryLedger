package dev.lseg.ledger.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * The RSA key pair that signs access tokens.
 *
 * <p>RS256 rather than HMAC so verification needs only the public half. The
 * public key is published at {@code /.well-known/jwks.json}, which lets anything
 * downstream — a gateway, a second service, the reconciliation worker on the
 * roadmap — verify a token without being trusted with the ability to mint one.
 *
 * <p>In production the private key arrives as {@code JWT_PRIVATE_KEY}, a base64
 * PKCS#8 blob held in {@code fly secrets}. With no key configured a fresh pair is
 * generated at startup: convenient for local work and for tests, and deliberately
 * loud, because it means every restart invalidates every token in circulation.
 */
@Configuration
class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    RSAKey rsaKey(@Value("${ledger.jwt.private-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("No ledger.jwt.private-key configured; generating an ephemeral RSA key. "
                    + "Every restart will invalidate all issued tokens. Do not run production this way.");
            return generate();
        }
        return fromPkcs8(configuredKey);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey key) {
        JWKSet set = new JWKSet(key);
        return (selector, context) -> selector.select(set);
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwks) {
        return new NimbusJwtEncoder(jwks);
    }

    /**
     * The decoder validates timestamps against the same {@link Clock} the encoder
     * signs with.
     *
     * <p>Nimbus defaults to {@code Instant.now()}, which is right in production
     * and wrong anywhere the clock is controlled: a token minted at the injected
     * time would be judged against the wall clock and rejected as expired the
     * moment it was issued. Sharing the clock also makes expiry itself testable
     * rather than a matter of waiting fifteen minutes.
     */
    @Bean
    JwtDecoder jwtDecoder(RSAKey key, Clock clock) {
        try {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();

            JwtTimestampValidator timestamps = new JwtTimestampValidator();
            timestamps.setClock(clock);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps));

            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException("could not build a JWT decoder from the configured key", e);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }

    private static RSAKey fromPkcs8(String base64) {
        try {
            // Tolerate a PEM that arrived with its armour and line breaks intact:
            // a key pasted into an environment variable usually has.
            String stripped = base64.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(stripped);

            RSAPrivateKey privateKey =
                    (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));

            // Derive the public half rather than requiring a second variable: one
            // secret to rotate is one chance to get the pairing wrong.
            java.security.spec.RSAPublicKeySpec publicSpec = new java.security.spec.RSAPublicKeySpec(
                    privateKey.getModulus(),
                    ((java.security.interfaces.RSAPrivateCrtKey) privateKey).getPublicExponent());
            RSAPublicKey publicKey =
                    (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(publicSpec);

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID("ledger-signing-key")
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("ledger.jwt.private-key is not a valid base64 PKCS#8 RSA private key", e);
        }
    }
}
