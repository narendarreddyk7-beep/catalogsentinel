package io.catalogsentinel.core.scenario;

import io.catalogsentinel.core.testsupport.TestJsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and instantiates {@code scripts/e2e/smoke-scenario.json}, the fixtures and expectations shared by
 * {@code scripts/smoke.sh} and {@link SmokeScenarioIT}. Placeholder rules and the GTIN derivation must stay identical
 * to {@code scripts/e2e/smoke.py} ({@code instantiate}, {@code gtin_for}); fixed vectors in both test suites pin them.
 */
final class SmokeScenario {

    static final String FILE = "scripts/e2e/smoke-scenario.json";
    private static final Pattern GTIN = Pattern.compile("\\{gtin:([a-z0-9-]+)}");
    private static final Pattern TOKEN = Pattern.compile("[a-z]{4,24}");

    private SmokeScenario() {
    }

    /** Finds the scenario by walking up from the working directory (the module under Maven, the repository root elsewhere). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> load() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(FILE);
            if (Files.isRegularFile(candidate)) {
                try {
                    return new TestJsonCodec().read(Files.readString(candidate), Map.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(FILE + " not found above " + Path.of("").toAbsolutePath());
    }

    static Map<String, Object> instantiate(Map<String, Object> scenario, String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("run token must be 4-24 lowercase letters: " + token);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) substitute(scenario, token);
        return result;
    }

    private static Object substitute(Object value, String token) {
        if (value instanceof String s) {
            return fill(s, token);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, token)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(substitute(v, token)));
            return out;
        }
        return value;
    }

    static String fill(String s, String token) {
        String brand = Character.toUpperCase(token.charAt(0)) + token.substring(1);
        String out = s.replace("{root}", "smoke " + token)
                .replace("{token}", token)
                .replace("{Brand}", brand)
                .replace("{BRAND}", token.toUpperCase(Locale.ROOT));
        Matcher m = GTIN.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, gtinFor(token, m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "2" + 11 digits from SHA-256(token:name), plus the GS1 check digit: a valid, run-specific GTIN-13. */
    static String gtinFor(String token, String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((token + ":" + name).getBytes(StandardCharsets.UTF_8));
            BigInteger value = new BigInteger(1, Arrays.copyOf(digest, 8)).mod(BigInteger.TEN.pow(11));
            String body = "2" + String.format(Locale.ROOT, "%011d", value);
            return body + checkDigit(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static int checkDigit(String body12) {
        int total = 0;
        for (int i = 0; i < body12.length(); i++) {
            total += (body12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - total % 10) % 10;
    }
}
