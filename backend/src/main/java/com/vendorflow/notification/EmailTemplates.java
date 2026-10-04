package com.vendorflow.notification;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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

    /** Upper bound of any interpolated name/line: a vendor-controlled value must not blow up a subject or a line. */
    static final int MAX_LINE_LENGTH = 300;
    /**
     * C0/C1 controls (CR, LF, NUL...), the Unicode line/paragraph separators and the bidirectional controls (LRM, RLM, ALM,
     * embeddings/overrides U+202A-202E, isolates U+2066-2069): the last ones can visually reorder a name or a link in a mail client.
     */
    private static final Pattern UNSAFE_CHARS =
            Pattern.compile("[\\p{Cc}\\u2028\\u2029\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]+");

    private final String baseUrl;
    private final String postalAddress;

    public EmailTemplates(@Value("${app.base-url}") String baseUrl,
            @Value("${vendorflow.mail.postal-address:}") String postalAddress) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.postalAddress = oneLine(postalAddress == null ? "" : postalAddress);
    }

    public RenderedEmail render(NotificationKind kind, Map<String, Object> payload) {
        return switch (kind) {
            case EMAIL_VERIFICATION -> emailVerification(payload);
            case PASSWORD_RESET -> passwordReset(payload);
            case PASSWORD_CHANGED -> passwordChanged(payload);
            case INVITATION -> invitation(payload);
            case DOCUMENT_REQUEST -> documentRequest(payload);
            case PORTAL_UPLOAD -> portalUpload(payload);
            case COMPLIANCE_DIGEST -> complianceDigest(payload);
            case VENDOR_CHASE -> vendorChase(payload);
            case CHASING_STAFF_NOTICE -> chasingStaffNotice(payload);
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

    /** Security notice (ASVS V2.1.6/3.7.1): no link and no secret, so a leaked mailbox copy gives nothing. */
    private RenderedEmail passwordChanged(Map<String, Object> payload) {
        String name = oneLine(String.valueOf(payload.getOrDefault("fullName", "there")));
        String subject = "Your VendorFlow password was changed";
        String text = "Hi " + name + ",\n\n"
                + "The password for your VendorFlow account was just changed and all other sessions were signed out.\n\n"
                + "If this was you, nothing more to do. If it was not, reset your password right away at " + baseUrl
                + "/forgot-password and contact support.\n";
        String html = "<p>Hi " + HtmlUtils.htmlEscape(name) + ",</p>"
                + "<p>The password for your VendorFlow account was just changed and all other sessions were signed out.</p>"
                + "<p>If this was you, nothing more to do. If it was not, reset your password right away at "
                + HtmlUtils.htmlEscape(baseUrl + "/forgot-password") + " and contact support.</p>";
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

    /**
     * Portal variant of the document request (ADR-0011): the payload carries the raw portal token (scrubbed by the
     * dispatcher after delivery) and the requested type names. The link keeps the token in the fragment.
     */
    private RenderedEmail portalDocumentRequest(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        String types = oneLine(required(payload, "documentTypeName"));
        String vendor = oneLine(String.valueOf(payload.getOrDefault("vendorName", "")));
        String contact = oneLine(String.valueOf(payload.getOrDefault("contactName", "")));
        String requester = oneLine(String.valueOf(payload.getOrDefault("requesterName", "A team member")));
        String replyTo = payload.get("replyTo") == null ? null : payload.get("replyTo").toString();
        String expires = oneLine(String.valueOf(payload.getOrDefault("expiresAt", "")));
        String link = baseUrl + "/portal#token=" + required(payload, "token");
        String greeting = contact.isBlank() ? "Hello" : "Hello " + contact;
        String subject = organization + " requests your " + types;
        String text = greeting + ",\n\n"
                + requester + " at " + organization + " asked VendorFlow to request the following"
                + (vendor.isBlank() ? "" : " for " + vendor) + ":\n\n"
                + "  " + types + "\n\n"
                + "Upload the files securely here (no account needed):\n" + link + "\n\n"
                + (expires.isBlank() ? "" : "This link expires on " + expires + ". ")
                + "Do not forward it: anyone with the link can upload files for your company.\n";
        String html = "<p>" + HtmlUtils.htmlEscape(greeting) + ",</p>"
                + "<p>" + HtmlUtils.htmlEscape(requester) + " at <strong>" + HtmlUtils.htmlEscape(organization)
                + "</strong> asked VendorFlow to request the following"
                + (vendor.isBlank() ? "" : " for " + HtmlUtils.htmlEscape(vendor)) + ":</p>"
                + "<p><strong>" + HtmlUtils.htmlEscape(types) + "</strong></p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Upload your documents</a> (no account needed)</p>"
                + "<p>" + (expires.isBlank() ? "" : "This link expires on " + HtmlUtils.htmlEscape(expires) + ". ")
                + "Do not forward it: anyone with the link can upload files for your company.</p>";
        return new RenderedEmail(subject, text, html, replyTo);
    }

    /** Staff notice: a vendor uploaded through a portal link. No filename (vendor-controlled) and no secret. */
    private RenderedEmail portalUpload(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        String vendor = oneLine(required(payload, "vendorName"));
        String type = oneLine(required(payload, "documentTypeName"));
        String link = baseUrl + "/vendors/" + oneLine(required(payload, "vendorId"));
        String subject = vendor + " uploaded a document to " + organization;
        String text = vendor + " used an upload link to send a document (" + type + ") to " + organization + ".\n\n"
                + "It is waiting for your review: " + link + "\n";
        String html = "<p><strong>" + HtmlUtils.htmlEscape(vendor) + "</strong> used an upload link to send a document ("
                + HtmlUtils.htmlEscape(type) + ") to " + HtmlUtils.htmlEscape(organization) + ".</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Review it</a></p>";
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail documentRequest(Map<String, Object> payload) {
        if (Boolean.TRUE.equals(payload.get("portal"))) {
            return portalDocumentRequest(payload);
        }
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

    /**
     * Automated follow-up to a vendor (ADR-0012). The payload carries two raw secrets, scrubbed after delivery: the portal
     * upload token (token) and the unsubscribe token (optOutToken). Both links keep the token in the fragment.
     */
    private RenderedEmail vendorChase(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        String vendor = oneLine(String.valueOf(payload.getOrDefault("vendorName", "")));
        String contact = oneLine(String.valueOf(payload.getOrDefault("contactName", "")));
        String expires = oneLine(String.valueOf(payload.getOrDefault("expiresAt", "")));
        String uploadLink = baseUrl + "/portal#token=" + required(payload, "token");
        String optOutLink = baseUrl + "/portal/unsubscribe#token=" + required(payload, "optOutToken");
        String replyTo = payload.get("replyTo") == null ? null : oneLine(payload.get("replyTo").toString());
        String greeting = contact.isBlank() ? "Hello" : "Hello " + contact;
        String attempt = payload.get("attempt") instanceof Number a && payload.get("maxAttempts") instanceof Number m
                ? " (reminder " + a.intValue() + " of " + m.intValue() + ")" : "";
        List<String> lines = new java.util.ArrayList<>();
        for (Map<String, Object> t : list(payload.get("types"))) {
            String status = str(t, "status");
            String date = str(t, "expirationDate");
            String what = switch (status) {
                case "EXPIRED" -> "expired" + (date.isBlank() ? "" : " on " + date);
                case "EXPIRING" -> "expires" + (date.isBlank() ? " soon" : " on " + date);
                default -> "missing";
            };
            lines.add(oneLine(str(t, "name")) + " - " + what);
        }
        String subject = organization + " still needs documents" + (vendor.isBlank() ? "" : " from " + vendor);
        StringBuilder text = new StringBuilder(greeting + ",\n\n" + organization
                + " uses VendorFlow to keep vendor paperwork up to date" + attempt + ". The following"
                + (vendor.isBlank() ? "" : " for " + vendor) + " need your attention:\n\n");
        StringBuilder html = new StringBuilder("<p>" + HtmlUtils.htmlEscape(greeting) + ",</p><p><strong>"
                + HtmlUtils.htmlEscape(organization) + "</strong> uses VendorFlow to keep vendor paperwork up to date"
                + HtmlUtils.htmlEscape(attempt) + ". The following"
                + (vendor.isBlank() ? "" : " for " + HtmlUtils.htmlEscape(vendor)) + " need your attention:</p><ul>");
        for (String line : lines) {
            text.append("  - ").append(line).append('\n');
            html.append("<li>").append(HtmlUtils.htmlEscape(line)).append("</li>");
        }
        html.append("</ul>");
        text.append("\nUpload the files securely here (no account needed):\n").append(uploadLink).append("\n\n")
                .append(expires.isBlank() ? "" : "This link expires on " + expires + ". ")
                .append("Do not forward it: anyone with the link can upload files for your company.\n\n")
                .append("Do not want these automatic reminders? Stop them here:\n").append(optOutLink).append('\n');
        html.append("<p><a href=\"").append(HtmlUtils.htmlEscape(uploadLink))
                .append("\">Upload your documents</a> (no account needed)</p><p>")
                .append(expires.isBlank() ? "" : "This link expires on " + HtmlUtils.htmlEscape(expires) + ". ")
                .append("Do not forward it: anyone with the link can upload files for your company.</p>")
                .append("<p>Do not want these automatic reminders? <a href=\"").append(HtmlUtils.htmlEscape(optOutLink))
                .append("\">Stop them here</a>.</p>");
        // Identification (CAN-SPAM, and plain honesty: the vendor never heard of VendorFlow): who sent it, for whom, where.
        String sentBy = "This email was sent by VendorFlow on behalf of " + organization + "."
                + (postalAddress.isBlank() ? "" : " VendorFlow, " + postalAddress);
        text.append("\n--\n").append(sentBy).append('\n');
        html.append("<hr><p style=\"font-size:12px;color:#555\">").append(HtmlUtils.htmlEscape(sentBy)).append("</p>");
        // RFC 8058 one-click unsubscribe: the mail client POSTs to the URL (no cookies, no JS, no page). The token is in the
        // path because the header carries a URL; the endpoint answers GET with 405 so a prefetch cannot unsubscribe anyone.
        Map<String, String> headers = Map.of(
                "List-Unsubscribe", "<" + baseUrl + "/api/v1/portal/chasing/one-click/" + required(payload, "optOutToken") + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        return new RenderedEmail(subject, text.toString(), html.toString(), replyTo, headers);
    }

    /** Staff notices of the chasing feature: no token and no upload link, only names and a link into the app. */
    private RenderedEmail chasingStaffNotice(Map<String, Object> payload) {
        String organization = oneLine(required(payload, "organizationName"));
        String event = required(payload, "event");
        List<Map<String, Object>> items = list(payload.get("items"));
        String link = baseUrl + "/dashboard";
        String subject;
        String intro;
        List<String> lines = new java.util.ArrayList<>();
        switch (event) {
            case "EXHAUSTED" -> {
                subject = items.size() + (items.size() == 1 ? " vendor has" : " vendors have")
                        + " not responded to automatic reminders";
                intro = "Automatic reminders were sent the maximum number of times and these vendors still have "
                        + "missing, expired or expiring documents. They will not be chased again; please follow up yourself:";
                for (Map<String, Object> i : items) {
                    lines.add(oneLine(str(i, "vendorName")) + " (" + str(i, "attempts") + " reminders sent)");
                }
            }
            case "OPTED_OUT" -> {
                subject = "A vendor stopped automatic reminders";
                intro = "A vendor used the unsubscribe link in a reminder. Automatic reminders are paused for them:";
                for (Map<String, Object> i : items) {
                    lines.add(oneLine(str(i, "vendorName")));
                }
            }
            default -> {
                subject = "Automatic reminders sent to " + items.size() + (items.size() == 1 ? " vendor" : " vendors");
                intro = "VendorFlow sent these vendors a reminder with a fresh upload link today:";
                for (Map<String, Object> i : items) {
                    Object types = i.get("types");
                    lines.add(oneLine(str(i, "vendorName")) + (types instanceof List<?> l && !l.isEmpty()
                            ? " - " + oneLine(l.stream().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(", ")))
                            : ""));
                }
            }
        }
        StringBuilder text = new StringBuilder(organization + "\n\n" + intro + "\n\n");
        StringBuilder html = new StringBuilder("<p><strong>" + HtmlUtils.htmlEscape(organization) + "</strong></p><p>"
                + HtmlUtils.htmlEscape(intro) + "</p><ul>");
        for (String line : lines) {
            text.append("  - ").append(line).append('\n');
            html.append("<li>").append(HtmlUtils.htmlEscape(line)).append("</li>");
        }
        text.append("\nOpen your dashboard: ").append(link).append('\n');
        html.append("</ul><p><a href=\"").append(HtmlUtils.htmlEscape(link)).append("\">Open your dashboard</a></p>");
        return new RenderedEmail(subject, text.toString(), html.toString());
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

    /**
     * Collapses control characters, line/paragraph separators and bidi controls so user-supplied names cannot inject
     * headers, fake lines or reorder text, and caps the length.
     */
    static String oneLine(String value) {
        String cleaned = UNSAFE_CHARS.matcher(value).replaceAll(" ").trim();
        if (cleaned.length() <= MAX_LINE_LENGTH) {
            return cleaned;
        }
        int end = MAX_LINE_LENGTH - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
            end--; // never cut a surrogate pair in half
        }
        return cleaned.substring(0, end).trim() + "…";
    }

    private static String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing payload field " + key);
        }
        return value.toString();
    }
}
