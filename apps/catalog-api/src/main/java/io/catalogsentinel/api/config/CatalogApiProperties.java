package io.catalogsentinel.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Typed configuration under {@code catalogsentinel.*}; defaults live in application.yml.
 *
 * @param aws         AWS region and optional SQS endpoint override (ElasticMQ locally)
 * @param queues      queue names
 * @param ingestion   ingestion transaction settings
 * @param security    API-key authentication settings (see docs/security.md)
 * @param reconciliation reconciliation job poller settings (worker threads, poll interval, on/off per instance)
 */
@ConfigurationProperties(prefix = "catalogsentinel")
public record CatalogApiProperties(
        Aws aws,
        Queues queues,
        Ingestion ingestion,
        Security security,
        Reconciliation reconciliation) {

    public record Aws(String region, String sqsEndpoint) {
    }

    public record Queues(String listingEvents, String listingEventsDlq, String changeEvents, String changeEventsDlq) {
    }

    public record Ingestion(Duration transactionTimeout) {
    }

    /**
     * @param enabled whether API keys are required (true in the aws profile)
     * @param keys    API keys, each as {@code <sha256-hex-of-key>:<ROLE>[|<ROLE>]}, entries comma-separated (so a
     *                single environment variable can hold several); roles are READER, REVIEWER, INGEST and ADMIN.
     *                Only hashes are configured, never plaintext keys.
     */
    public record Security(boolean enabled, List<String> keys) {
    }

    /**
     * @param workerThreads  jobs this instance runs at once
     * @param pollInterval   how often the poller looks for claimable jobs (it is also woken on every request)
     * @param pollerEnabled  run reconciliation jobs in this instance (false to leave them to other instances)
     */
    public record Reconciliation(int workerThreads, java.time.Duration pollInterval, Boolean pollerEnabled) {
    }
}
