package io.catalogsentinel.api;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests of the API against a real PostgreSQL (Testcontainers) with Flyway migrations
 * applied by Spring Boot. SQS is replaced by a mock so the async endpoint can be exercised without a broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiIT {

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
    void reset() throws Exception {
        jdbc.execute(TestSchema.RESET_SQL);
        mvc.perform(post("/api/v1/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"acme-market\",\"name\":\"Acme Marketplace\",\"role\":\"MARKETPLACE\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/sources/1"));
    }

    private static String event(UUID id, String sku, long version, String title) {
        return """
                {"eventId":"%s","eventType":"LISTING_UPSERTED","schemaVersion":1,"sourceCode":"acme-market",
                 "externalProductId":"%s","sourceVersion":%d,"occurredAt":"2026-01-01T00:00:00Z",
                 "payload":{"title":"%s","brand":"Sony","model":"WH-1000XM5","attributes":{"color":"Black"}}}
                """.formatted(id, sku, version, title);
    }

    @Test
    void sourceRegistrationValidationAndConflicts() throws Exception {
        mvc.perform(post("/api/v1/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"acme-market\",\"name\":\"Dup\",\"role\":\"REFERENCE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_CODE_TAKEN"));
        mvc.perform(post("/api/v1/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Bad Code\",\"name\":\"\",\"role\":\"SELLER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code", "name", "role")));
        mvc.perform(get("/api/v1/sources/1")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MARKETPLACE"));
        mvc.perform(get("/api/v1/sources/999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/sources").param("role", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void ingestionStatusCodesFollowOutcomes() throws Exception {
        UUID first = UUID.randomUUID();
        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "demo-123")
                        .content(event(first, "SKU-1", 1, "Sony WH-1000XM5")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/listings/1"))
                .andExpect(header().string("X-Correlation-Id", "demo-123"))
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.duplicate").value(false));

        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(first, "SKU-1", 1, "Sony WH-1000XM5")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(first, "SKU-1", 1, "Changed title")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ID_REUSED"));

        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(UUID.randomUUID(), "SKU-1", 1, "Same version, other content")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.listingId").value(1));

        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(UUID.randomUUID(), "SKU-1", 0, "Old")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("STALE_IGNORED"));

        mvc.perform(get("/api/v1/listings/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.normalizedModel").value("wh1000xm5"))
                .andExpect(jsonPath("$.history", hasSize(3)));
    }

    @Test
    void validationAndUnknownSourceErrors() throws Exception {
        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"LISTING_UPSERTED\",\"schemaVersion\":1,\"payload\":{\"title\":\"\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder(
                        "sourceVersion", "payload.title", "eventId", "sourceCode", "externalProductId", "occurredAt")))
                .andExpect(jsonPath("$.correlationId", notNullValue()));

        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        String unknownSource = event(UUID.randomUUID(), "SKU-1", 1, "x").replace("acme-market", "ghost");
        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON).content(unknownSource))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNKNOWN_SOURCE"));

        mvc.perform(patch("/api/v1/sources/1").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                        .content(event(UUID.randomUUID(), "SKU-2", 1, "x")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("SOURCE_DISABLED"));
    }

    @Test
    void asyncEndpointValidatesThenEnqueues() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(queueClient.send(anyString(), anyList()))
                .thenReturn(new QueueClient.SendResult(List.of(a.toString(), b.toString()), Map.of()));
        mvc.perform(post("/api/v1/ingestion/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event(a, "SKU-A", 1, "A") + "," + event(b, "SKU-B", 1, "B") + "]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2));

        mvc.perform(post("/api/v1/ingestion/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event(UUID.randomUUID(), "SKU-C", 1, "") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("events[0].payload.title"));
    }

    @Test
    void listingSearchPaginates() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/listing-events").contentType(MediaType.APPLICATION_JSON)
                    .content(event(UUID.randomUUID(), "SKU-" + i, 1, "Item " + i))).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/listings").param("sourceCode", "acme-market").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].externalProductId").value("SKU-4"));
        mvc.perform(get("/api/v1/listings").param("cursor", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/listings").param("sourceCode", "nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void healthAndOpenApiAreExposed() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/listing-events']").exists());
    }
}
