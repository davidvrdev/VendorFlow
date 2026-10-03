package com.vendorflow.document.application;

import java.util.Locale;

/**
 * Turns the client-supplied file name into a safe DISPLAY name. The result is only ever shown/sent in a header; it is
 * never used to build a storage path (keys are server-generated UUIDs).
 *
 * <p>Rules (docs/API.md Phase 3): last path segment (both "/" and "\"), control and format (bidi, zero-width)
 * characters removed, whitespace collapsed, at most 255 characters keeping the extension, fallback "document" when
 * nothing is left before the extension.
 */
public final class FilenameSanitizer {

    public static final int MAX_LENGTH = 255;
    private static final String FALLBACK_BASE = "document";

    /** {@code extension} is lower-case without the dot, or "" when the name has none. */
    public record Result(String filename, String extension) {
    }

    private FilenameSanitizer() {
    }

    public static Result sanitize(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = collapse(name);

        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot + 1).strip();
        if (name.isEmpty()) {
            return new Result("", "");
        }
        if (base.isBlank()) {
            base = FALLBACK_BASE;
        }
        String suffix = extension.isEmpty() && dot < 0 ? "" : "." + extension;
        // Keep the extension when shortening: truncate the base, not the suffix (unless the suffix itself is absurd,
        // in which case the extension check rejects the upload anyway).
        int maxBase = MAX_LENGTH - suffix.codePointCount(0, suffix.length());
        if (maxBase < 1) {
            suffix = truncate(suffix, MAX_LENGTH - 1);
            maxBase = MAX_LENGTH - suffix.codePointCount(0, suffix.length());
        }
        base = truncate(base, maxBase).stripTrailing();
        if (base.isEmpty()) {
            base = FALLBACK_BASE;
        }
        return new Result(base + suffix, extension.toLowerCase(Locale.ROOT));
    }

    private static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String truncate(String s, int maxCodePoints) {
        if (s.codePointCount(0, s.length()) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
    }
}
