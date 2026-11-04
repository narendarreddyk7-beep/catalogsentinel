package io.catalogsentinel.api.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.catalogsentinel.api.config.ReconciliationRunner;
import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.gaps.ReconciliationService;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@Tag(name = "Reconciliation", description = "Gap detection runs per target marketplace")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReconciliationJobRepository jobs;
    private final ReconciliationRunner runner;

    public ReconciliationController(ReconciliationService reconciliation, ReconciliationJobRepository jobs,
                                    ReconciliationRunner runner) {
        this.reconciliation = reconciliation;
        this.jobs = jobs;
        this.runner = runner;
    }

    @PostMapping("/api/v1/reconciliation/jobs")
    @Operation(summary = "Start a reconciliation (gap detection) for a target marketplace",
            description = "202 with the job; poll GET /api/v1/reconciliation/jobs/{id}. One active job per target "
                    + "(409 RECONCILIATION_IN_PROGRESS). The same Idempotency-Key returns the same job.")
    public ResponseEntity<JobResponse> start(@RequestBody StartRequest body,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (body == null || body.targetSourceCode() == null || body.targetSourceCode().isBlank()) {
            throw new DomainValidationException("targetSourceCode", "is required");
        }
        ReconciliationJob job = reconciliation.request(body.targetSourceCode().strip(), body.referenceSourceCode(),
                body.requestedBy() == null ? "api" : body.requestedBy(), key);
        if ("PENDING".equals(job.status())) {
            runner.submit(job.jobId()); // only a nudge: the job is durable in the database either way
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/reconciliation/jobs/" + job.jobId()))
                .body(JobResponse.from(job));
    }

    @GetMapping("/api/v1/reconciliation/jobs/{jobId}")
    @Operation(summary = "Get a reconciliation job with its statistics")
    public JobResponse get(@PathVariable long jobId) {
        return JobResponse.from(reconciliation.get(jobId));
    }

    @GetMapping("/api/v1/reconciliation/jobs")
    @Operation(summary = "List reconciliation jobs, newest first")
    public PageResponse<JobResponse> list(@RequestParam(required = false) Long targetSourceId,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        Long before = page.cursor() == null ? null : CursorCodec.decodeLong(page.cursor());
        Page<ReconciliationJob> result = Page.fromOverfetch(jobs.recent(targetSourceId, before, page.fetchSize()), page,
                j -> CursorCodec.encode(j.jobId()));
        return PageResponse.of(result, page.limit(), JobResponse::from);
    }

    public record StartRequest(String targetSourceCode, String referenceSourceCode, String requestedBy) {
    }

    /**
     * @param attempt       attempts started so far; {@code maxAttempts} bounds retries
     * @param nextAttemptAt when a PENDING job may next be claimed (in the future after a failed attempt)
     * @param errorMessage  last error, kept while a failed attempt waits for its retry and on final failure
     */
    public record JobResponse(long jobId, String targetSourceCode, String referenceSourceCode, String status,
                              String requestedBy, @JsonRawValue String stats, String errorMessage, Instant createdAt,
                              Instant startedAt, Instant finishedAt, int attempt, int maxAttempts,
                              Instant nextAttemptAt, Instant leaseExpiresAt) {
        static JobResponse from(ReconciliationJob j) {
            return new JobResponse(j.jobId(), j.targetSourceCode(), j.referenceSourceCode(), j.status(),
                    j.requestedBy(), j.statsJson(), j.errorMessage(), j.createdAt(), j.startedAt(), j.finishedAt(),
                    j.attempt(), j.maxAttempts(), j.nextAttemptAt(), j.leaseExpiresAt());
        }
    }
}
