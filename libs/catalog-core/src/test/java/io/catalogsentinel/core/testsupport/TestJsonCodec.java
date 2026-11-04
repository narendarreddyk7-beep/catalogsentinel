package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.core.json.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Jackson 3 {@link JsonCodec} for tests, equivalent to the applications' production codec. */
public final class TestJsonCodec implements JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new JsonCodecException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    @Override
    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new JsonCodecException("cannot parse " + type.getSimpleName(), e);
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
            throw new JsonCodecException("cannot parse string map", e);
        }
    }
}
