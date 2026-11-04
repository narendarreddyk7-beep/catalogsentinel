package io.catalogsentinel.core.testsupport;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * Real PostgreSQL 16 for integration tests, shared by every test class in the JVM.
 *
 * <p>By default a Testcontainers {@code postgres:16-alpine} container is started (and reaped by Testcontainers
 * when the JVM exits). Set {@code CATALOGSENTINEL_TEST_JDBC_URL} (plus {@code _USER}/{@code _PASSWORD}) to run
 * against an existing database instead, for example a CI service container. Flyway applies the production
 * migrations either way, so tests always exercise the real schema.
 */
public final class TestDatabase {

    public static final String IMAGE = "postgres:16-alpine";
    private static DataSource dataSource;

    private TestDatabase() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = create();
        }
        return dataSource;
    }

    private static DataSource create() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        String url = System.getenv("CATALOGSENTINEL_TEST_JDBC_URL");
        if (url != null && !url.isBlank()) {
            ds.setURL(url);
            ds.setUser(envOr("CATALOGSENTINEL_TEST_JDBC_USER", "catalog"));
            ds.setPassword(envOr("CATALOGSENTINEL_TEST_JDBC_PASSWORD", "catalog_local_dev"));
        } else {
            PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(IMAGE));
            container.start();
            ds.setURL(container.getJdbcUrl());
            ds.setUser(container.getUsername());
            ds.setPassword(container.getPassword());
        }
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }

    /** Empties every application table and resets identities. */
    public static void reset(DataSource ds) {
        new JdbcTemplate(ds).execute(TestSchema.RESET_SQL);
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
