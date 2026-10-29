package org.example.sectoriadb.catalog.metastore;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON part of metastore values. A private mapper, so the stored format does not depend on how the application
 * configures its own mapper. Unknown properties are ignored to let a newer version add fields.
 */
final class JsonValues {

    /**
     * Jackson 3 writes java.time values as ISO-8601 strings by default, as the stored records always had them. The
     * reading side is pinned to the Jackson 2 behaviour the records were written under: unknown properties and a null
     * for a primitive are accepted (older and newer versions of a record stay readable).
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private JsonValues() {
    }

    static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot encode " + value.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    static <T> T read(byte[] json, int off, int len, Class<T> type) {
        try {
            return MAPPER.readValue(json, off, len, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("corrupt " + type.getSimpleName() + " record: " + e.getMessage(), e);
        }
    }
}
