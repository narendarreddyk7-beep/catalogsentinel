package io.catalogsentinel.domain.common;

import java.util.ArrayList;
import java.util.List;

/** Small accumulator for field violations; throws once with all of them. */
public final class Violations {

    private final List<DomainValidationException.Violation> items = new ArrayList<>();

    public Violations add(String field, String message) {
        items.add(new DomainValidationException.Violation(field, message));
        return this;
    }

    public Violations requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            add(field, "must not be blank");
        } else if (value.length() > maxLength) {
            add(field, "must be at most " + maxLength + " characters");
        }
        return this;
    }

    public Violations optionalText(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            add(field, "must be at most " + maxLength + " characters");
        }
        return this;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<DomainValidationException.Violation> list() {
        return List.copyOf(items);
    }

    public void throwIfAny() {
        if (!items.isEmpty()) {
            throw new DomainValidationException(items);
        }
    }
}
