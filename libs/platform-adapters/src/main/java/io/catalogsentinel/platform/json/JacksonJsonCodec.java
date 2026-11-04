package io.catalogsentinel.platform.json;

import io.catalogsentinel.core.json.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** {@link JsonCodec} backed by the application's Jackson 3 {@link JsonMapper}. */
public final class JacksonJsonCodec implements JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    public JacksonJsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new JsonCodecException("cannot serialize " + (value == null ? "null" : value.getClass().getSimpleName()), e);
        }
    }

    @Override
    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new JsonCodecException("cannot parse " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (JacksonException e) {
            throw new JsonCodecException("cannot parse string map: " + e.getMessage(), e);
        }
    }
}
