package com.vendorflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for all integration tests: real Postgres (same major version as prod/docker-compose), real Flyway
 * migrations, real security filter chain. One container per JVM, shared by every test class; tests must create
 * their own data and never depend on ordering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportTestcontainers
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        // Started once for the whole JVM; Ryuk removes it when the JVM exits.
        POSTGRES.start();
    }
}
