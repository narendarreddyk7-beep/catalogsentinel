package io.catalogsentinel.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset-pagination cursors. A cursor encodes the sort key of the last row returned; the next page
 * continues strictly after it. Unlike OFFSET pagination this is O(page size) per request and stable under
 * concurrent inserts. Cursors are versioned ({@code v1}) so the format can evolve.
 */
public final class CursorCodec {

    private static final String VERSION = "v1";
    private static final char SEP = '|';

    private CursorCodec() {
    }

    public static String encode(Object... keyParts) {
        StringBuilder sb = new StringBuilder(VERSION);
        for (Object part : keyParts) {
            String s = String.valueOf(part);
            if (s.indexOf(SEP) >= 0) {
                throw new IllegalArgumentException("cursor key part must not contain '|': " + s);
            }
            sb.append(SEP).append(s);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a cursor into exactly {@code expectedParts} key parts. */
    public static String[] decode(String cursor, int expectedParts) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != expectedParts + 1 || !parts[0].equals(VERSION)) {
                throw new InvalidCursorException("malformed cursor");
            }
            String[] key = new String[expectedParts];
            System.arraycopy(parts, 1, key, 0, expectedParts);
            return key;
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("malformed cursor");
        }
    }

    public static long decodeLong(String cursor) {
        try {
            return Long.parseLong(decode(cursor, 1)[0]);
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("malformed cursor");
        }
    }

    /** Raised for cursors that were not produced by this codec; maps to HTTP 400. */
    public static class InvalidCursorException extends RuntimeException {
        public InvalidCursorException(String message) {
            super(message);
        }
    }
}
