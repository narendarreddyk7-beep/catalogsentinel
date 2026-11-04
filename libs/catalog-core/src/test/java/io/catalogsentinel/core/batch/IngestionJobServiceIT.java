package io.catalogsentinel.core.batch;

import io.catalogsentinel.core.batch.IngestionJob.IngestionJobStatus;
import io.catalogsentinel.core.batch.IngestionJobService.RegisterCommand;
import io.catalogsentinel.core.batch.IngestionJobService.UpdateCommand;
import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.core.ingestion.IngestionRequest;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.core.testsupport.Events;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionJobServiceIT {

    private final CoreTestContext ctx = new CoreTestContext();
    private final IngestionJobService service = ctx.ingestionJobService;

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register("northwind-supply", "Northwind Supply", SourceRole.REFERENCE, true);
        ctx.sourceService.register("acme-market", "Acme Market", SourceRole.MARKETPLACE, true);
    }

    private static Map<String, Object> manifest(String checksum) {
        return Map.of("manifestVersion", 1, "sourceCode", "northwind-supply",
                "files", List.of(Map.of("path", "part-0001.jsonl", "sha256", checksum, "records", 3)));
    }

    private RegisterCommand register(String key, String checksum) {
        return new RegisterCommand(key, "northwind-supply", manifest(checksum), "s3://bucket/in/", null, null, 3L);
    }

    @Test
    void registrationIsIdempotentForTheSameManifest() {
        var first = service.register(register("northwind-supply:abc12345", "aa"));
        var again = service.register(register("northwind-supply:abc12345", "aa"));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.job().ingestionJobId()).isEqualTo(first.job().ingestionJobId());
        assertThat(first.job().status()).isEqualTo(IngestionJobStatus.REGISTERED);
        assertThat(first.job().rowsTotal()).isEqualTo(3L);
        assertThat(ctx.count("ingestion_jobs")).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentManifestOrSourceIsAConflict() {
        service.register(register("northwind-supply:abc12345", "aa"));

        assertThatThrownBy(() -> service.register(register("northwind-supply:abc12345", "bb")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("different source or manifest");
        assertThatThrownBy(() -> service.register(new RegisterCommand("northwind-supply:abc12345", "acme-market",
                manifest("aa"), null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void registrationValidatesInput() {
        assertThatThrownBy(() -> service.register(new RegisterCommand("short", "northwind-supply", Map.of(), null, null,
                null, -1L)))
                .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactlyInAnyOrder("jobKey", "manifest", "rowsTotal"));
        assertThatThrownBy(() -> service.register(new RegisterCommand("unknown-src:12345678", "unknown-src",
                manifest("aa"), null, null, null, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("UNKNOWN_SOURCE"));
    }

    @Test
    void fullLifecycleWithRetriedCompletionReport() {
        long id = service.register(register("northwind-supply:life0001", "aa")).job().ingestionJobId();

        IngestionJob running = service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null, null,
                null, null, null));
        assertThat(running.status()).isEqualTo(IngestionJobStatus.RUNNING);
        assertThat(running.startedAt()).isNotNull();

        ctx.clock.advance(Duration.ofSeconds(30));
        service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, 3L, 2L, 1L, null, null,
                "s3://bucket/rejected/", null));
        UpdateCommand done = new UpdateCommand(IngestionJobStatus.PUBLISHED, 3L, 2L, 1L, 2L, "s3://bucket/curated/",
                null, null);
        IngestionJob published = service.update(id, done);

        assertThat(published.status()).isEqualTo(IngestionJobStatus.PUBLISHED);
        assertThat(published.finishedAt()).isNotNull();
        assertThat(published.rejectedUri()).isEqualTo("s3://bucket/rejected/");
        assertThat(published.outputUri()).isEqualTo("s3://bucket/curated/");
        assertThat(published.startedAt()).isEqualTo(running.startedAt());

        // The batch retries its final HTTP call: same counts are a no-op, different counts a conflict.
        assertThat(service.update(id, done)).isEqualTo(published);
        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, 3L, 3L, 0L, 3L,
                null, null, null)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("JOB_ALREADY_PUBLISHED"));
        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null, null,
                null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void publishingRequiresConsistentCounts() {
        long id = service.register(register("northwind-supply:count001", "aa")).job().ingestionJobId();
        service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, 3L, 2L, 0L, 2L,
                null, null, null)))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("rowsValid + rowsRejected");
        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, 3L, 3L, 0L, 4L,
                null, null, null)))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("cannot exceed rowsValid");
        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, null, null, null,
                null, null, null, null)))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("required to publish");
        // In-file duplicates collapse, so fewer events than valid rows is fine.
        assertThat(service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, 3L, 3L, 0L, 2L, null, null,
                null)).status()).isEqualTo(IngestionJobStatus.PUBLISHED);
        assertThatThrownBy(() -> service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, -1L, null, null,
                null, null, null, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void failedJobRestartsWithNextAttemptAndKeepsBoundedErrorHistory() {
        long id = service.register(register("northwind-supply:fail0001", "aa")).job().ingestionJobId();
        service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null, null, null, null, null));
        List<String> errors = IntStream.range(0, 15).mapToObj(i -> "executor lost #" + i).toList();
        IngestionJob failed = service.update(id, new UpdateCommand(IngestionJobStatus.FAILED, null, null, null, null,
                null, null, errors));
        assertThat(failed.status()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(failed.finishedAt()).isNotNull();

        IngestionJob restarted = service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null,
                null, null, null, null));
        assertThat(restarted.attempt()).isEqualTo(2);
        assertThat(restarted.finishedAt()).isNull();

        service.update(id, new UpdateCommand(IngestionJobStatus.FAILED, null, null, null, null, null, null, errors));
        IngestionJobService.JobView view = service.get(id);
        assertThat((List<?>) view.errorSummary()).hasSize(20);
        assertThat(view.errorSummary().toString()).contains("executor lost #14").doesNotContain("executor lost #9,");
    }

    @Test
    void progressIsDerivedFromTheIdempotencyLedger() {
        long id = service.register(register("northwind-supply:prog0001", "aa")).job().ingestionJobId();
        service.update(id, new UpdateCommand(IngestionJobStatus.RUNNING, null, null, null, null, null, null, null));
        for (int i = 1; i <= 3; i++) {
            ctx.ingestionService.ingest(new IngestionRequest(
                    Events.upsert("northwind-supply", "NW-" + i, 1, "Northwind Item " + i),
                    IngestionPath.BATCH, "corr", id, ctx.clock.instant()));
        }
        service.update(id, new UpdateCommand(IngestionJobStatus.PUBLISHED, 4L, 4L, 0L, 4L, null, null, null));

        IngestionJobService.JobView partial = service.get(id);
        assertThat(partial.progress().processedEvents()).isEqualTo(3);
        assertThat(partial.progress().byOutcome()).containsOnlyKeys("CREATED");
        assertThat(partial.remainingEvents()).isEqualTo(1);
        assertThat(partial.fullyProcessed()).isFalse();

        ctx.ingestionService.ingest(new IngestionRequest(Events.upsert("northwind-supply", "NW-4", 1, "Northwind Item 4"),
                IngestionPath.BATCH, "corr", id, ctx.clock.instant()));
        IngestionJobService.JobView complete = service.get(id);
        assertThat(complete.remainingEvents()).isZero();
        assertThat(complete.fullyProcessed()).isTrue();
        assertThat(ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE ingestion_path = 'BATCH' AND ingestion_job_id = ?",
                Long.class, id)).isEqualTo(4);
    }

    @Test
    void listIsNewestFirstWithKeysetPaginationAndFilters() {
        for (int i = 0; i < 5; i++) {
            service.register(register("northwind-supply:list000" + i, "c" + i));
        }
        long failedId = service.register(register("northwind-supply:list0009", "c9")).job().ingestionJobId();
        service.update(failedId, new UpdateCommand(IngestionJobStatus.FAILED, null, null, null, null, null, null,
                List.of("bad manifest")));

        Page<IngestionJob> first = service.list(null, null, PageRequest.first(4));
        assertThat(first.items()).hasSize(4);
        assertThat(first.items().getFirst().jobKey()).isEqualTo("northwind-supply:list0009");
        Page<IngestionJob> second = service.list(null, null, new PageRequest(first.nextCursor(), 4));
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextCursor()).isNull();

        assertThat(service.list(IngestionJobStatus.FAILED, null, PageRequest.first(10)).items())
                .extracting(IngestionJob::ingestionJobId).containsExactly(failedId);
        assertThat(service.list(null, "acme-market", PageRequest.first(10)).items()).isEmpty();
        assertThat(service.list(null, "no-such-source", PageRequest.first(10)).items()).isEmpty();
    }
}
