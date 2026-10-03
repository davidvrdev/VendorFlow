package com.vendorflow.document.application;

import java.nio.charset.StandardCharsets;

/** Builds {@code Content-Disposition: attachment} with an ASCII fallback and an RFC 5987/8187 {@code filename*}. */
public final class ContentDispositions {

    private ContentDispositions() {
    }

    public static String attachment(String filename) {
        return "attachment; filename=\"" + asciiFallback(filename) + "\"; filename*=UTF-8''" + encode(filename);
    }

    /** Printable ASCII only; quotes, backslashes, '%' and ';' become '_' so the quoted-string can never be broken. */
    static String asciiFallback(String filename) {
        StringBuilder sb = new StringBuilder();
        filename.codePoints().forEach(cp -> {
            boolean safe = cp >= 0x20 && cp < 0x7F && cp != '"' && cp != '\\' && cp != '%' && cp != ';';
            sb.append(safe ? (char) cp : '_');
        });
        String result = sb.toString().strip();
        return result.isEmpty() ? "document" : result;
    }

    /** Percent-encodes every UTF-8 byte except RFC 5987 attr-char. */
    static String encode(String filename) {
        StringBuilder sb = new StringBuilder();
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean attrChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (attrChar) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
