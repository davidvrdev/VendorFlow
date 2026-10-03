package com.vendorflow.notification;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds subject/text/html from a notification kind + payload at SEND time, so raw tokens live in the payload only
 * until delivery. Every interpolated value is HTML-escaped (names are user input). Links put the token in the URL
 * fragment, which browsers never send to servers or in Referer headers (docs/API.md).
 */
@Component
public class EmailTemplates {

    private final String baseUrl;

    public EmailTemplates(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public RenderedEmail render(NotificationKind kind, Map<String, Object> payload) {
        return switch (kind) {
            case EMAIL_VERIFICATION -> emailVerification(payload);
            default -> throw new IllegalArgumentException("No template for kind " + kind);
        };
    }

    private RenderedEmail emailVerification(Map<String, Object> payload) {
        String token = required(payload, "token");
        String name = String.valueOf(payload.getOrDefault("fullName", "there"));
        String link = baseUrl + "/verify-email#token=" + token;
        String subject = "Verify your email for VendorFlow";
        String text = "Hi " + name + ",\n\n"
                + "Confirm your email address to finish setting up VendorFlow:\n" + link + "\n\n"
                + "This link expires in 24 hours. If you did not create an account, ignore this email.\n";
        String html = "<p>Hi " + HtmlUtils.htmlEscape(name) + ",</p>"
                + "<p>Confirm your email address to finish setting up VendorFlow:</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Verify email</a></p>"
                + "<p>This link expires in 24 hours. If you did not create an account, ignore this email.</p>";
        return new RenderedEmail(subject, text, html);
    }

    private static String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing payload field " + key);
        }
        return value.toString();
    }
}
