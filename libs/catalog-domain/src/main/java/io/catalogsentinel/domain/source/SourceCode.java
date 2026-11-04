package io.catalogsentinel.domain.source;

import io.catalogsentinel.domain.common.DomainValidationException;

import java.util.regex.Pattern;

/**
 * Stable, human-readable identifier that producers use to address a catalog source
 * (for example {@code acme-marketplace}). Internal numeric IDs never leave the platform in events.
 */
public record SourceCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");

    public SourceCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("sourceCode",
                    "must match " + FORMAT.pattern() + " (lowercase letters, digits, '-' or '_', 2-64 chars)");
        }
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
