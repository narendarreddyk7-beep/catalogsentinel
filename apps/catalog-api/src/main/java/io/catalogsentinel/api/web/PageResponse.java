package io.catalogsentinel.api.web;

import io.catalogsentinel.core.common.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Collection response envelope: {@code {"items": [...], "page": {"limit": 50, "nextCursor": "..."}}}.
 * Pass {@code nextCursor} back as {@code ?cursor=} to get the next page; it is {@code null} on the last page.
 */
public record PageResponse<T>(List<T> items, PageInfo page) {

    public record PageInfo(int limit, String nextCursor, boolean hasMore) {
    }

    public static <T> PageResponse<T> of(Page<T> page, int limit) {
        return new PageResponse<>(page.items(), new PageInfo(limit, page.nextCursor(), page.nextCursor() != null));
    }

    public static <S, T> PageResponse<T> of(Page<S> page, int limit, Function<S, T> mapper) {
        return of(page.map(mapper), limit);
    }
}
