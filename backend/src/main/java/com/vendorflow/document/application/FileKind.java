package com.vendorflow.document.application;

import java.util.Optional;

/**
 * The only file types accepted, with their magic bytes. The detected kind (not the client Content-Type) decides the
 * stored mime type. Anything else (HTML, SVG, scripts, executables) is never accepted, whatever its name says.
 */
public enum FileKind {
    PDF("application/pdf", new int[] {0x25, 0x50, 0x44, 0x46, 0x2D}, "pdf"),
    PNG("image/png", new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, "png"),
    JPEG("image/jpeg", new int[] {0xFF, 0xD8, 0xFF}, "jpg", "jpeg");

    /** Enough bytes to check every signature. */
    public static final int HEADER_BYTES = 8;

    private final String mimeType;
    private final int[] magic;
    private final String[] extensions;

    FileKind(String mimeType, int[] magic, String... extensions) {
        this.mimeType = mimeType;
        this.magic = magic;
        this.extensions = extensions;
    }

    public String mimeType() {
        return mimeType;
    }

    /** @param extension lower-case, without the dot */
    public static Optional<FileKind> forExtension(String extension) {
        for (FileKind kind : values()) {
            for (String ext : kind.extensions) {
                if (ext.equals(extension)) {
                    return Optional.of(kind);
                }
            }
        }
        return Optional.empty();
    }

    /** True if the first {@code length} bytes of the file start with this kind's signature. */
    public boolean matches(byte[] header, int length) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((header[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
