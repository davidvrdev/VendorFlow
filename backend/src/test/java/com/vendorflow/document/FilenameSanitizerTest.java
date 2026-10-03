package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.document.application.ContentDispositions;
import com.vendorflow.document.application.FileKind;
import com.vendorflow.document.application.FilenameSanitizer;
import org.junit.jupiter.api.Test;

/** Pure unit tests: file name sanitizing, magic-byte kinds, Content-Disposition building. */
class FilenameSanitizerTest {

    @Test
    void unixTraversalKeepsOnlyTheLastSegment() {
        var r = FilenameSanitizer.sanitize("../../etc/passwd.pdf");
        assertThat(r.filename()).isEqualTo("passwd.pdf");
        assertThat(r.extension()).isEqualTo("pdf");
    }

    @Test
    void windowsPathKeepsOnlyTheLastSegment() {
        assertThat(FilenameSanitizer.sanitize("C:\\x\\y.pdf").filename()).isEqualTo("y.pdf");
        assertThat(FilenameSanitizer.sanitize("..\\..\\boot.ini.png").filename()).isEqualTo("boot.ini.png");
        assertThat(FilenameSanitizer.sanitize("a/b\\c/d.jpg").filename()).isEqualTo("d.jpg");
    }

    @Test
    void bidiOverrideAndOtherInvisibleCharactersAreStripped() {
        // U+202E (right-to-left override) would display "gnp.pdf" reversed as "fdp.png"-style spoofing.
        var r = FilenameSanitizer.sanitize("\u202Egnp.pdf");
        assertThat(r.filename()).isEqualTo("gnp.pdf");
        assertThat(r.extension()).isEqualTo("pdf");
        assertThat(FilenameSanitizer.sanitize("a\u200Bb\u0000c\u0007.pdf").filename()).isEqualTo("abc.pdf");
        assertThat(FilenameSanitizer.sanitize("a\uFEFF.pdf").filename()).isEqualTo("a.pdf");
    }

    @Test
    void whitespaceIsCollapsedAndTrimmed() {
        assertThat(FilenameSanitizer.sanitize("  my \t\n  insurance   cert .pdf ").filename())
                .isEqualTo("my insurance cert.pdf");
        assertThat(FilenameSanitizer.sanitize("a\u00A0\u2003b.pdf").filename()).isEqualTo("a b.pdf");
    }

    @Test
    void veryLongNamesAreCutToTheLimitKeepingTheExtension() {
        var r = FilenameSanitizer.sanitize("x".repeat(1000) + ".PDF");
        assertThat(r.filename()).hasSize(255).endsWith("x.PDF");
        assertThat(r.extension()).isEqualTo("pdf");
        // Code points outside the BMP are not split in half.
        var emoji = FilenameSanitizer.sanitize("\uD83D\uDE00".repeat(400) + ".png");
        assertThat(emoji.filename().codePointCount(0, emoji.filename().length())).isEqualTo(255);
        assertThat(emoji.filename()).endsWith(".png").doesNotContain("\uFFFD");
    }

    @Test
    void nameWithoutExtensionHasNoExtension() {
        var r = FilenameSanitizer.sanitize("README");
        assertThat(r.filename()).isEqualTo("README");
        assertThat(r.extension()).isEmpty();
        assertThat(FilenameSanitizer.sanitize("trailingdot.").extension()).isEmpty();
        assertThat(FilenameSanitizer.sanitize("..").extension()).isEmpty();
    }

    @Test
    void emptyNullAndExtensionOnlyNames() {
        assertThat(FilenameSanitizer.sanitize(null).filename()).isEmpty();
        assertThat(FilenameSanitizer.sanitize("   ").filename()).isEmpty();
        assertThat(FilenameSanitizer.sanitize("dir/").filename()).isEmpty();
        var r = FilenameSanitizer.sanitize(".pdf");
        assertThat(r.filename()).isEqualTo("document.pdf");
        assertThat(r.extension()).isEqualTo("pdf");
    }

    @Test
    void onlyTheLastExtensionCounts() {
        assertThat(FilenameSanitizer.sanitize("invoice.pdf.exe").extension()).isEqualTo("exe");
        assertThat(FilenameSanitizer.sanitize("shell.php.jpg").extension()).isEqualTo("jpg");
    }

    @Test
    void fileKindsKnowTheirExtensionsAndMagicBytes() {
        assertThat(FileKind.forExtension("pdf")).contains(FileKind.PDF);
        assertThat(FileKind.forExtension("png")).contains(FileKind.PNG);
        assertThat(FileKind.forExtension("jpg")).contains(FileKind.JPEG);
        assertThat(FileKind.forExtension("jpeg")).contains(FileKind.JPEG);
        assertThat(FileKind.forExtension("html")).isEmpty();
        assertThat(FileKind.forExtension("svg")).isEmpty();
        assertThat(FileKind.forExtension("")).isEmpty();

        byte[] pdf = "%PDF-1.7".getBytes();
        assertThat(FileKind.PDF.matches(pdf, pdf.length)).isTrue();
        assertThat(FileKind.PNG.matches(pdf, pdf.length)).isFalse();
        assertThat(FileKind.PDF.matches(pdf, 4)).as("too short to hold the signature").isFalse();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(FileKind.PNG.matches(png, 8)).isTrue();
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertThat(FileKind.JPEG.matches(jpg, 8)).isTrue();
        assertThat(FileKind.JPEG.matches(png, 8)).isFalse();
    }

    @Test
    void contentDispositionIsAnAttachmentWithAsciiFallbackAndEncodedName() {
        assertThat(ContentDispositions.attachment("coi.pdf"))
                .isEqualTo("attachment; filename=\"coi.pdf\"; filename*=UTF-8''coi.pdf");
        String h = ContentDispositions.attachment("Seguro d\u00EDa \"x\".pdf");
        assertThat(h).startsWith("attachment; filename=\"Seguro d_a _x_.pdf\"; filename*=UTF-8''");
        assertThat(h).endsWith("Seguro%20d%C3%ADa%20%22x%22.pdf");
        // Header injection attempts cannot break out of the quoted string.
        String evil = ContentDispositions.attachment("a\";x=\"b\r\nSet-Cookie: z.pdf");
        assertThat(evil).doesNotContain("\r").doesNotContain("\n");
        assertThat(evil.substring(0, evil.indexOf("; filename*="))).matches("attachment; filename=\"[^\"]*\"");
    }
}
