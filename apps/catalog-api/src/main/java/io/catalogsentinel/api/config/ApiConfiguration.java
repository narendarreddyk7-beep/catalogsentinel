package io.catalogsentinel.api.config;

import io.catalogsentinel.core.ingestion.AsyncIngestionService;
import io.catalogsentinel.core.ingestion.IngestionMetrics;
import io.catalogsentinel.core.ingestion.ListingEventMapper;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.messaging.DeadLetterService;
import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.platform.json.JacksonJsonCodec;
import io.catalogsentinel.platform.metrics.MicrometerIngestionMetrics;
import io.catalogsentinel.platform.sqs.SqsClients;
import io.catalogsentinel.platform.sqs.SqsQueueClient;
import io.catalogsentinel.platform.web.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Binds the core's ports to concrete adapters for the API process. */
@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonCodec jsonCodec(JsonMapper jsonMapper) {
        return new JacksonJsonCodec(jsonMapper);
    }

    /** Programmatic transactions for the core services; READ COMMITTED plus explicit row locks. */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager, CatalogApiProperties props) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout((int) props.ingestion().transactionTimeout().toSeconds());
        return template;
    }

    @Bean
    IngestionMetrics ingestionMetrics(MeterRegistry registry) {
        return new MicrometerIngestionMetrics(registry);
    }

    @Bean
    ListingEventMapper listingEventMapper(Clock clock) {
        return new ListingEventMapper(clock);
    }

    /** The production matcher; its version tag is stored with every candidate and link. */
    @Bean
    io.catalogsentinel.domain.matching.ProductMatcher productMatcher() {
        return new io.catalogsentinel.domain.matching.RuleBasedMatcher();
    }

    @Bean
    QueueNames queueNames(CatalogApiProperties props) {
        CatalogApiProperties.Queues q = props.queues();
        return new QueueNames(q.listingEvents(), q.listingEventsDlq(), q.changeEvents(), q.changeEventsDlq());
    }

    @Bean
    SqsClient sqsClient(CatalogApiProperties props) {
        return SqsClients.create(props.aws().region(), props.aws().sqsEndpoint());
    }

    @Bean
    QueueClient queueClient(SqsClient sqsClient) {
        return new SqsQueueClient(sqsClient);
    }

    @Bean
    DeadLetterService deadLetterService(QueueClient queueClient, QueueNames queues) {
        return new DeadLetterService(queueClient, queues);
    }

    @Bean
    AsyncIngestionService asyncIngestionService(ListingEventMapper mapper, QueueClient queueClient, QueueNames queues,
                                                JsonCodec json, Clock clock) {
        return new AsyncIngestionService(mapper, queueClient, queues.listingEvents(), json, clock);
    }

    /** Registered as a servlet filter by Spring Boot; ordered first so every log line carries the id. */
    @Bean
    CorrelationIdFilter correlationIdFilter() {
        return new OrderedCorrelationIdFilter();
    }

    static final class OrderedCorrelationIdFilter extends CorrelationIdFilter implements org.springframework.core.Ordered {
        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
