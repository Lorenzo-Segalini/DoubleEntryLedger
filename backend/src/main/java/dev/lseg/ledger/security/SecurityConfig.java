package dev.lseg.ledger.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT authentication with three roles. See ADR-0007.
 *
 * <p>Authorisation is <strong>not</strong> expressed here beyond the coarse
 * "authenticated or not" line. The role checks live on the service layer as
 * {@code @PreAuthorize}, so a controller added later without an annotation still
 * hits a guarded service. A rule enforced only in the web layer is a rule that
 * holds until someone adds a second entry point.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http
                // No session, no CSRF token to protect: the API is stateless and
                // bearer-authenticated, so there is no ambient cookie credential a
                // forged cross-site request could ride on. The refresh cookie is
                // SameSite=Strict and is only ever read by /auth/refresh.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/.well-known/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        // Metrics are not public: they leak posting volumes and
                        // error rates that say more about the business than the
                        // API does.
                        .requestMatchers("/actuator/prometheus")
                        .hasRole(AppRole.ADMIN.name())
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * Maps the {@code role} claim onto a single Spring authority.
     *
     * <p>The default converter reads {@code scope}/{@code scp} and prefixes with
     * {@code SCOPE_}, which would silently leave every {@code hasRole} check
     * failing closed — safe, but confusing to debug.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtService.ROLE_CLAIM);
            if (role == null) {
                return List.of();
            }
            return List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    AppRole.valueOf(role).authority()));
        });
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    /**
     * Cost 12: roughly 250 ms per hash on current hardware. Slow enough to make
     * offline cracking expensive, fast enough that a login is not noticeable.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${ledger.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Idempotency-Replayed", "Location"));
        // Required for the refresh cookie to travel from the Vercel origin.
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
