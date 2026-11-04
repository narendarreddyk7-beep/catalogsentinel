package io.catalogsentinel.api.web;

import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.source.CatalogSource;
import io.catalogsentinel.core.source.SourceService;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.source.SourceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/sources")
@Tag(name = "Sources", description = "Marketplaces (monitored) and reference catalogs (benchmarks)")
public class SourceController {

    private final SourceService sources;

    public SourceController(SourceService sources) {
        this.sources = sources;
    }

    @PostMapping
    @Operation(summary = "Register a catalog source", description = "201 with Location; 409 SOURCE_CODE_TAKEN if the code exists")
    public ResponseEntity<SourceResponse> register(@RequestBody RegisterSourceRequest body) {
        CatalogSource s = sources.registerFromRequest(body.code(), body.name(), body.role(), body.enabled());
        return ResponseEntity.created(URI.create("/api/v1/sources/" + s.sourceId())).body(SourceResponse.from(s));
    }

    @GetMapping
    @Operation(summary = "List sources", description = "Filter by role and enabled flag; keyset pagination")
    public PageResponse<SourceResponse> list(@RequestParam(required = false) SourceRole role,
                                             @RequestParam(required = false) Boolean enabled,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        return PageResponse.of(sources.list(role, enabled, page), page.limit(), SourceResponse::from);
    }

    @GetMapping("/{sourceId}")
    @Operation(summary = "Get a source by id")
    public SourceResponse get(@PathVariable long sourceId) {
        return SourceResponse.from(sources.get(sourceId));
    }

    @PatchMapping("/{sourceId}")
    @Operation(summary = "Enable or disable a source", description = "Events for disabled sources are rejected with 422")
    public SourceResponse update(@PathVariable long sourceId, @RequestBody UpdateSourceRequest body) {
        if (body.enabled() == null) {
            throw new DomainValidationException("enabled", "is required");
        }
        return SourceResponse.from(sources.setEnabled(sourceId, body.enabled()));
    }

    public record RegisterSourceRequest(String code, String name, String role, Boolean enabled) {
    }

    public record UpdateSourceRequest(Boolean enabled) {
    }

    public record SourceResponse(long sourceId, String code, String name, SourceRole role, boolean enabled,
                                 Instant createdAt, Instant updatedAt) {
        static SourceResponse from(CatalogSource s) {
            return new SourceResponse(s.sourceId(), s.code(), s.name(), s.role(), s.enabled(), s.createdAt(), s.updatedAt());
        }
    }
}
