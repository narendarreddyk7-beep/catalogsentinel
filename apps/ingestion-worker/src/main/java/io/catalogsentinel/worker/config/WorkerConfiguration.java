package io.catalogsentinel.worker.config;

import io.catalogsentinel.core.ingestion.IngestionMetrics;
import io.catalogsentinel.core.ingestion.ListingEventMapper;
import io.catalogsentinel.core.ingestion.ListingEventMessageHandler;
import io.catalogsentinel.core.ingestion.ListingIngestionService;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.matching.ListingChangedMessageHandler;
import io.catalogsentinel.core.matching.MatchingService;
import io.catalogsentinel.core.messaging.BackoffPolicy;
import io.catalogsentinel.core.messaging.ConsumerMetrics;
import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueConsumer;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.core.outbox.OutboxRelay;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.domain.matching.ProductMatcher;
import io.catalogsentinel.domain.matching.RuleBasedMatcher;
import io.catalogsentinel.platform.json.JacksonJsonCodec;
import io.catalogsentinel.platform.metrics.MicrometerConsumerMetrics;
import io.catalogsentinel.platform.metrics.MicrometerIngestionMetrics;
import io.catalogsentinel.platform.sqs.SqsClients;
import io.catalogsentinel.platform.sqs.SqsQueueClient;
import io.catalogsentinel.worker.runtime.BacklogHealthIndicator;
import io.catalogsentinel.worker.runtime.BacklogMonitor;
import io.catalogsentinel.worker.runtime.ConsumerLifecycle;
import io.catalogsentinel.worker.runtime.OutboxRelayScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.function.BooleanSupplier;

/** Wires the core's ports and the two consumers for the worker process. */
@Configuration(proxyBeanMethods = false)
public class WorkerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonCodec jsonCodec(JsonMapper jsonMapper) {
        return new JacksonJsonCodec(jsonMapper);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm, WorkerProperties props) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setTimeout((int) props.transactionTimeout().toSeconds());
        return t;
    }

    @Bean
    IngestionMetrics ingestionMetrics(MeterRegistry registry) {
        return new MicrometerIngestionMetrics(registry);
    }

    @Bean
    ConsumerMetrics consumerMetrics(MeterRegistry registry) {
        return new MicrometerConsumerMetrics(registry);
    }

    @Bean
    ListingEventMapper listingEventMapper(Clock clock) {
        return new ListingEventMapper(clock);
    }

    @Bean
    ProductMatcher productMatcher() {
        return new RuleBasedMatcher();
    }

    @Bean
    QueueNames queueNames(WorkerProperties props) {
        WorkerProperties.Queues q = props.queues();
        return new QueueNames(q.listingEvents(), q.listingEventsDlq(), q.changeEvents(), q.changeEventsDlq());
    }

    @Bean
    SqsClient sqsClient(WorkerProperties props) {
        return SqsClients.create(props.aws().region(), props.aws().sqsEndpoint());
    }

    @Bean
    QueueClient queueClient(SqsClient sqsClient) {
        return new SqsQueueClient(sqsClient);
    }

    /** Health probe used to resume consumption after a database outage. */
    @Bean
    BooleanSupplier databaseProbe(JdbcTemplate jdbc) {
        return () -> {
            try {
                Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
                return one != null && one == 1;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    @Bean
    ConsumerLifecycle listingEventsConsumer(QueueClient queue, QueueNames names, JsonCodec json,
                                            ListingEventMapper mapper, ListingIngestionService ingestion,
                                            WorkerProperties props, ConsumerMetrics metrics, BooleanSupplier databaseProbe) {
        QueueConsumer consumer = new QueueConsumer(queue, new ListingEventMessageHandler(json, mapper, ingestion),
                settings(names.listingEvents(), names.listingEventsDlq(), props.consumers().listingConcurrency(), props),
                metrics, databaseProbe);
        return new ConsumerLifecycle(consumer, props.consumers().enabled());
    }

    @Bean
    ConsumerLifecycle changeEventsConsumer(QueueClient queue, QueueNames names, JsonCodec json, MatchingService matching,
                                           WorkerProperties props, ConsumerMetrics metrics, BooleanSupplier databaseProbe) {
        QueueConsumer consumer = new QueueConsumer(queue, new ListingChangedMessageHandler(json, matching),
                settings(names.changeEvents(), names.changeEventsDlq(), props.consumers().changeConcurrency(), props),
                metrics, databaseProbe);
        return new ConsumerLifecycle(consumer, props.consumers().enabled());
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository outbox, QueueClient queue, QueueNames names, TransactionTemplate tx,
                            Clock clock, WorkerProperties props) {
        WorkerProperties.Outbox o = props.outbox();
        return new OutboxRelay(outbox, queue, names.changeEvents(), tx, clock,
                BackoffPolicy.fullJitter(o.backoffBase(), o.backoffCap()), o.batchSize(), o.maxAttempts());
    }

    @Bean
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, OutboxRepository outbox, Clock clock,
                                              WorkerProperties props) {
        return new OutboxRelayScheduler(relay, outbox, clock, props.outbox());
    }

    @Bean
    BacklogMonitor backlogMonitor(QueueClient queue, QueueNames names, OutboxRepository outbox, Clock clock,
                                  MeterRegistry registry, WorkerProperties props) {
        return new BacklogMonitor(queue, names, outbox, clock, registry, props.backlogRefresh());
    }

    @Bean
    BacklogHealthIndicator backlogHealthIndicator(BacklogMonitor monitor, Clock clock, WorkerProperties props) {
        return new BacklogHealthIndicator(monitor, clock, props.backlogRefresh().multipliedBy(4));
    }

    private static QueueConsumer.Settings settings(String queue, String dlq, int concurrency, WorkerProperties props) {
        WorkerProperties.Consumers c = props.consumers();
        return new QueueConsumer.Settings(queue, dlq, concurrency, c.maxAttempts(), c.waitTime(), c.visibilityTimeout(),
                BackoffPolicy.fullJitter(c.backoffBase(), c.backoffCap()), c.outageThreshold(), c.outagePause(),
                c.shutdownGrace());
    }
}
