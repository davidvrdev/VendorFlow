package com.vendorflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L3: names written by users or vendors must not break headers/lines or reorder text in a mail client. */
class EmailTemplatesSanitizeTest {

    /** Code points that must never survive: separators, bidi controls (incl. U+202C pop) and C0/C1 controls. */
    private static final int[] FORBIDDEN = {0x2028, 0x2029, 0x200E, 0x200F, 0x061C, 0x202A, 0x202B, 0x202C, 0x202D,
            0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x0000, 0x000A, 0x000D, 0x0085};

    private final EmailTemplates templates = new EmailTemplates("https://app.example.com", "1 Main St, Springfield");

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static void assertNoForbidden(String text) {
        for (int forbidden : FORBIDDEN) {
            assertThat(text.indexOf(forbidden)).as("U+%04X in [%s]", forbidden, text).isEqualTo(-1);
        }
    }

    @Test
    void oneLineStripsLineSeparatorsBidiControlsAndAsciiControls() {
        StringBuilder nasty = new StringBuilder("Acme");
        for (int forbidden : FORBIDDEN) {
            nasty.append(cp(forbidden)).append('x');
        }
        nasty.append("end");
        String clean = EmailTemplates.oneLine(nasty.toString());
        assertNoForbidden(clean);
        assertThat(clean).startsWith("Acme").endsWith("end");
        // Header injection attempt: the CR/LF is gone, the text stays on one line.
        assertThat(EmailTemplates.oneLine("Evil" + cp(0x0D, 0x0A) + "Bcc: a@b.c")).isEqualTo("Evil Bcc: a@b.c");
        assertThat(EmailTemplates.oneLine("a" + cp(0x2028) + "b" + cp(0x2029) + "c")).isEqualTo("a b c");
        assertThat(EmailTemplates.oneLine("a" + cp(0x202E) + "b")).isEqualTo("a b");
    }

    @Test
    void oneLineCapsTheLengthWithoutSplittingASurrogatePair() {
        String capped = EmailTemplates.oneLine("a".repeat(1000));
        assertThat(capped.length()).isLessThanOrEqualTo(EmailTemplates.MAX_LINE_LENGTH);
        assertThat(capped.charAt(capped.length() - 1)).isEqualTo((char) 0x2026);
        // Emoji (surrogate pairs) right at the cut point, with both parities of the cut.
        for (String prefix : List.of("", "x")) {
            String emoji = EmailTemplates.oneLine(prefix + cp(0x1F600).repeat(400));
            assertThat(emoji.length()).isLessThanOrEqualTo(EmailTemplates.MAX_LINE_LENGTH);
            String body = emoji.substring(0, emoji.length() - 1);
            assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1))).isFalse();
        }
        assertThat(EmailTemplates.oneLine("  short  ")).isEqualTo("short");
    }

    @Test
    void aMaliciousOrganizationNameCannotReachTheSubjectOrBody() {
        String evil = "Evil" + cp(0x202E) + " Corp" + cp(0x2028) + "Injected: header" + "x".repeat(600);
        RenderedEmail mail = templates.render(NotificationKind.VENDOR_CHASE, Map.of("organizationName", evil,
                "vendorName", "V" + cp(0x2029) + "endor", "token", "T".repeat(43), "optOutToken", "O".repeat(43),
                "types", List.of(Map.of("name", "W9", "status", "MISSING"))));
        assertNoForbidden(mail.subject());
        assertThat(mail.subject().length()).isLessThan(2 * EmailTemplates.MAX_LINE_LENGTH + 60);
        for (int forbidden : new int[] {0x2028, 0x2029, 0x202E}) {
            assertThat(mail.textBody().indexOf(forbidden)).isEqualTo(-1);
            assertThat(mail.htmlBody().indexOf(forbidden)).isEqualTo(-1);
        }
    }

    @Test
    void theChaseEmailCarriesTheFooterAndTheRfc8058Headers() {
        String optOut = "O".repeat(43);
        RenderedEmail mail = templates.render(NotificationKind.VENDOR_CHASE, Map.of("organizationName", "Acme HOA",
                "vendorName", "Vendor", "token", "T".repeat(43), "optOutToken", optOut, "replyTo", "boss@acme.example",
                "types", List.of(Map.of("name", "W9", "status", "MISSING"))));
        assertThat(mail.textBody()).contains("sent by VendorFlow on behalf of Acme HOA")
                .contains("1 Main St, Springfield");
        assertThat(mail.replyTo()).isEqualTo("boss@acme.example");
        assertThat(mail.headers()).containsEntry("List-Unsubscribe",
                "<https://app.example.com/api/v1/portal/chasing/one-click/" + optOut + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
    }
}
