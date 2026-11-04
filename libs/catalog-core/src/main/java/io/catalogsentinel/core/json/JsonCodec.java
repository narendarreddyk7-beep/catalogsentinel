package io.catalogsentinel.core.json;

import java.util.Map;

/**
 * JSON port. Applications implement it with their Jackson 3 {@code JsonMapper}; the core never binds to a
 * Jackson major version, which keeps this shared library independent of the Jackson 2 to 3 migration.
 */
public interface JsonCodec {

    String write(Object value);

    <T> T read(String json, Class<T> type);

    /** Reads a flat JSON object of string values (listing attributes). */
    Map<String, String> readStringMap(String json);

    /** Thrown by implementations for malformed JSON or type mismatches. */
    class JsonCodecException extends RuntimeException {
        public JsonCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
