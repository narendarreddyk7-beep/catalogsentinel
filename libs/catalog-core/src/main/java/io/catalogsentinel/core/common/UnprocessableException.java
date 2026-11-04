package io.catalogsentinel.core.common;

/**
 * A syntactically valid request that cannot be processed in the current state (HTTP 422), for example an
 * event for an unknown or disabled source. Distinct from validation errors (400) so producers can tell
 * "fix your payload" from "fix your configuration".
 */
public class UnprocessableException extends RuntimeException {

    private final String code;

    public UnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
