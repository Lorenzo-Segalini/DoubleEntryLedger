package dev.lseg.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.lseg.ledger.support.PostgresIT;

/**
 * Authentication and role enforcement end to end.
 *
 * <p>Unlike {@link JournalEntryApiIT}, nothing here is injected into the security
 * context: these log in with a password, carry the issued token, and are refused
 * exactly as a real client would be.
 */
class AuthApiIT extends PostgresIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper json;

    @Autowired
    RequestIdFilter requestIdFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        truncateJournal();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM refresh_token").update();

        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .addFilters(requestIdFilter)
                .build();
    }

    // ---------------------------------------------------------------- login

    @Test
    void loggingInReturnsAnAccessTokenAndARefreshCookie() throws Exception {
        MvcResult result = login("operator@demo.local", "test-operator")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresIn", is(900)))
                .andExpect(jsonPath("$.role", is("OPERATOR")))
                .andExpect(jsonPath("$.email", is("operator@demo.local")))
                .andReturn();

        Cookie refresh = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(refresh).isNotNull();
        // The properties that matter, asserted rather than assumed.
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    void theRefreshTokenIsNeverInTheResponseBody() throws Exception {
        String body = login("operator@demo.local", "test-operator")
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Putting it in the body would undo HttpOnly in one line.
        assertThat(json.readTree(body).has("refreshToken")).isFalse();
    }

    @Test
    void theStoredRefreshTokenIsAHashNotTheTokenItself() throws Exception {
        MvcResult result = login("operator@demo.local", "test-operator").andReturn();
        String presented =
                result.getResponse().getCookie(AuthController.REFRESH_COOKIE).getValue();

        // A database dump must not be a set of live credentials.
        long matchingPlaintext = jdbc.sql(
                        "SELECT count(*) FROM refresh_token WHERE encode(token_hash, 'escape') = :token")
                .param("token", presented)
                .query(Long.class)
                .single();

        assertThat(matchingPlaintext).isZero();
        assertThat(count("refresh_token")).isEqualTo(1);
    }

    @Test
    void awrongPasswordAndAnUnknownEmailAreIndistinguishable() throws Exception {
        String wrongPassword = login("operator@demo.local", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String unknownEmail = login("nobody@demo.local", "test-operator")
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Telling these apart would be an account enumeration oracle. Only the
        // requestId differs.
        assertThat(json.readTree(wrongPassword).get("detail"))
                .isEqualTo(json.readTree(unknownEmail).get("detail"));
        assertThat(json.readTree(wrongPassword).get("type"))
                .isEqualTo(json.readTree(unknownEmail).get("type"));
    }

    @Test
    void bothFailuresAreDistinguishedInTheAuditLog() throws Exception {
        login("operator@demo.local", "not-the-password").andExpect(status().isUnauthorized());
        login("nobody@demo.local", "whatever").andExpect(status().isUnauthorized());

        // What the caller may not learn, an auditor may.
        assertThat(auditReasons()).contains("bad-password", "unknown-email");
    }

    // ---------------------------------------------------------------- tokens in use

    @Test
    void anAccessTokenIsAcceptedOnAProtectedEndpoint() throws Exception {
        String token = accessToken("operator@demo.local", "test-operator");

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aRequestWithoutATokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = accessToken("operator@demo.local", "test-operator");
        // Flip the last character of the signature.
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReportsTheCallerAndWhatTheyMayDo() throws Exception {
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken("auditor@demo.local", "test-auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("AUDITOR")))
                .andExpect(jsonPath("$.can", is(java.util.List.of("ledger:read"))));
    }

    // ---------------------------------------------------------------- roles

    @Test
    void anAuditorCanReadEverythingAndPostNothing() throws Exception {
        String auditor = accessToken("auditor@demo.local", "test-auditor");

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + auditor))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/reports/trial-balance").header("Authorization", "Bearer " + auditor))
                .andExpect(status().isOk());

        // The role that makes the append-only argument concrete: it sees
        // everything and can change nothing.
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + auditor)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", containsString("insufficient-role")));

        assertThat(count("journal_entry")).isZero();
    }

    @Test
    void aDeniedWriteIsRecordedInTheAuditLog() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + accessToken("auditor@demo.local", "test-auditor"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson()))
                .andExpect(status().isForbidden());

        long denials = jdbc.sql("SELECT count(*) FROM audit_event WHERE outcome = 'DENIED' AND actor_role = 'AUDITOR'")
                .query(Long.class)
                .single();

        assertThat(denials).isEqualTo(1);
    }

    @Test
    void anOperatorAndAnAdminMayBothPost() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + accessToken("operator@demo.local", "test-operator"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson()))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + accessToken("admin@demo.local", "test-admin"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson()))
                .andExpect(status().isCreated());

        assertThat(count("journal_entry")).isEqualTo(2);
    }

    @Test
    void thePostingIsAttributedToTheAuthenticatedUser() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + accessToken("operator@demo.local", "test-operator"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy", is(OPERATOR_ID.toString())));
    }

    // ---------------------------------------------------------------- refresh rotation

    @Test
    void refreshingRotatesTheTokenAndIssuesANewAccessToken() throws Exception {
        Cookie first =
                refreshCookieFrom(login("operator@demo.local", "test-operator").andReturn());

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn();

        Cookie second = refreshCookieFrom(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Two rows: the spent one and its replacement, linked.
        assertThat(count("refresh_token")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE used_at IS NOT NULL AND replaced_by IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamily() throws Exception {
        Cookie first =
                refreshCookieFrom(login("operator@demo.local", "test-operator").andReturn());
        Cookie second = refreshCookieFrom(
                mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andReturn());

        // The stolen copy. Presenting it means two parties hold the same
        // credential, and nothing can tell which one is calling.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());

        // So the legitimate client is logged out too. That is the intended
        // outcome: an inconvenience beats an attacker with a live session.
        mvc.perform(post("/api/v1/auth/refresh").cookie(second)).andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(auditActions()).contains("AUTH_REFRESH_REUSE");
    }

    @Test
    void loggingOutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        Cookie cookie =
                refreshCookieFrom(login("operator@demo.local", "test-operator").andReturn());

        mvc.perform(post("/api/v1/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        mvc.perform(post("/api/v1/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshingWithNoCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- jwks

    @Test
    void theJwksEndpointPublishesOnlyThePublicKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty", is("RSA")))
                .andExpect(jsonPath("$.keys[0].n", notNullValue()))
                // The private parameters must never appear here.
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }

    @Test
    void healthIsPublicButMetricsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String accessToken(String email, String password) throws Exception {
        String body = login(email, password)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private static Cookie refreshCookieFrom(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).as("refresh cookie").isNotNull();
        return cookie;
    }

    private static String transferJson() {
        return """
               {"effectiveDate":"2026-07-15","fromAccountCode":"1000","toAccountCode":"1100",
                "amountMinor":250000,"currency":"EUR","description":"transfer"}
               """;
    }

    private java.util.List<String> auditReasons() {
        return jdbc.sql("SELECT detail ->> 'reason' FROM audit_event WHERE outcome = 'DENIED'")
                .query(String.class)
                .list();
    }

    private java.util.List<String> auditActions() {
        return jdbc.sql("SELECT action FROM audit_event").query(String.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
