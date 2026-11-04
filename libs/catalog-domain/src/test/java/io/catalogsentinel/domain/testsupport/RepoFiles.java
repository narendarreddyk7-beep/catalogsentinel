package io.catalogsentinel.domain.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates shared test fixtures in the repository's top-level {@code tests/} directory regardless of whether
 * tests run from the module directory (Maven) or the repository root (IDE).
 */
public final class RepoFiles {

    private RepoFiles() {
    }

    public static Path locate(String relative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixture not found walking up from user.dir: " + relative);
    }

    /** Reads a tab-separated fixture, skipping blank lines and lines starting with '#'. */
    public static List<String[]> readTsv(String relative) {
        try {
            List<String[]> rows = new ArrayList<>();
            for (String line : Files.readAllLines(locate(relative), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                rows.add(line.split("\t", -1));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
