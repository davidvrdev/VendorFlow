package com.vendorflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request/response mapping and result classification of the Resend sender against a mock HTTP server. */
class ResendEmailSenderTest {

    static final String API_KEY = "re_TEST_SECRET_KEY_123";
    static final String FROM = "VendorFlow <notifications@mail.example.com>";

    final JsonMapper json = JsonMapper.builder().build();
    MockRestServiceServer server;
    ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new ResendEmailSender(builder, json, API_KEY, FROM, "https://api.resend.com");
    }

    private EmailMessage message(String replyTo) {
        return new EmailMessage(UUID.fromString("11111111-2222-3333-4444-555555555555"), "digest:a:b:2026-10-04",
                "vendor@example.com", "Hello <subject>", "plain text body", "<p>html body</p>",
                NotificationKind.DOCUMENT_REQUEST, replyTo);
    }

    @Test
    void sendsTheDocumentedRequestAndReturnsTheProviderId() {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "11111111-2222-3333-4444-555555555555"))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(request -> body.set(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}",
                        MediaType.APPLICATION_JSON));

        String id = sender.send(message("manager@example.com"));

        assertThat(id).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        JsonNode sent = body.get();
        assertThat(sent.get("from").asString()).isEqualTo(FROM);
        assertThat(sent.get("to")).hasSize(1);
        assertThat(sent.get("to").get(0).asString()).isEqualTo("vendor@example.com");
        assertThat(sent.get("subject").asString()).isEqualTo("Hello <subject>");
        assertThat(sent.get("html").asString()).isEqualTo("<p>html body</p>");
        assertThat(sent.get("text").asString()).isEqualTo("plain text body");
        assertThat(sent.get("reply_to").asString()).isEqualTo("manager@example.com");
        server.verify();
    }

    @Test
    void omitsReplyToWhenThereIsNone() {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(request -> body.set(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));
        sender.send(message(null));
        assertThat(body.get().has("reply_to")).isFalse();
    }

    @Test
    void successWithoutAnIdStillCountsAsSent() {
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(sender.send(message(null))).startsWith("resend-");
    }

    private EmailDeliveryException failure(HttpStatus status, String body) {
        server.reset();
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(
                withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        return (EmailDeliveryException) catchThrowable(() -> sender.send(message("manager@example.com")));
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected an exception");
    }

    @Test
    void rateLimitAndServerErrorsAreRetryable() {
        assertThat(failure(HttpStatus.TOO_MANY_REQUESTS, "{\"name\":\"rate_limit_exceeded\"}").isPermanent()).isFalse();
        assertThat(failure(HttpStatus.TOO_MANY_REQUESTS, "{\"name\":\"daily_quota_exceeded\"}").isPermanent()).isFalse();
        assertThat(failure(HttpStatus.INTERNAL_SERVER_ERROR, "{\"name\":\"application_error\"}").isPermanent()).isFalse();
        assertThat(failure(HttpStatus.SERVICE_UNAVAILABLE, "oops not json").isPermanent()).isFalse();
        assertThat(failure(HttpStatus.BAD_GATEWAY, "").isPermanent()).isFalse();
    }

    @Test
    void concurrentIdempotentRequestIsRetryableButAReusedKeyWithAnotherBodyIsPermanent() {
        assertThat(failure(HttpStatus.CONFLICT, "{\"name\":\"concurrent_idempotent_requests\"}").isPermanent())
                .isFalse();
        assertThat(failure(HttpStatus.CONFLICT, "not json").isPermanent()).isFalse();
        assertThat(failure(HttpStatus.CONFLICT, "{\"name\":\"invalid_idempotent_request\"}").isPermanent()).isTrue();
    }

    @Test
    void otherClientErrorsArePermanent() {
        assertThat(failure(HttpStatus.UNPROCESSABLE_ENTITY, "{\"name\":\"invalid_parameter\"}").isPermanent()).isTrue();
        assertThat(failure(HttpStatus.BAD_REQUEST, "{\"name\":\"validation_error\"}").isPermanent()).isTrue();
        assertThat(failure(HttpStatus.UNAUTHORIZED, "{\"name\":\"missing_api_key\"}").isPermanent()).isTrue();
        assertThat(failure(HttpStatus.FORBIDDEN, "{\"name\":\"validation_error\"}").isPermanent()).isTrue();
        assertThat(failure(HttpStatus.NOT_FOUND, "").isPermanent()).isTrue();
    }

    @Test
    void errorMessagesNeverCarryTheResponseBodyAddressesOrTheKey() {
        EmailDeliveryException e = failure(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"name\":\"invalid_parameter\",\"message\":\"The to field vendor@example.com is invalid, key "
                        + API_KEY + " https://secret.link/x#token=abc\"}");
        assertThat(e.getMessage()).isEqualTo("Resend HTTP 422 invalid_parameter");
        assertThat(e.getMessage()).doesNotContain("vendor@example.com").doesNotContain(API_KEY)
                .doesNotContain("secret.link");
        // Unknown error names are not copied (only the allowlisted vocabulary from the docs).
        assertThat(failure(HttpStatus.UNPROCESSABLE_ENTITY, "{\"name\":\"evil vendor@example.com\"}").getMessage())
                .isEqualTo("Resend HTTP 422");
    }

    @Test
    void timeoutsAndNetworkErrorsAreRetryable() {
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out talking to api.resend.com for vendor@example.com");
        });
        assertThatThrownBy(() -> sender.send(message(null))).isInstanceOfSatisfying(EmailDeliveryException.class,
                e -> {
                    assertThat(e.isPermanent()).isFalse();
                    assertThat(e.getMessage()).doesNotContain("vendor@example.com").doesNotContain("resend.com/");
                });
    }

    @Test
    void refusesToBeCreatedWithoutKeyOrFromAndNeverEchoesTheKey() {
        assertThatThrownBy(() -> new ResendEmailSender(RestClient.builder(), json, " ", FROM, "https://x.example"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendEmailSender(RestClient.builder(), json, API_KEY, "", "https://x.example"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EMAIL_FROM")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    void timeoutsAreTheDocumentedOnes() {
        assertThat(ResendEmailSender.CONNECT_TIMEOUT).hasSeconds(5);
        assertThat(ResendEmailSender.READ_TIMEOUT).hasSeconds(10);
    }
}
