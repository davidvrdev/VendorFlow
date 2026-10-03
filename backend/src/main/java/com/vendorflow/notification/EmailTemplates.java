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
            case PASSWORD_RESET -> passwordReset(payload);
            case INVITATION -> invitation(payload);
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

    private RenderedEmail passwordReset(Map<String, Object> payload) {
        String token = required(payload, "token");
        String name = oneLine(String.valueOf(payload.getOrDefault("fullName", "there")));
        String link = baseUrl + "/reset-password#token=" + token;
        String subject = "Reset your VendorFlow password";
        String text = "Hi " + name + ",\n\n"
                + "We received a request to reset your VendorFlow password. Choose a new one here:\n" + link + "\n\n"
                + "This link expires in 30 minutes and can be used once. If you did not ask for this, ignore this "
                + "email: your password stays the same.\n";
        String html = "<p>Hi " + HtmlUtils.htmlEscape(name) + ",</p>"
                + "<p>We received a request to reset your VendorFlow password.</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Choose a new password</a></p>"
                + "<p>This link expires in 30 minutes and can be used once. If you did not ask for this, ignore this "
                + "email: your password stays the same.</p>";
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail invitation(Map<String, Object> payload) {
        String token = required(payload, "token");
        String organization = oneLine(required(payload, "organizationName"));
        String inviter = oneLine(String.valueOf(payload.getOrDefault("inviterName", "A teammate")));
        String role = oneLine(String.valueOf(payload.getOrDefault("role", "MEMBER")));
        String link = baseUrl + "/invite#token=" + token;
        // Names are user input: the subject is a header, so line breaks are stripped (oneLine) to prevent injection.
        String subject = inviter + " invited you to " + organization + " on VendorFlow";
        String text = inviter + " invited you to join " + organization + " on VendorFlow as " + role + ".\n\n"
                + "Accept the invitation:\n" + link + "\n\n"
                + "This invitation expires in 7 days. If you were not expecting it, ignore this email.\n";
        String html = "<p>" + HtmlUtils.htmlEscape(inviter) + " invited you to join <strong>"
                + HtmlUtils.htmlEscape(organization) + "</strong> on VendorFlow as " + HtmlUtils.htmlEscape(role)
                + ".</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Accept the invitation</a></p>"
                + "<p>This invitation expires in 7 days. If you were not expecting it, ignore this email.</p>";
        return new RenderedEmail(subject, text, html);
    }

    /** Collapses control characters/line breaks so user-supplied names cannot inject headers or fake lines. */
    private static String oneLine(String value) {
        return value.replaceAll("\\p{Cntrl}+", " ").trim();
    }

    private static String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing payload field " + key);
        }
        return value.toString();
    }
}
