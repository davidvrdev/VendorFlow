package com.vendorflow.notification;

import java.util.List;
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
            case DOCUMENT_REQUEST -> documentRequest(payload);
            case COMPLIANCE_DIGEST -> complianceDigest(payload);
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

    private RenderedEmail documentRequest(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        String type = oneLine(required(payload, "documentTypeName"));
        String vendor = oneLine(String.valueOf(payload.getOrDefault("vendorName", "")));
        String contact = oneLine(String.valueOf(payload.getOrDefault("contactName", "")));
        String requester = oneLine(String.valueOf(payload.getOrDefault("requesterName", "A team member")));
        String replyTo = payload.get("replyTo") == null ? null : payload.get("replyTo").toString();
        String greeting = contact.isBlank() ? "Hello" : "Hello " + contact;
        // Subject is a header: names are one-lined above (no CR/LF injection).
        String subject = organization + " requests your " + type;
        String text = greeting + ",\n\n"
                + requester + " at " + organization + " asked VendorFlow to request the following document"
                + (vendor.isBlank() ? "" : " for " + vendor) + ":\n\n"
                + "  " + type + "\n\n"
                + "Please reply to this email with the document attached.\n";
        String html = "<p>" + HtmlUtils.htmlEscape(greeting) + ",</p>"
                + "<p>" + HtmlUtils.htmlEscape(requester) + " at <strong>" + HtmlUtils.htmlEscape(organization)
                + "</strong> asked VendorFlow to request the following document"
                + (vendor.isBlank() ? "" : " for " + HtmlUtils.htmlEscape(vendor)) + ":</p>"
                + "<p><strong>" + HtmlUtils.htmlEscape(type) + "</strong></p>"
                + "<p>Please reply to this email with the document attached.</p>";
        return new RenderedEmail(subject, text, html, replyTo);
    }

    @SuppressWarnings("unchecked")
    private RenderedEmail complianceDigest(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        List<Map<String, Object>> expired = list(payload.get("expired"));
        List<Map<String, Object>> expiring = list(payload.get("expiring"));
        int missing = payload.get("missingCount") instanceof Number n ? n.intValue() : 0;
        String link = baseUrl + "/dashboard";

        List<String> parts = new java.util.ArrayList<>();
        if (!expired.isEmpty()) {
            parts.add(expired.size() + (expired.size() == 1 ? " document" : " documents") + " expired");
        }
        if (!expiring.isEmpty()) {
            parts.add(expiring.size() + " expiring soon");
        }
        String subject = "VendorFlow: " + (parts.isEmpty() ? "compliance update" : String.join(", ", parts));
        if (expired.isEmpty() && !expiring.isEmpty()) {
            subject = "VendorFlow: " + expiring.size() + (expiring.size() == 1 ? " document" : " documents")
                    + " expiring soon";
        }

        StringBuilder text = new StringBuilder("Compliance update for " + organization + "\n\n");
        StringBuilder html = new StringBuilder("<p>Compliance update for <strong>" + HtmlUtils.htmlEscape(organization)
                + "</strong></p>");
        if (!expired.isEmpty()) {
            text.append("Expired:\n");
            html.append("<h3>Expired</h3><ul>");
            for (Map<String, Object> item : expired) {
                String line = oneLine(str(item, "vendorName")) + " - " + oneLine(str(item, "documentType"))
                        + " (expired " + str(item, "expirationDate") + ")";
                text.append("  - ").append(line).append('\n');
                html.append("<li>").append(HtmlUtils.htmlEscape(line)).append("</li>");
            }
            html.append("</ul>");
            text.append('\n');
        }
        if (!expiring.isEmpty()) {
            text.append("Expiring soon:\n");
            html.append("<h3>Expiring soon</h3><ul>");
            for (Map<String, Object> item : expiring) {
                Object days = item.get("daysLeft");
                String line = oneLine(str(item, "vendorName")) + " - " + oneLine(str(item, "documentType"))
                        + " (expires " + str(item, "expirationDate") + ", " + days
                        + (days instanceof Number n && n.intValue() == 1 ? " day left)" : " days left)");
                text.append("  - ").append(line).append('\n');
                html.append("<li>").append(HtmlUtils.htmlEscape(line)).append("</li>");
            }
            html.append("</ul>");
            text.append('\n');
        }
        String missingLine = missing + (missing == 1 ? " required document is" : " required documents are")
                + " currently missing.";
        text.append(missingLine).append("\n\nOpen your dashboard: ").append(link).append('\n');
        html.append("<p>").append(HtmlUtils.htmlEscape(missingLine)).append("</p><p><a href=\"")
                .append(HtmlUtils.htmlEscape(link)).append("\">Open your dashboard</a></p>");
        return new RenderedEmail(subject, text.toString(), html.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? "" : v.toString();
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
