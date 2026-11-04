package io.catalogsentinel.core.source;

import io.catalogsentinel.domain.source.SourceRole;

import java.time.Instant;

/** A registered catalog source. */
public record CatalogSource(
        long sourceId,
        String code,
        String name,
        SourceRole role,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
