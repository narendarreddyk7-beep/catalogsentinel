package io.catalogsentinel.core.source;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class SourceServiceIT {

    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
    }

    @Test
    void registersAndFetchesSources() {
        CatalogSource s = ctx.sourceService.register("acme-market", "  Acme Marketplace ", SourceRole.MARKETPLACE, null);
        assertThat(s.sourceId()).isPositive();
        assertThat(s.name()).isEqualTo("Acme Marketplace");
        assertThat(s.enabled()).isTrue();
        assertThat(ctx.sourceService.get(s.sourceId())).isEqualTo(s);
        assertThat(ctx.sourceService.getByCode("acme-market")).isEqualTo(s);
    }

    @Test
    void duplicateCodeIsAConflict() {
        ctx.sourceService.register("acme-market", "Acme", SourceRole.MARKETPLACE, true);
        assertThatThrownBy(() -> ctx.sourceService.register("acme-market", "Other", SourceRole.REFERENCE, true))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo("SOURCE_CODE_TAKEN"));
    }

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> ctx.sourceService.register("Bad Code", " ", null, true))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e -> assertThat(((DomainValidationException) e).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactlyInAnyOrder("code", "name", "role"));
    }

    @Test
    void anUnknownRoleFromARequestIsReportedTogetherWithTheOtherInvalidFields() {
        assertThatThrownBy(() -> ctx.sourceService.registerFromRequest("Bad Code", "", "SELLER", true))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e -> assertThat(((DomainValidationException) e).violations())
                        .extracting(DomainValidationException.Violation::field, DomainValidationException.Violation::message)
                        .containsExactlyInAnyOrder(
                                tuple("code", "must match ^[a-z0-9][a-z0-9_-]{1,63}$"),
                                tuple("name", "must not be blank"),
                                tuple("role", "must be MARKETPLACE or REFERENCE")));
        assertThatThrownBy(() -> ctx.sourceService.registerFromRequest("acme-market", "Acme", "seller", true))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e -> assertThat(((DomainValidationException) e).violations())
                        .extracting(DomainValidationException.Violation::field).containsExactly("role"));
        CatalogSource s = ctx.sourceService.registerFromRequest("acme-market", "Acme", " marketplace ", null);
        assertThat(s.role()).isEqualTo(SourceRole.MARKETPLACE);
    }

    @Test
    void listsWithFiltersAndKeysetPagination() {
        for (int i = 0; i < 5; i++) {
            ctx.sourceService.register("market-" + i, "Market " + i, SourceRole.MARKETPLACE, true);
            ctx.sourceService.register("ref-" + i, "Ref " + i, SourceRole.REFERENCE, i % 2 == 0);
        }
        Page<CatalogSource> first = ctx.sourceService.list(SourceRole.REFERENCE, null, new PageRequest(null, 2));
        assertThat(first.items()).extracting(CatalogSource::code).containsExactly("ref-0", "ref-1");
        Page<CatalogSource> second = ctx.sourceService.list(SourceRole.REFERENCE, null, new PageRequest(first.nextCursor(), 2));
        assertThat(second.items()).extracting(CatalogSource::code).containsExactly("ref-2", "ref-3");
        Page<CatalogSource> last = ctx.sourceService.list(SourceRole.REFERENCE, null, new PageRequest(second.nextCursor(), 2));
        assertThat(last.items()).extracting(CatalogSource::code).containsExactly("ref-4");
        assertThat(last.nextCursor()).isNull();

        assertThat(ctx.sourceService.list(SourceRole.REFERENCE, true, PageRequest.first(50)).items()).hasSize(3);
        assertThat(ctx.sourceService.list(null, null, PageRequest.first(50)).items()).hasSize(10);
    }

    @Test
    void rejectsForeignCursorsAndMissingSources() {
        assertThatThrownBy(() -> ctx.sourceService.list(null, null, new PageRequest("not-a-cursor!", 10)))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
        assertThatThrownBy(() -> ctx.sourceService.get(424242)).isInstanceOf(NotFoundException.class);
    }
}
