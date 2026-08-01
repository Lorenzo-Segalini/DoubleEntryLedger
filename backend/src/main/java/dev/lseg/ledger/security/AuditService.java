package dev.lseg.ledger.security;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.lseg.ledger.api.RequestIdFilter;

/**
 * Records what the journal cannot.
 *
 * <p>Logins, denied authorisation attempts, account changes, statement imports.
 * Financial history lives in the journal; this is who did what to the system.
 * Append-only for the same reason.
 *
 * <p>{@code REQUIRES_NEW} on every public method: an audit entry must survive the
 * rollback of whatever it was recording. A denied login that rolls back and takes
 * its own "denied" record with it is worse than no audit log, because it looks
 * like nothing happened.
 *
 * <p>The annotation is on the public methods rather than on the shared private
 * writer for a reason that costs an afternoon to find otherwise: Spring's
 * transactional proxy only intercepts calls arriving from outside the bean, so
 * annotating a helper this class calls on {@code this} does nothing at all.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginSucceeded(AppUser user, AuthenticationService.RequestMetadata metadata) {
        record(user.id(), user.role(), "AUTH_LOGIN", "app_user", user.id().toString(), "SUCCESS", metadata, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginDenied(String email, AuthenticationService.RequestMetadata metadata, String reason) {
        // The email is recorded, the reason is recorded, and neither is ever
        // returned to the caller. That asymmetry is the point.
        record(null, null, "AUTH_LOGIN", "app_user", email, "DENIED", metadata, Map.of("reason", reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshed(AppUser user, AuthenticationService.RequestMetadata metadata) {
        record(user.id(), user.role(), "AUTH_REFRESH", "app_user", user.id().toString(), "SUCCESS", metadata, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshTokenReuseDetected(UUID userId, UUID familyId, AuthenticationService.RequestMetadata metadata) {
        record(
                userId,
                null,
                "AUTH_REFRESH_REUSE",
                "refresh_token_family",
                familyId.toString(),
                "DENIED",
                metadata,
                Map.of("action", "family-revoked"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loggedOut(UUID userId, AuthenticationService.RequestMetadata metadata) {
        record(userId, null, "AUTH_LOGOUT", "app_user", userId.toString(), "SUCCESS", metadata, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accessDenied(UUID userId, AppRole role, String action, String target) {
        record(
                userId,
                role,
                action,
                "endpoint",
                target,
                "DENIED",
                AuthenticationService.RequestMetadata.none(),
                Map.of("reason", "insufficient-role"));
    }

    private void record(
            UUID actorId,
            AppRole role,
            String action,
            String targetType,
            String targetId,
            String outcome,
            AuthenticationService.RequestMetadata metadata,
            Map<String, Object> detail) {

        String requestId = MDC.get("requestId");
        jdbc.sql(
                        """
                        INSERT INTO audit_event
                            (actor_id, actor_role, action, target_type, target_id, request_id, ip_address, outcome, detail)
                        VALUES
                            (:actorId, CAST(:role AS app_role), :action, :targetType, :targetId,
                             :requestId, CAST(:ip AS inet), :outcome, CAST(:detail AS jsonb))
                        """)
                .param("actorId", actorId, Types.OTHER)
                .param("role", role == null ? null : role.name(), Types.VARCHAR)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId, Types.VARCHAR)
                .param("requestId", requestId == null ? RequestIdFilter.UNKNOWN : requestId)
                .param("ip", metadata == null ? null : metadata.ipAddress(), Types.VARCHAR)
                .param("outcome", outcome)
                .param("detail", write(detail))
                .update();
    }

    private String write(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }
}
