package org.example.sectoriadb.repository.metastore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The JSON part of metastore values. A private mapper, so the stored format does not depend on how the application
 * configures its own mapper. Unknown properties are ignored to let a newer version add fields.
 */
final class JsonValues {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonValues() {
    }

    static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T read(byte[] json, int off, int len, Class<T> type) {
        try {
            return MAPPER.readValue(json, off, len, type);
        } catch (IOException e) {
            throw new IllegalStateException("corrupt " + type.getSimpleName() + " record: " + e.getMessage(), e);
        }
    }
}
