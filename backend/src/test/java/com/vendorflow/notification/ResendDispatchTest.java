package com.vendorflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.vendorflow.support.IntegrationTest;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The Resend sender wired to the real dispatcher (real Postgres, real backoff) against a mock HTTP server. */
class ResendDispatchTest extends IntegrationTest {

    @Autowired OutboxService outbox;
    @Autowired NotificationRepository repository;
    @Autowired EmailTemplates templates;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;

    MockRestServiceServer server;
    OutboxDispatcher dispatcher;
    String key;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from notification");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailSender sender = new ResendEmailSender(builder, json, "re_test_key", "VendorFlow <n@mail.example>",
                "https://api.resend.com");
        dispatcher = new OutboxDispatcher(repository, sender, templates, txManager, clock, 20);
        key = "k-" + UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> outbox.enqueue(NotificationKind.DOCUMENT_REQUEST,
                null, "vendor@example.com", key, Map.of("organizationName", "Org", "documentTypeName", "W-9",
                        "replyTo", "boss@example.com")));
    }

    Map<String, Object> row() {
        return jdbc.queryForMap("select * from notification where idempotency_key = ?", key);
    }

    String notificationId() {
        return row().get("id").toString();
    }

    @Test
    void successStoresTheProviderIdAndSendsTheNotificationIdAsIdempotencyKey() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(header("Idempotency-Key", notificationId()))
                .andRespond(withSuccess("{\"id\":\"resend-id-1\"}", MediaType.APPLICATION_JSON));
        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("SENT");
        assertThat(row().get("provider_message_id")).isEqualTo("resend-id-1");
        server.verify();
    }

    @Test
    void rateLimitAndServerErrorsAreRetriedWithBackoffUsingTheSameIdempotencyKeyThenSent() {
        String id = notificationId();
        server.expect(once(), requestTo("https://api.resend.com/emails")).andExpect(header("Idempotency-Key", id))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"name\":\"rate_limit_exceeded\"}"));
        server.expect(once(), requestTo("https://api.resend.com/emails")).andExpect(header("Idempotency-Key", id))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo("https://api.resend.com/emails")).andExpect(header("Idempotency-Key", id))
                .andRespond(withSuccess("{\"id\":\"resend-id-2\"}", MediaType.APPLICATION_JSON));

        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("attempts")).isEqualTo(1);
        assertThat((String) row().get("last_error")).isEqualTo("EmailDeliveryException: Resend HTTP 429 rate_limit_exceeded");
        dispatcher.dispatchBatch(); // not due yet: no request
        clock.advance(Duration.ofMinutes(2));
        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("attempts")).isEqualTo(2);
        clock.advance(Duration.ofMinutes(10));
        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("SENT");
        assertThat(row().get("provider_message_id")).isEqualTo("resend-id-2");
        server.verify();
    }

    @Test
    void validationErrorGoesDeadAtOnceWithoutRetries() {
        server.expect(once(), requestTo("https://api.resend.com/emails")).andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"name\":\"invalid_parameter\"}"));
        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("DEAD");
        assertThat(row().get("attempts")).isEqualTo(1);
        assertThat((String) row().get("last_error")).isEqualTo("EmailDeliveryException: Resend HTTP 422 invalid_parameter");
        clock.advance(Duration.ofDays(1));
        dispatcher.dispatchBatch(); // would fail the test (unexpected request) if it were retried
        assertThat(row().get("attempts")).isEqualTo(1);
        server.verify();
    }

    @Test
    void timeoutIsRetryable() {
        server.expect(once(), requestTo("https://api.resend.com/emails")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });
        dispatcher.dispatchBatch();
        assertThat(row().get("status")).isEqualTo("FAILED");
        assertThat(row().get("attempts")).isEqualTo(1);
        assertThat((String) row().get("last_error")).contains("SocketTimeoutException");
    }
}
