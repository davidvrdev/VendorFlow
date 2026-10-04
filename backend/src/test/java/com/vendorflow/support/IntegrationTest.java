package com.vendorflow.support;

import com.vendorflow.shared.ratelimit.RateLimiter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for all integration tests: real Postgres (same major version as prod/docker-compose), real Flyway
 * migrations, real security filter chain. One container per JVM, shared by every test class; tests must create
 * their own data and never depend on ordering. Shared mutable test doubles (clock, email sender, rate limiter)
 * are reset before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportTestcontainers
@Import(TestBeans.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            // Many cached Spring contexts (one per distinct test config) each hold a connection pool.
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        // Started once for the whole JVM; Ryuk removes it when the JVM exits.
        POSTGRES.start();
    }

    /** Document files of the whole test JVM live in one temp directory, removed at JVM exit. */
    protected static final Path STORAGE_ROOT = createStorageRoot();

    private static Path createStorageRoot() {
        try {
            Path root = Files.createTempDirectory("vendorflow-test-storage");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException | RuntimeException ignored) {
                    // best effort cleanup of a temp directory
                }
            }));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.filesystem.root", STORAGE_ROOT::toString);
    }

    @Autowired protected MutableClock clock;
    @Autowired protected CapturingEmailSender emailSender;
    @Autowired protected RateLimiter rateLimiter;

    @BeforeEach
    void resetSharedTestState() {
        clock.reset();
        emailSender.reset();
        rateLimiter.reset();
    }
}
