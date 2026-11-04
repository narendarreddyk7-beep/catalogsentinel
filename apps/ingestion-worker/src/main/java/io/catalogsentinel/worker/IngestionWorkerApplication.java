package io.catalogsentinel.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Asynchronous processing: SQS consumers, outbox relay, queue backlog monitoring. */
@SpringBootApplication(scanBasePackages = {"io.catalogsentinel.core", "io.catalogsentinel.worker"})
@ConfigurationPropertiesScan
public class IngestionWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionWorkerApplication.class, args);
    }
}
