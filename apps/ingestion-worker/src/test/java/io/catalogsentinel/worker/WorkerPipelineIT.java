package io.catalogsentinel.worker;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.messaging.DeadLetterService;
import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.core.source.SourceService;
import io.catalogsentinel.core.testsupport.Events;
import io.catalogsentinel.core.testsupport.TestSchema;
import io.catalogsentinel.domain.source.SourceRole;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end through real infrastructure: PostgreSQL 16 and ElasticMQ (SQS-compatible) in containers, the
 * worker's real Spring context, SQS client, consumers and outbox relay.
 *
 * <p>listing event on SQS -> ingestion (listing row, idempotency ledger, outbox row) -> relay publishes the
 * change event -> change consumer runs matching (canonical link). Plus poison messages and DLQ replay.
 */
@SpringBootTest(classes = IngestionWorkerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "catalogsentinel.consumers.wait-time=1s",
                "catalogsentinel.consumers.visibility-timeout=10s",
                "catalogsentinel.consumers.backoff-base=200ms",
                "catalogsentinel.consumers.backoff-cap=1s",
                "catalogsentinel.outbox.poll-interval=100ms",
                "catalogsentinel.backlog-refresh=2s"
        })
class WorkerPipelineIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    static final GenericContainer<?> ELASTICMQ =
            new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.6.16")).withExposedPorts(9324);
    static final QueueNames NAMES = QueueNames.defaults();

    static {
        POSTGRES.start();
        ELASTICMQ.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // Dummy credentials for the local SQS emulator, read by the SDK's default credentials chain.
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        createQueues();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("catalogsentinel.aws.sqs-endpoint", WorkerPipelineIT::sqsEndpoint);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    QueueClient queue;
    @Autowired
    JsonCodec json;
    @Autowired
    SourceService sources;

    @BeforeEach
    void reset() {
        jdbc.execute(TestSchema.RESET_SQL);
        try (SqsClient admin = adminClient()) {
            for (String q : List.of(NAMES.listingEvents(), NAMES.listingEventsDlq(), NAMES.changeEvents(),
                    NAMES.changeEventsDlq())) {
                admin.purgeQueue(PurgeQueueRequest.builder().queueUrl(url(admin, q)).build());
            }
        }
    }

    @Test
    void listingEventFlowsThroughIngestionOutboxAndMatching() {
        sources.register("acme-market", "Acme Market", SourceRole.MARKETPLACE, true);
        ListingEventV1 event = Events.contract("acme-market", "A-100", 1, "Contoso Trailblazer 2 Hiking Boot, Size 10");

        send(NAMES.listingEvents(), event);

        awaitTrue(Duration.ofSeconds(30), () -> count("SELECT count(*) FROM listing_product_links") == 1);
        assertThat(count("SELECT count(*) FROM product_listings WHERE external_product_id = 'A-100'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM canonical_products")).isEqualTo(1);
    }

    @Test
    void duplicateDeliveriesAreAppliedOnce() {
        sources.register("acme-market", "Acme Market", SourceRole.MARKETPLACE, true);
        ListingEventV1 event = Events.contract("acme-market", "A-200", 3, "Fabrikam Kettle 1.7 L, Stainless");

        // At-least-once delivery: the same event (same eventId) arrives three times.
        send(NAMES.listingEvents(), event);
        send(NAMES.listingEvents(), event);
        send(NAMES.listingEvents(), event);

        awaitTrue(Duration.ofSeconds(30), () -> depth(NAMES.listingEvents()) == 0
                && count("SELECT count(*) FROM listing_product_links") == 1);
        assertThat(count("SELECT count(*) FROM product_listings")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events")).isEqualTo(1);
    }

    @Test
    void poisonMessageIsDeadLetteredWithReasonAndNotRetried() {
        queue.send(NAMES.listingEvents(), List.of(OutboundMessage.of("bad-1", "{not json", Map.of())));

        awaitTrue(Duration.ofSeconds(30), () -> depth(NAMES.listingEventsDlq()) == 1);
        List<QueueClient.ReceivedMessage> dead = queue.receive(NAMES.listingEventsDlq(), 1, Duration.ZERO,
                Duration.ofSeconds(1));
        assertThat(dead).hasSize(1);
        assertThat(dead.getFirst().attributes().get(MessageAttributes.FAILURE_REASON)).startsWith("MALFORMED_JSON");
        assertThat(dead.getFirst().attributes().get("receiveCount")).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM product_listings")).isZero();
    }

    @Test
    void deadLetteredEventForUnknownSourceIsRecoveredByReplay() {
        ListingEventV1 event = Events.contract("late-source", "L-1", 1, "Northwind Desk Lamp LED");
        send(NAMES.listingEvents(), event);
        awaitTrue(Duration.ofSeconds(30), () -> depth(NAMES.listingEventsDlq()) == 1);

        // Operator fixes the cause (registers the source), then replays the DLQ.
        sources.register("late-source", "Late Source", SourceRole.REFERENCE, true);
        DeadLetterService.ReplayResult replay = new DeadLetterService(queue, NAMES).replay(NAMES.listingEventsDlq(), 10);
        assertThat(replay.replayed()).isEqualTo(1);

        awaitTrue(Duration.ofSeconds(30), () -> count("SELECT count(*) FROM product_listings WHERE external_product_id = 'L-1'") == 1);
        assertThat(depth(NAMES.listingEventsDlq())).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void send(String queueName, ListingEventV1 event) {
        QueueClient.SendResult result = queue.send(queueName, List.of(OutboundMessage.of(UUID.randomUUID().toString(),
                json.write(event), Map.of(MessageAttributes.EVENT_ID, event.eventId().toString(),
                        MessageAttributes.EVENT_TYPE, event.eventType()))));
        assertThat(result.allSucceeded()).isTrue();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long depth(String queueName) {
        QueueClient.QueueDepth d = queue.depth(queueName);
        return d.visible() + d.inFlight() + d.delayed();
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static String sqsEndpoint() {
        return "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
    }

    private static SqsClient adminClient() {
        return SqsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(sqsEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }

    private static String url(SqsClient admin, String q) {
        return admin.getQueueUrl(b -> b.queueName(q)).queueUrl();
    }

    /** Same topology as infra/compose/elasticmq.conf: each queue redrives to its DLQ after 6 receives. */
    private static void createQueues() {
        try (SqsClient admin = adminClient()) {
            for (String[] pair : new String[][]{{NAMES.listingEvents(), NAMES.listingEventsDlq()},
                    {NAMES.changeEvents(), NAMES.changeEventsDlq()}}) {
                String dlqUrl = admin.createQueue(CreateQueueRequest.builder().queueName(pair[1]).build()).queueUrl();
                String dlqArn = admin.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
                admin.createQueue(CreateQueueRequest.builder().queueName(pair[0])
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                                "{\"maxReceiveCount\":\"6\",\"deadLetterTargetArn\":\"" + dlqArn + "\"}",
                                QueueAttributeName.VISIBILITY_TIMEOUT, "60"))
                        .build());
            }
        }
    }
}
