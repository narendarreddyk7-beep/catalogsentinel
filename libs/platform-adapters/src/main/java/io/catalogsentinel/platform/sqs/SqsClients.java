package io.catalogsentinel.platform.sqs;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Builds {@link SqsClient}s. With an endpoint override the client talks to an SQS-compatible server
 * (ElasticMQ in docker compose); without one it uses the regional AWS endpoint and the default credentials
 * chain (ECS task role in AWS). No credentials are ever configured in code.
 */
public final class SqsClients {

    private SqsClients() {
    }

    public static SqsClient create(String region, String endpointOverride) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        // Long polls take up to 20 s; leave headroom so the HTTP call does not time out first.
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .build());
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }
}
