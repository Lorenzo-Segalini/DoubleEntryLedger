package dev.lseg.ledger.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.lseg.ledger.support.PostgresIT;

class ReconciliationApiIT extends PostgresIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper json;

    @Autowired
    RequestIdFilter requestIdFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM reconciliation_break").update();
        jdbc.sql("DELETE FROM reconciliation_match").update();
        jdbc.sql("DELETE FROM statement_line").update();
        jdbc.sql("DELETE FROM statement_import").update();
        truncateJournal();
        jdbc.sql("DELETE FROM idempotency_record").update();

        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").with(operator()))
                .addFilters(requestIdFilter)
                .build();
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor operator() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject(OPERATOR_ID.toString())
                        .claim("role", "OPERATOR")
                        .claim("email", "operator@demo.local"))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    private static final String STATEMENT =
            """
            value_date,amount,currency,description,external_id
            2026-06-04,1000.00,EUR,CARD SETTLEMENT,TX-1
            2026-06-28,-14.50,EUR,ACCOUNT MAINTENANCE FEE,TX-FEE
            """;

    @Test
    void importingAStatementReturnsTheBridge() throws Exception {
        postTransfer(100_000);

        mvc.perform(upload(STATEMENT, 0, 98_550))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importId", notNullValue()))
                .andExpect(jsonPath("$.accountCode", is("1000")))
                .andExpect(jsonPath("$.bridge", hasSize(1)))
                .andExpect(jsonPath("$.bridge[0].type", is("MISSING_IN_LEDGER")))
                .andExpect(jsonPath("$.bridge[0].deltaMinor", is(-1450)))
                // The deliverable: the explanations add up to the difference.
                .andExpect(jsonPath("$.bridgeTotalMinor", is(-1450)))
                .andExpect(jsonPath("$.differenceMinor", is(-1450)))
                .andExpect(jsonPath("$.bridgeBalanced", is(true)));
    }

    @Test
    void reUploadingTheSameFileReturnsTheSameRun() throws Exception {
        postTransfer(100_000);

        String first = mvc.perform(upload(STATEMENT, 0, 98_550))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = mvc.perform(upload(STATEMENT, 0, 98_550))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Idempotency by natural key: the file's own content.
        org.assertj.core.api.Assertions.assertThat(json.readTree(second).get("importId"))
                .isEqualTo(json.readTree(first).get("importId"));
    }

    @Test
    void aStatementThatDoesNotAddUpIs422() throws Exception {
        mvc.perform(upload(STATEMENT, 0, 999_999))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", containsString("statement-not-internally-consistent")))
                .andExpect(jsonPath("$.details.declaredClosingMinor", is(999999)));
    }

    @Test
    void anUnreadableFileIs422WithTheRowNumber() throws Exception {
        mvc.perform(upload(
                        """
                        value_date,amount,currency,description
                        2026-06-04,not-a-number,EUR,BROKEN
                        """,
                        0,
                        0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", containsString("statement-not-readable")))
                .andExpect(jsonPath("$.detail", containsString("row 1")));
    }

    @Test
    void aBreakCanBeExplainedAndThenResolved() throws Exception {
        postTransfer(100_000);

        String body = mvc.perform(upload(STATEMENT, 0, 98_550))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String importId = json.readTree(body).get("importId").asText();
        String breakId = json.readTree(body).get("bridge").get(0).get("breakId").asText();

        mvc.perform(post("/api/v1/reconciliations/{id}/breaks/{breakId}/explain", importId, breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explanation\":\"monthly account fee\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/reconciliations/{id}/breaks/{breakId}/resolve", importId, breakId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterAccountCode\":\"5000\",\"explanation\":\"booked the fee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustingEntryId", notNullValue()));

        // The adjustment closes the difference, and the report says so.
        mvc.perform(get("/api/v1/reconciliations/{id}/report", importId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceMinor", is(0)))
                .andExpect(jsonPath("$.bridgeTotalMinor", is(0)))
                .andExpect(jsonPath("$.bridge[0].status", is("RESOLVED")));
    }

    @Test
    void resolvingRequiresAnIdempotencyKeyLikeAnyOtherPosting() throws Exception {
        postTransfer(100_000);
        String body = mvc.perform(upload(STATEMENT, 0, 98_550))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String importId = json.readTree(body).get("importId").asText();
        String breakId = json.readTree(body).get("bridge").get(0).get("breakId").asText();

        mvc.perform(post("/api/v1/reconciliations/{id}/breaks/{breakId}/resolve", importId, breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterAccountCode\":\"5000\",\"explanation\":\"no key\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", containsString("idempotency-key-required")));
    }

    @Test
    void anAuditorMayReadAReportAndMayNotImport() throws Exception {
        var auditor = SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject(AUDITOR_ID.toString())
                        .claim("role", "AUDITOR")
                        .claim("email", "auditor@demo.local"))
                .authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"));

        mvc.perform(get("/api/v1/reconciliations").with(auditor)).andExpect(status().isOk());

        mvc.perform(upload(STATEMENT, 0, 98_550).with(auditor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", containsString("insufficient-role")));
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            String csv, long opening, long closing) {

        var builder = multipart("/api/v1/reconciliations")
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
        builder.param("accountCode", "1000");
        builder.param("periodStart", "2026-06-01");
        builder.param("periodEnd", "2026-06-30");
        builder.param("openingBalanceMinor", String.valueOf(opening));
        builder.param("closingBalanceMinor", String.valueOf(closing));
        return builder;
    }

    private void postTransfer(long amountMinor) throws Exception {
        mvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"effectiveDate":"2026-06-04","description":"CARD SETTLEMENT","currency":"EUR",
                                 "externalRef":"TX-1",
                                 "lines":[{"accountCode":"1000","direction":"DEBIT","amountMinor":%d},
                                          {"accountCode":"4000","direction":"CREDIT","amountMinor":%d}]}
                                """
                                        .formatted(amountMinor, amountMinor)))
                .andExpect(status().isCreated());
    }
}
