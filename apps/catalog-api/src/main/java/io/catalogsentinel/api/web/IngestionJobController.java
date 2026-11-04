package io.catalogsentinel.api.web;

import io.catalogsentinel.core.batch.IngestionJob;
import io.catalogsentinel.core.batch.IngestionJob.IngestionJobStatus;
import io.catalogsentinel.core.batch.IngestionJobRepository;
import io.catalogsentinel.core.batch.IngestionJobService;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Batch ingestion jobs (Phase 5). The PySpark batch registers a job (idempotent per manifest-derived key),
 * reports RUNNING / PUBLISHED / FAILED, and publishes listing events that carry the job id; downstream progress
 * is derived from the idempotency ledger.
 */
@RestController
@Tag(name = "Batch ingestion", description = "Batch job registration, status reports and processing progress")
public class IngestionJobController {

    private final IngestionJobService jobs;

    public IngestionJobController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    public record RegisterRequest(String jobKey, String sourceCode, Map<String, Object> manifest, String inputUri,
                                  String outputUri, String rejectedUri, Long rowsTotal) {
    }

    public record UpdateRequest(String status, Long rowsTotal, Long rowsValid, Long rowsRejected, Long eventsPublished,
                                String outputUri, String rejectedUri, List<String> errors) {
    }

    public record JobResponse(long jobId, String jobKey, String sourceCode, String status, int attempt, String inputUri,
                              String outputUri, String rejectedUri, Long rowsTotal, Long rowsValid, Long rowsRejected,
                              Long eventsPublished, Instant createdAt, Instant startedAt, Instant finishedAt,
                              Instant updatedAt) {
        static JobResponse from(IngestionJob j) {
            return new JobResponse(j.ingestionJobId(), j.jobKey(), j.sourceCode(), j.status().name(), j.attempt(),
                    j.inputUri(), j.outputUri(), j.rejectedUri(), j.rowsTotal(), j.rowsValid(), j.rowsRejected(),
                    j.eventsPublished(), j.createdAt(), j.startedAt(), j.finishedAt(), j.updatedAt());
        }
    }

    public record ProgressResponse(long processedEvents, Map<String, Long> byOutcome, Long remainingEvents,
                                   boolean fullyProcessed, Instant firstProcessedAt, Instant lastProcessedAt) {
    }

    public record JobDetailResponse(JobResponse job, Object manifest, Object errors, ProgressResponse progress) {
        static JobDetailResponse from(IngestionJobService.JobView v) {
            IngestionJobRepository.Progress p = v.progress();
            return new JobDetailResponse(JobResponse.from(v.job()), v.manifest(), v.errorSummary(),
                    new ProgressResponse(p.processedEvents(), p.byOutcome(), v.remainingEvents(), v.fullyProcessed(),
                            p.firstProcessedAt(), p.lastProcessedAt()));
        }
    }

    @PostMapping("/api/v1/ingestion/jobs")
    @Operation(summary = "Register a batch ingestion job (idempotent per jobKey)",
            description = "201 for a new job, 200 when the same jobKey and manifest are registered again (a restart). "
                    + "409 JOB_KEY_REUSED if the key identifies a different manifest or source.")
    public ResponseEntity<JobDetailResponse> register(@RequestBody RegisterRequest body) {
        if (body == null) {
            throw new DomainValidationException("body", "is required");
        }
        IngestionJobService.Registration r = jobs.register(new IngestionJobService.RegisterCommand(body.jobKey(),
                body.sourceCode(), body.manifest(), body.inputUri(), body.outputUri(), body.rejectedUri(), body.rowsTotal()));
        JobDetailResponse response = JobDetailResponse.from(jobs.get(r.job().ingestionJobId()));
        return r.created()
                ? ResponseEntity.created(URI.create("/api/v1/ingestion/jobs/" + r.job().ingestionJobId())).body(response)
                : ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PatchMapping("/api/v1/ingestion/jobs/{jobId}")
    @Operation(summary = "Report job status and counts (RUNNING, PUBLISHED or FAILED)",
            description = "Repeating the final PUBLISHED report with the same counts is a no-op; FAILED -> RUNNING "
                    + "restarts the job and increments attempt.")
    public JobDetailResponse update(@PathVariable long jobId, @RequestBody UpdateRequest body) {
        if (body == null || body.status() == null) {
            throw new DomainValidationException("status", "is required");
        }
        IngestionJobStatus status;
        try {
            status = IngestionJobStatus.valueOf(body.status());
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("status", "must be RUNNING, PUBLISHED or FAILED");
        }
        jobs.update(jobId, new IngestionJobService.UpdateCommand(status, body.rowsTotal(), body.rowsValid(),
                body.rowsRejected(), body.eventsPublished(), body.outputUri(), body.rejectedUri(), body.errors()));
        return JobDetailResponse.from(jobs.get(jobId));
    }

    @GetMapping("/api/v1/ingestion/jobs/{jobId}")
    @Operation(summary = "Get a job with its manifest, error history and downstream processing progress")
    public JobDetailResponse get(@PathVariable long jobId) {
        return JobDetailResponse.from(jobs.get(jobId));
    }

    @GetMapping("/api/v1/ingestion/jobs")
    @Operation(summary = "List batch ingestion jobs, newest first")
    public PageResponse<JobResponse> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String sourceCode,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        IngestionJobStatus parsed = null;
        if (status != null) {
            try {
                parsed = IngestionJobStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new DomainValidationException("status", "must be REGISTERED, RUNNING, PUBLISHED or FAILED");
            }
        }
        PageRequest page = new PageRequest(cursor, limit);
        return PageResponse.of(jobs.list(parsed, sourceCode, page), page.limit(), JobResponse::from);
    }
}
