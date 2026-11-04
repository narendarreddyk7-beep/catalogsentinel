package io.catalogsentinel.domain.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;

/**
 * Builds an unambiguous, order-stable textual form of a set of named fields and hashes it.
 *
 * <p>Each field is written as {@code name=<length>:<value>\n}; a {@code null} value is written as
 * {@code name=~\n}. Length-prefixing makes the encoding injective: no two different field sets can
 * serialize to the same string (unlike naive concatenation, where {@code ("ab","c")} and
 * {@code ("a","bc")} collide). Maps must be sorted so the output is independent of insertion order.
 *
 * <p>Used for content hashes (change detection, same-version conflict detection) and request
 * fingerprints (event-id reuse detection).
 */
public final class CanonicalForm {

    private final StringBuilder buffer = new StringBuilder(256);

    private CanonicalForm() {
    }

    public static CanonicalForm create() {
        return new CanonicalForm();
    }

    public CanonicalForm field(String name, String value) {
        buffer.append(name).append('=');
        if (value == null) {
            buffer.append('~');
        } else {
            buffer.append(value.length()).append(':').append(value);
        }
        buffer.append('\n');
        return this;
    }

    public CanonicalForm field(String name, long value) {
        return field(name, Long.toString(value));
    }

    public CanonicalForm field(String name, SortedMap<String, String> values) {
        if (values == null) {
            return field(name, (String) null);
        }
        buffer.append(name).append("={").append(values.size()).append('\n');
        for (Map.Entry<String, String> entry : values.entrySet()) {
            field("  " + entry.getKey(), entry.getValue());
        }
        buffer.append("}\n");
        return this;
    }

    public String text() {
        return buffer.toString();
    }

    public String sha256Hex() {
        return sha256Hex(buffer.toString());
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
