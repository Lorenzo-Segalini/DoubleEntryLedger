package dev.lseg.ledger.api;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.lseg.ledger.support.PostgresIT;

/**
 * The API over real HTTP against a real database.
 *
 * <p>Nothing is mocked below the controller: these exercise the whole stack down
 * to the deferred constraint triggers, which is the only way the status codes
 * asserted here mean anything.
 */
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
class JournalEntryApiIT extends PostgresIT {

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
        // addFilters: a MockMvc built from the context alone wires controllers but
        // not servlet filter beans, so X-Request-Id would silently never be set.
        // A real JWT for a real seeded user, not @WithMockUser: created_by is a
        // foreign key, so an invented principal id fails at the database rather
        // than proving anything about the API.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.jwt()
                                        .jwt(token -> token.subject(OPERATOR_ID.toString())
                                                .claim("role", "OPERATOR")
                                                .claim("email", "operator@demo.local"))
                                        .authorities(
                                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                        "ROLE_OPERATOR"))))
                .addFilters(requestIdFilter)
                .build();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    // ---------------------------------------------------------------- posting

    @Test
    void postsAThreeLineEntryAndReturnsItInFull() throws Exception {
        String payload =
                """
                {
                  "effectiveDate": "2026-07-15",
                  "description": "Card payment #4471 settled",
                  "currency": "EUR",
                  "externalRef": "psp:pay_3Nk8Qz",
                  "lines": [
                    {"accountCode": "1100", "direction": "DEBIT",  "amountMinor": 9710, "memo": "net settlement"},
                    {"accountCode": "5000", "direction": "DEBIT",  "amountMinor": 290,  "memo": "processor fee"},
                    {"accountCode": "4000", "direction": "CREDIT", "amountMinor": 10000}
                  ]
                }
                """;

        mvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/journal-entries/")))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.sequenceNo", greaterThan(0)))
                .andExpect(jsonPath("$.source", is("API")))
                .andExpect(jsonPath("$.externalRef", is("psp:pay_3Nk8Qz")))
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.totalDebit.amountMinor", is(10000)))
                .andExpect(jsonPath("$.totalCredit.amountMinor", is(10000)))
                // Money renders as minor units plus a display string, never a JSON decimal.
                .andExpect(jsonPath("$.totalDebit.amount", is("100.00")))
                .andExpect(jsonPath("$.totalDebit.currency", is("EUR")))
                // Omitted rather than null: the API sets default-property-inclusion
                // to non_null, so "not a reversal" is expressed by absence.
                .andExpect(jsonPath("$.reversalOfEntryId").doesNotExist());
    }

    @Test
    void theRequestIdIsEchoedAndStoredOnTheEntry() throws Exception {
        String requestId = "test-" + UUID.randomUUID();

        String id = mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key())
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", requestId))
                // The same token reaches the journal row, which is what lets an
                // operator get from a posting back to the request that made it.
                .andExpect(jsonPath("$.requestId", is(requestId)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertStoredRequestId(id, requestId);
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void aRetryReturns200WithTheOriginalBodyAndPostsNothing() throws Exception {
        String key = key();
        String payload = transferJson(250_000);

        String first = mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String replayed = mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The bodies must be identical: that is the guarantee. Only the status and
        // the header distinguish a replay.
        org.assertj.core.api.Assertions.assertThat(json.readTree(replayed)).isEqualTo(json.readTree(first));
        org.assertj.core.api.Assertions.assertThat(count("journal_entry")).isEqualTo(1);
    }

    @Test
    void aMissingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", containsString("idempotency-key-required")))
                .andExpect(jsonPath("$.requestId", notNullValue()));
    }

    @Test
    void reusingAKeyForADifferentBodyIsAConflict() throws Exception {
        String key = key();
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(999_999)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", containsString("idempotency-key-conflict")));

        org.assertj.core.api.Assertions.assertThat(count("journal_entry")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- errors

    @Test
    void anUnbalancedEntryIs422AndSaysByHowMuch() throws Exception {
        String payload =
                """
                {
                  "effectiveDate": "2026-07-15",
                  "description": "unbalanced",
                  "currency": "EUR",
                  "lines": [
                    {"accountCode": "1000", "direction": "DEBIT",  "amountMinor": 10000},
                    {"accountCode": "4000", "direction": "CREDIT", "amountMinor": 9000}
                  ]
                }
                """;

        mvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.type", containsString("unbalanced-entry")))
                .andExpect(jsonPath("$.code", is("UNBALANCED_ENTRY")))
                .andExpect(jsonPath("$.details.differenceMinor", is(1000)))
                .andExpect(jsonPath("$.details.totalDebitMinor", is(10000)))
                .andExpect(jsonPath("$.requestId", notNullValue()));

        org.assertj.core.api.Assertions.assertThat(count("journal_entry")).isZero();
    }

    @Test
    void aDecimalAmountIsRefusedRatherThanRounded() throws Exception {
        // 125.50 in a field typed as minor units is not a rounding decision to
        // make on the caller's behalf. It is a malformed request.
        String payload =
                """
                {
                  "effectiveDate": "2026-07-15", "description": "decimal", "currency": "EUR",
                  "lines": [
                    {"accountCode": "1000", "direction": "DEBIT",  "amountMinor": 125.50},
                    {"accountCode": "4000", "direction": "CREDIT", "amountMinor": 125.50}
                  ]
                }
                """;

        mvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", containsString("malformed-request")));
    }

    @Test
    void aSingleLineEntryFailsSchemaValidationWithAFieldPointer() throws Exception {
        String payload =
                """
                {
                  "effectiveDate": "2026-07-15", "description": "one line", "currency": "EUR",
                  "lines": [{"accountCode": "1000", "direction": "DEBIT", "amountMinor": 100}]
                }
                """;

        mvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", containsString("validation-failed")))
                .andExpect(jsonPath("$.errors[0].pointer", is("/lines")));
    }

    @Test
    void anUnknownAccountIs422() throws Exception {
        mvc.perform(
                        post("/api/v1/transfers")
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"effectiveDate":"2026-07-15","fromAccountCode":"1000","toAccountCode":"9999",
                                 "amountMinor":1000,"currency":"EUR","description":"nowhere"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", containsString("unknown-account")))
                .andExpect(jsonPath("$.details.accountCode", is("9999")));
    }

    @Test
    void readingAnEntryThatDoesNotExistIs404() throws Exception {
        mvc.perform(get("/api/v1/journal-entries/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", containsString("entry-not-found")));
    }

    // ---------------------------------------------------------------- reversal

    @Test
    void reversingAnEntryPostsTheMirrorAndLeavesTheOriginal() throws Exception {
        String created = mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = json.readTree(created).get("id").asText();

        mvc.perform(post("/api/v1/journal-entries/{id}/reversal", id)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveDate\":\"2026-07-15\",\"reason\":\"sent to the wrong account\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", is("REVERSAL")))
                .andExpect(jsonPath("$.reversalOfEntryId", is(id)))
                .andExpect(jsonPath("$.reversalReason", is("sent to the wrong account")))
                .andExpect(jsonPath("$.lines", hasSize(2)));

        // Correcting does not erase: both entries are in the journal.
        org.assertj.core.api.Assertions.assertThat(count("journal_entry")).isEqualTo(2);
        mvc.perform(get("/api/v1/journal-entries/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void reversingTwiceIsAConflict() throws Exception {
        String created = mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = json.readTree(created).get("id").asText();

        String reversalBody = "{\"effectiveDate\":\"2026-07-15\",\"reason\":\"first\"}";
        mvc.perform(post("/api/v1/journal-entries/{id}/reversal", id)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/journal-entries/{id}/reversal", id)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveDate\":\"2026-07-15\",\"reason\":\"second\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", containsString("already-reversed")));
    }

    @Test
    void aReversalWithoutAReasonFailsValidation() throws Exception {
        mvc.perform(post("/api/v1/journal-entries/{id}/reversal", UUID.randomUUID())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveDate\":\"2026-07-15\",\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].pointer", is("/reason")));
    }

    // ---------------------------------------------------------------- reads

    @Test
    void balancesAndTheTrialBalanceReflectWhatWasPosted() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(250_000)))
                .andExpect(status().isCreated());

        String accountId = jdbc.sql("SELECT id FROM account WHERE code = '1100'")
                .query(UUID.class)
                .single()
                .toString();

        mvc.perform(get("/api/v1/accounts/{id}/balance", accountId).param("asOf", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("1100")))
                .andExpect(jsonPath("$.balance.amountMinor", is(250_000)))
                .andExpect(jsonPath("$.balance.amount", is("2500.00")))
                .andExpect(jsonPath("$.derivedAt", notNullValue()));

        mvc.perform(get("/api/v1/reports/trial-balance").param("asOf", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced", is(true)))
                .andExpect(jsonPath("$.outOfBalanceMinor", is(0)))
                .andExpect(jsonPath("$.totalDebit.amountMinor", is(250_000)))
                .andExpect(jsonPath("$.totalCredit.amountMinor", is(250_000)));
    }

    @Test
    void theChartOfAccountsIsListed() throws Exception {
        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(both(greaterThan(5)).and(org.hamcrest.Matchers.lessThan(50)))))
                .andExpect(jsonPath("$[0].code", notNullValue()))
                .andExpect(jsonPath("$[0].type", notNullValue()));
    }

    @Test
    void theOpenApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/transfers']", notNullValue()))
                .andExpect(jsonPath("$.paths['/api/v1/journal-entries']", notNullValue()));
    }

    // ---------------------------------------------------------------- helpers

    private static String transferJson(long amountMinor) {
        return """
               {"effectiveDate":"2026-07-15","fromAccountCode":"1000","toAccountCode":"1100",
                "amountMinor":%d,"currency":"EUR","description":"transfer"}
               """
                .formatted(amountMinor);
    }

    private void assertStoredRequestId(String responseBody, String expected) throws Exception {
        String id = json.readTree(responseBody).get("id").asText();
        String stored = jdbc.sql("SELECT request_id FROM journal_entry WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(expected);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
