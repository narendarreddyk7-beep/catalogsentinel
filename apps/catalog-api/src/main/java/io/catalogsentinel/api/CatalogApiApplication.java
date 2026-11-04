package io.catalogsentinel.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CatalogSentinel REST API. Scans {@code io.catalogsentinel} so that the shared core's repositories and
 * services are picked up; application-specific wiring lives in {@code io.catalogsentinel.api.config}.
 */
@SpringBootApplication(scanBasePackages = {"io.catalogsentinel.core", "io.catalogsentinel.api"})
@ConfigurationPropertiesScan
public class CatalogApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApiApplication.class, args);
    }
}
