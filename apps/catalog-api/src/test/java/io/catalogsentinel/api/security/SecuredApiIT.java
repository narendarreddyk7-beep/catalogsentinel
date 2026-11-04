package io.catalogsentinel.api.security;

import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.testsupport.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization matrix of the {@code aws} profile (security enabled), end to end through the real filter
 * chain. The keys are test-only values; only their SHA-256 hashes are configured, as in production.
 */
@SpringBootTest(properties = {
        "catalogsentinel.security.enabled=true",
        "catalogsentinel.security.keys="
                + "73cd7f6f3884ee0ad6a3292f90865222842c11270f1080e3f91be38edcad73b7:READER,"
                + "7c5564276e2f89309f2ea77b1a516b3f6c36c4622f3374485d2792e49537ac60:REVIEWER,"
                + "9949951f9b3142a6facba6fc22ef7e5c99bebd7bc4a50d8b8d00098ad50f4ad8:INGEST,"
                + "0d46389428b4ebfa8757051ceae368473fc4b38a6e2a4ab0b70e0bf6b285fbf9:ADMIN"})
@AutoConfigureMockMvc
@Testcontainers
class SecuredApiIT {

    private static final String READER = "reader-test-key";
    private static final String REVIEWER = "reviewer-test-key";
    private static final String INGEST = "ingest-test-key";
    private static final String ADMIN = "admin-test-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    QueueClient queueClient;

    @BeforeEach
    void reset() {
        jdbc.execute(TestSchema.RESET_SQL);
    }

    private static MockHttpServletRequestBuilder as(String key, MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyAuthenticationFilter.HEADER, key);
    }

    @Test
    void requestsWithoutAValidKeyAreUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/listings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(as("not-a-configured-key", get("/api/v1/listings")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readersCanReadButNotChangeAnything() throws Exception {
        mvc.perform(as(READER, get("/api/v1/listings"))).andExpect(status().isOk());
        mvc.perform(as(READER, get("/api/v1/dashboard/summary"))).andExpect(status().isOk());
        mvc.perform(as(READER, post("/api/v1/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"acme-market\",\"name\":\"Acme\",\"role\":\"MARKETPLACE\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(READER, post("/api/v1/gaps/1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DISMISS\",\"reason\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(READER, post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewDecisionsNeedTheReviewerRoleAndIngestionTheIngestRole() throws Exception {
        // Authorized requests reach the controller: 404 for a gap that does not exist, 400 for an empty event.
        mvc.perform(as(REVIEWER, post("/api/v1/gaps/999/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DISMISS\",\"reason\":\"duplicate of an existing listing\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(INGEST, post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(INGEST, post("/api/v1/gaps/999/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DISMISS\",\"reason\":\"x\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorEndpointsAreAdminOnlyForEveryMethod() throws Exception {
        mvc.perform(as(REVIEWER, get("/api/v1/admin/outbox"))).andExpect(status().isForbidden());
        mvc.perform(as(INGEST, post("/api/v1/admin/outbox/requeue-failed"))).andExpect(status().isForbidden());
        mvc.perform(as(ADMIN, get("/api/v1/admin/outbox")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0));
        mvc.perform(as(READER, get("/actuator/metrics"))).andExpect(status().isForbidden());
    }

    @Test
    void healthDetailsAreShownOnlyToAdminKeys() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        for (String key : List.of(READER, REVIEWER, INGEST)) {
            mvc.perform(as(key, get("/actuator/health")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
        mvc.perform(as(ADMIN, get("/actuator/health")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void healthDocsAndTheMetricsScrapeStayReachableWithoutAKey() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        // Whether the Prometheus endpoint is active in a test context depends on metrics-export test defaults;
        // what matters here is that the security chain lets the unauthenticated in-task scrape through.
        int scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();
        assertThat(scrape).isNotIn(401, 403);
    }
}
