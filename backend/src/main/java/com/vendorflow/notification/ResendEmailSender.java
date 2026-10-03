package com.vendorflow.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Email delivery through Resend's HTTP API (no SDK). Selected with {@code app.email.provider=resend}.
 *
 * <p><b>Resend facts</b> (official docs, checked 2026-10-04; do not invent fields beyond these):
 * <ul>
 *   <li>Send email: {@code POST https://api.resend.com/emails}
 *       (https://resend.com/docs/api-reference/emails/send-email). Header {@code Authorization: Bearer re_...}.
 *       Body: required {@code from} (string, "Name &lt;email&gt;" allowed), {@code to} (string or string[], max 50),
 *       {@code subject}; optional {@code html}, {@code text}, {@code reply_to} (string or string[]), ...
 *       Success: HTTP 200 {@code {"id": "..."}}.</li>
 *   <li>Idempotency (https://resend.com/docs/dashboard/emails/idempotency-keys): header {@code Idempotency-Key},
 *       1..256 characters, kept 24 h; a replay returns the same response without sending again. 409
 *       {@code invalid_idempotent_request} = same key with a different payload; 409
 *       {@code concurrent_idempotent_requests} = the first request is still in flight.</li>
 *   <li>Errors (https://resend.com/docs/api-reference/errors): 400 validation_error / invalid_idempotency_key,
 *       401 missing_api_key, 403 (restricted/suspended key, unverified domain), 404, 405, 409 (above),
 *       422 invalid_parameter / missing_required_field, 429 rate_limit_exceeded / daily_quota_exceeded /
 *       monthly_quota_exceeded, 500 application_error, 503 service_unavailable. The docs do not define a JSON schema
 *       for error bodies; we only look for a string field {@code name} against an allowlist and never store bodies.</li>
 *   <li>Rate limit (https://resend.com/docs/api-reference/rate-limit): default 10 requests/second per team; 429 with
 *       {@code retry-after} header. Our dispatcher backoff (1m, 5m, ...) is far slower, so the header is not used.</li>
 * </ul>
 *
 * <p><b>Classification</b> (docs/API.md Phase 6): 2xx = SENT (the returned id is stored); 429, 5xx, 408, 409
 * {@code concurrent_idempotent_requests} (or an unreadable 409) and any network error/timeout = retryable
 * ({@link EmailDeliveryException} non-permanent); every other 4xx = permanent (DEAD at once).
 *
 * <p><b>Logging</b>: never the API key, request/response bodies, subject, links or addresses. Messages of the
 * exceptions thrown here contain only the HTTP status and, for classification, an allowlisted error name.
 *
 * <p>Unverified against the real service until the owner provides an API key and a verified sending domain.
 */
@Component
@Profile("!e2e")
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** Error names we may copy into last_error (fixed vocabulary from the Resend docs; nothing from the body itself). */
    private static final Set<String> KNOWN_ERROR_NAMES = Set.of("validation_error", "invalid_idempotency_key",
            "missing_api_key", "restricted_api_key", "invalid_api_key", "suspended_api_key", "invalid_permission",
            "not_found", "method_not_allowed", "concurrent_idempotent_requests", "invalid_idempotent_request",
            "invalid_attachment", "invalid_parameter", "missing_required_field", "missing_required_parameter",
            "daily_quota_exceeded", "monthly_quota_exceeded", "rate_limit_exceeded", "application_error",
            "service_unavailable");

    private final RestClient client;
    private final JsonMapper json;
    private final String from;

    @Autowired
    public ResendEmailSender(JsonMapper json, @Value("${app.email.resend.api-key:}") String apiKey,
            @Value("${app.email.from:}") String from,
            @Value("${app.email.resend.base-url:https://api.resend.com}") String baseUrl) {
        this(RestClient.builder().requestFactory(requestFactory()), json, apiKey, from, baseUrl);
    }

    /** Tests pass a builder bound to MockRestServiceServer. */
    ResendEmailSender(RestClient.Builder builder, JsonMapper json, String apiKey, String from, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.email.provider=resend requires RESEND_API_KEY.");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("app.email.provider=resend requires EMAIL_FROM.");
        }
        this.json = json;
        this.from = from;
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public String send(EmailMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        body.put("html", message.htmlBody());
        body.put("text", message.textBody());
        if (message.replyTo() != null && !message.replyTo().isBlank()) {
            body.put("reply_to", message.replyTo());
        }
        String payload = json.writeValueAsString(body);

        Response response;
        try {
            response = client.post().uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", message.notificationId().toString())
                    .body(payload)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            // Timeouts, connection refused, TLS errors...: transient by nature. The cause text may hold the host;
            // keep only its class.
            throw new EmailDeliveryException("Resend unreachable (" + rootName(e) + ")", false);
        }

        int status = response.status();
        if (status >= 200 && status < 300) {
            String id = idOf(response.body());
            if (id == null) {
                log.warn("Resend accepted a message without an id: notificationId={}", message.notificationId());
                return "resend-" + message.notificationId();
            }
            return id;
        }
        String name = errorName(response.body());
        boolean retryable = status == 429 || status >= 500 || status == 408
                || (status == 409 && !"invalid_idempotent_request".equals(name));
        String description = "Resend HTTP " + status + (name == null ? "" : " " + name);
        throw new EmailDeliveryException(description, !retryable);
    }

    private record Response(int status, String body) {
    }

    private String idOf(String body) {
        try {
            JsonNode id = json.readTree(body).get("id");
            return id != null && id.isString() && !id.asString().isBlank() ? id.asString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String errorName(String body) {
        try {
            JsonNode name = json.readTree(body).get("name");
            return name != null && name.isString() && KNOWN_ERROR_NAMES.contains(name.asString()) ? name.asString()
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String rootName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
