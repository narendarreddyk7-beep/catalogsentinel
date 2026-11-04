package io.catalogsentinel.domain.common;

import java.util.List;

/**
 * Raised when input violates a domain invariant. Carries every violation found, not just the first,
 * so API callers can fix a request in one round trip.
 */
public class DomainValidationException extends RuntimeException {

    private final List<Violation> violations;

    public DomainValidationException(List<Violation> violations) {
        super(summarize(violations));
        this.violations = List.copyOf(violations);
    }

    public DomainValidationException(String field, String message) {
        this(List.of(new Violation(field, message)));
    }

    public List<Violation> violations() {
        return violations;
    }

    private static String summarize(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "validation failed";
        }
        StringBuilder sb = new StringBuilder("validation failed: ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(violations.get(i).field()).append(' ').append(violations.get(i).message());
        }
        return sb.toString();
    }

    /** A single field-level violation. */
    public record Violation(String field, String message) {
    }
}
