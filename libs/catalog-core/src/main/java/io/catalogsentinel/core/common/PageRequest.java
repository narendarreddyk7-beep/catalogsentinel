package io.catalogsentinel.core.common;

/**
 * Keyset page request. {@code limit} is clamped to [1, {@value #MAX_LIMIT}].
 *
 * @param cursor opaque cursor from a previous page, or {@code null} for the first page
 */
public record PageRequest(String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (cursor != null && cursor.isBlank()) {
            cursor = null;
        }
    }

    public static PageRequest first(int limit) {
        return new PageRequest(null, limit);
    }

    /** Rows to fetch: one extra row reveals whether another page exists without a COUNT query. */
    public int fetchSize() {
        return limit + 1;
    }
}
