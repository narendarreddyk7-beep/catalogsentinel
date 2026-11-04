package io.catalogsentinel.domain.listing;

import java.util.Optional;

/**
 * Global Trade Item Number (GTIN-8/12/13/14, i.e. EAN-8, UPC-A, EAN-13, ITF-14).
 *
 * <p>Input is accepted with spaces or hyphens, validated with the GS1 mod-10 check digit, and stored
 * in canonical GTIN-14 form (left-padded with zeros) so that a UPC-A and the equivalent EAN-13
 * compare equal: {@code 036000291452} and {@code 0036000291452} are the same trade item.
 */
public record Gtin(String value) {

    public Gtin {
        if (value == null || value.length() != 14 || !isAllDigits(value) || !hasValidCheckDigit(value)) {
            throw new IllegalArgumentException("not a canonical GTIN-14: " + value);
        }
    }

    /** Parses a raw GTIN; returns empty when the input is not a structurally valid GTIN. */
    public static Optional<Gtin> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replace(" ", "").replace("-", "");
        if (!isAllDigits(digits)) {
            return Optional.empty();
        }
        int length = digits.length();
        if (length != 8 && length != 12 && length != 13 && length != 14) {
            return Optional.empty();
        }
        if (!hasValidCheckDigit(digits)) {
            return Optional.empty();
        }
        String padded = "0".repeat(14 - length) + digits;
        if (padded.chars().allMatch(c -> c == '0')) {
            return Optional.empty();
        }
        return Optional.of(new Gtin(padded));
    }

    public static boolean isValid(String raw) {
        return parse(raw).isPresent();
    }

    /** GS1 mod-10: weights 3,1,3,1... applied from the digit left of the check digit, moving left. */
    static boolean hasValidCheckDigit(String digits) {
        int sum = 0;
        int weight = 3;
        for (int i = digits.length() - 2; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = (weight == 3) ? 1 : 3;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == digits.charAt(digits.length() - 1) - '0';
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return value;
    }
}
