package io.catalogsentinel.core.batch;

import io.catalogsentinel.core.batch.IngestionJob.IngestionJobStatus;
import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.source.CatalogSource;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.common.Violations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Registration and lifecycle of batch ingestion jobs, driven by the batch job itself.
 *
 * <ul>
 *   <li><b>Idempotent registration</b>: the job key is derived from the manifest by the producer, so submitting the
 *       same manifest again (a restart) returns the existing job instead of creating a second one. Reusing a key
 *       with a different manifest or source is a conflict.</li>
 *   <li><b>State machine</b>: REGISTERED -> RUNNING -> PUBLISHED | FAILED, and FAILED -> RUNNING as a restart that
 *       increments {@code attempt}. RUNNING -> RUNNING records progress. Repeating the final PUBLISHED report with
 *       the same counts is a no-op (the batch may retry the HTTP call); different counts are a conflict.</li>
 *   <li><b>Progress</b> of downstream processing is read from {@code processed_events} at query time.</li>
 * </ul>
 */
@Service
public class IngestionJobService {

    private static final Pattern JOB_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");
    private static final int MAX_MANIFEST_CHARS = 64 * 1024;
    private static final int MAX_URI = 1000;
    private static final int MAX_ERRORS_KEPT = 20;
    private static final int MAX_ERROR_CHARS = 2000;

    private final IngestionJobRepository jobs;
    private final SourceRepository sources;
    private final JsonCodec json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public IngestionJobService(IngestionJobRepository jobs, SourceRepository sources, JsonCodec json,
                               TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.sources = sources;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    /** Registration input. {@code manifest} is the parsed manifest object as submitted. */
    public record RegisterCommand(String jobKey, String sourceCode, Map<String, Object> manifest, String inputUri,
                                  String outputUri, String rejectedUri, Long rowsTotal) {
    }

    public record Registration(IngestionJob job, boolean created) {
    }

    /** A status report from the batch. Null counts and URIs leave the stored values unchanged. */
    public record UpdateCommand(IngestionJobStatus status, Long rowsTotal, Long rowsValid, Long rowsRejected,
                                Long eventsPublished, String outputUri, String rejectedUri, List<String> errors) {
    }

    /** A job plus downstream progress derived from the idempotency ledger. */
    public record JobView(IngestionJob job, Object manifest, Object errorSummary,
                          IngestionJobRepository.Progress progress, Long remainingEvents, boolean fullyProcessed) {
    }

    public Registration register(RegisterCommand cmd) {
        Violations v = new Violations();
        if (cmd.jobKey() == null || !JOB_KEY.matcher(cmd.jobKey()).matches()) {
            v.add("jobKey", "must match " + JOB_KEY.pattern());
        }
        v.requireText("sourceCode", cmd.sourceCode(), 64);
        if (cmd.manifest() == null || cmd.manifest().isEmpty()) {
            v.add("manifest", "is required and must be a non-empty object");
        }
        v.optionalText("inputUri", cmd.inputUri(), MAX_URI)
                .optionalText("outputUri", cmd.outputUri(), MAX_URI)
                .optionalText("rejectedUri", cmd.rejectedUri(), MAX_URI);
        if (cmd.rowsTotal() != null && cmd.rowsTotal() < 0) {
            v.add("rowsTotal", "must be >= 0");
        }
        v.throwIfAny();
        String manifestJson = json.write(cmd.manifest());
        if (manifestJson.length() > MAX_MANIFEST_CHARS) {
            throw new DomainValidationException("manifest", "must be at most " + MAX_MANIFEST_CHARS + " characters of JSON");
        }
        CatalogSource source = sources.findByCode(cmd.sourceCode()).orElseThrow(() ->
                new UnprocessableException("UNKNOWN_SOURCE", "source '" + cmd.sourceCode() + "' is not registered"));

        return Objects.requireNonNull(tx.execute(status -> {
            var inserted = jobs.insertIfAbsent(cmd.jobKey(), source.sourceId(), manifestJson, cmd.inputUri(),
                    cmd.outputUri(), cmd.rejectedUri(), cmd.rowsTotal(), clock.instant());
            if (inserted.isPresent()) {
                return new Registration(jobs.find(inserted.get()).orElseThrow(), true);
            }
            IngestionJob existing = jobs.findByKey(cmd.jobKey()).orElseThrow();
            boolean sameManifest = Objects.equals(json.read(existing.manifestJson(), Object.class),
                    json.read(manifestJson, Object.class));
            if (existing.sourceId() != source.sourceId() || !sameManifest) {
                throw new ConflictException("JOB_KEY_REUSED",
                        "job key '" + cmd.jobKey() + "' already identifies a job with a different source or manifest");
            }
            return new Registration(existing, false);
        }));
    }

    public IngestionJob update(long jobId, UpdateCommand cmd) {
        validateCounts(cmd);
        return Objects.requireNonNull(tx.execute(status -> {
            IngestionJob job = jobs.findForUpdate(jobId).orElseThrow(() -> new NotFoundException("ingestion job", jobId));
            IngestionJob next = transition(job, cmd, clock.instant());
            if (!next.equals(job)) {
                jobs.update(next);
            }
            return jobs.find(jobId).orElseThrow();
        }));
    }

    public JobView get(long jobId) {
        IngestionJob job = jobs.find(jobId).orElseThrow(() -> new NotFoundException("ingestion job", jobId));
        return view(job);
    }

    public Page<IngestionJob> list(IngestionJobStatus status, String sourceCode, PageRequest page) {
        Long sourceId = null;
        if (sourceCode != null) {
            sourceId = sources.findByCode(sourceCode).map(CatalogSource::sourceId).orElse(-1L);
        }
        Long before = page.cursor() == null ? null : CursorCodec.decodeLong(page.cursor());
        List<IngestionJob> rows = jobs.list(status, sourceId, before, page);
        return Page.fromOverfetch(rows, page, j -> CursorCodec.encode(j.ingestionJobId()));
    }

    private JobView view(IngestionJob job) {
        IngestionJobRepository.Progress progress = jobs.progress(job.ingestionJobId());
        Long remaining = job.eventsPublished() == null ? null
                : Math.max(0, job.eventsPublished() - progress.processedEvents());
        boolean done = job.status() == IngestionJobStatus.PUBLISHED && remaining != null && remaining == 0;
        return new JobView(job, json.read(job.manifestJson(), Object.class), json.read(job.errorSummaryJson(), Object.class),
                progress, remaining, done);
    }

    /** Pure transition function: returns the job as it should be stored after applying {@code cmd}. */
    IngestionJob transition(IngestionJob job, UpdateCommand cmd, Instant now) {
        IngestionJobStatus from = job.status();
        IngestionJobStatus to = cmd.status();
        if (to == null || to == IngestionJobStatus.REGISTERED) {
            throw new DomainValidationException("status", "must be RUNNING, PUBLISHED or FAILED");
        }
        if (from == IngestionJobStatus.PUBLISHED) {
            if (to == IngestionJobStatus.PUBLISHED && sameFinalCounts(job, cmd)) {
                return job; // retried completion report
            }
            throw new ConflictException("JOB_ALREADY_PUBLISHED", "job " + job.ingestionJobId() + " is already PUBLISHED");
        }
        int attempt = job.attempt();
        Instant startedAt = job.startedAt();
        Instant finishedAt = job.finishedAt();
        String errors = job.errorSummaryJson();
        switch (to) {
            case RUNNING -> {
                if (from == IngestionJobStatus.FAILED) {
                    attempt++;
                    startedAt = now;
                    finishedAt = null;
                } else if (from == IngestionJobStatus.REGISTERED) {
                    startedAt = now;
                }
            }
            case PUBLISHED -> {
                if (from != IngestionJobStatus.RUNNING) {
                    throw new ConflictException("INVALID_JOB_TRANSITION", "a " + from + " job cannot become PUBLISHED");
                }
                finishedAt = now;
            }
            case FAILED -> {
                finishedAt = now;
                errors = appendErrors(errors, cmd.errors(), now);
            }
            default -> throw new IllegalStateException();
        }
        if (to != IngestionJobStatus.FAILED && cmd.errors() != null && !cmd.errors().isEmpty()) {
            errors = appendErrors(errors, cmd.errors(), now);
        }
        IngestionJob next = new IngestionJob(job.ingestionJobId(), job.jobKey(), job.sourceId(), job.sourceCode(), to,
                job.manifestJson(), job.inputUri(), coalesce(cmd.outputUri(), job.outputUri()),
                coalesce(cmd.rejectedUri(), job.rejectedUri()), coalesce(cmd.rowsTotal(), job.rowsTotal()),
                coalesce(cmd.rowsValid(), job.rowsValid()), coalesce(cmd.rowsRejected(), job.rowsRejected()),
                coalesce(cmd.eventsPublished(), job.eventsPublished()), errors, attempt, job.createdAt(), startedAt,
                finishedAt, job.updatedAt());
        if (to == IngestionJobStatus.PUBLISHED) {
            requireConsistentFinalCounts(next);
        }
        return next;
    }

    private static void validateCounts(UpdateCommand cmd) {
        Violations v = new Violations();
        for (var e : Map.of("rowsTotal", nz(cmd.rowsTotal()), "rowsValid", nz(cmd.rowsValid()),
                "rowsRejected", nz(cmd.rowsRejected()), "eventsPublished", nz(cmd.eventsPublished())).entrySet()) {
            if (e.getValue() < 0) {
                v.add(e.getKey(), "must be >= 0");
            }
        }
        v.optionalText("outputUri", cmd.outputUri(), MAX_URI).optionalText("rejectedUri", cmd.rejectedUri(), MAX_URI);
        if (cmd.errors() != null && cmd.errors().size() > MAX_ERRORS_KEPT) {
            v.add("errors", "at most " + MAX_ERRORS_KEPT + " entries per report");
        }
        v.throwIfAny();
    }

    private static void requireConsistentFinalCounts(IngestionJob j) {
        Violations v = new Violations();
        if (j.rowsTotal() == null || j.rowsValid() == null || j.rowsRejected() == null || j.eventsPublished() == null) {
            v.add("counts", "rowsTotal, rowsValid, rowsRejected and eventsPublished are required to publish a job");
        } else {
            if (j.rowsValid() + j.rowsRejected() != j.rowsTotal()) {
                v.add("counts", "rowsValid + rowsRejected must equal rowsTotal");
            }
            if (j.eventsPublished() > j.rowsValid()) {
                // Fewer is legitimate: in-file duplicates of the same listing version collapse into one event.
                v.add("eventsPublished", "cannot exceed rowsValid");
            }
        }
        v.throwIfAny();
    }

    private static boolean sameFinalCounts(IngestionJob job, UpdateCommand cmd) {
        return (cmd.rowsTotal() == null || cmd.rowsTotal().equals(job.rowsTotal()))
                && (cmd.rowsValid() == null || cmd.rowsValid().equals(job.rowsValid()))
                && (cmd.rowsRejected() == null || cmd.rowsRejected().equals(job.rowsRejected()))
                && (cmd.eventsPublished() == null || cmd.eventsPublished().equals(job.eventsPublished()));
    }

    private String appendErrors(String existingJson, List<String> newErrors, Instant now) {
        List<Object> all = new ArrayList<>();
        Object parsed = json.read(existingJson == null ? "[]" : existingJson, Object.class);
        if (parsed instanceof List<?> list) {
            all.addAll(list);
        }
        if (newErrors != null) {
            for (String e : newErrors) {
                if (e != null && !e.isBlank()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("at", now.toString());
                    entry.put("message", e.length() > MAX_ERROR_CHARS ? e.substring(0, MAX_ERROR_CHARS) : e);
                    all.add(entry);
                }
            }
        }
        List<Object> kept = all.size() > MAX_ERRORS_KEPT ? all.subList(all.size() - MAX_ERRORS_KEPT, all.size()) : all;
        return json.write(kept);
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }
}
