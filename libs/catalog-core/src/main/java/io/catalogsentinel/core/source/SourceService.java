package io.catalogsentinel.core.source;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.domain.common.Violations;
import io.catalogsentinel.domain.source.SourceCode;
import io.catalogsentinel.domain.source.SourceRole;
import org.springframework.stereotype.Service;

/** Registration and lookup of catalog sources. */
@Service
public class SourceService {

    private final SourceRepository repository;

    public SourceService(SourceRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a new source.
     *
     * @throws io.catalogsentinel.domain.common.DomainValidationException for invalid input
     * @throws ConflictException                                          when the code is already registered
     */
    public CatalogSource register(String code, String name, SourceRole role, Boolean enabled) {
        Violations v = new Violations();
        if (!SourceCode.isValid(code)) {
            v.add("code", "must match ^[a-z0-9][a-z0-9_-]{1,63}$");
        }
        v.requireText("name", name, 200);
        if (role == null) {
            v.add("role", "is required (MARKETPLACE or REFERENCE)");
        }
        v.throwIfAny();
        return repository.insertIfCodeAvailable(code, name.strip(), role, enabled == null || enabled)
                .orElseThrow(() -> new ConflictException("SOURCE_CODE_TAKEN", "source code '" + code + "' is already registered"));
    }

    public CatalogSource get(long sourceId) {
        return repository.findById(sourceId).orElseThrow(() -> new NotFoundException("source", sourceId));
    }

    public CatalogSource getByCode(String code) {
        return repository.findByCode(code).orElseThrow(() -> new NotFoundException("source", code));
    }

    public Page<CatalogSource> list(SourceRole role, Boolean enabled, PageRequest page) {
        Long afterId = page.cursor() == null ? null : CursorCodec.decodeLong(page.cursor());
        return Page.fromOverfetch(repository.list(role, enabled, afterId, page.fetchSize()), page,
                s -> CursorCodec.encode(s.sourceId()));
    }

    public CatalogSource setEnabled(long sourceId, boolean enabled) {
        return repository.updateEnabled(sourceId, enabled).orElseThrow(() -> new NotFoundException("source", sourceId));
    }
}
