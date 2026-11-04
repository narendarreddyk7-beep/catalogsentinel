package io.catalogsentinel.core.common;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results plus the cursor for the next page ({@code null} when this is the last page).
 */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = List.copyOf(items);
    }

    /**
     * Builds a page from rows fetched with {@link PageRequest#fetchSize()}: if the extra row is present it is
     * dropped and the cursor is built from the last row that is kept.
     */
    public static <T> Page<T> fromOverfetch(List<T> rows, PageRequest request, Function<T, String> cursorOf) {
        if (rows.size() <= request.limit()) {
            return new Page<>(rows, null);
        }
        List<T> kept = rows.subList(0, request.limit());
        return new Page<>(kept, cursorOf.apply(kept.get(kept.size() - 1)));
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
