package io.catalogsentinel.core.common;

/**
 * A request conflicts with current state (HTTP 409). {@code code} is a stable machine-readable identifier
 * such as {@code EVENT_ID_REUSED} or {@code SOURCE_CODE_TAKEN}.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
