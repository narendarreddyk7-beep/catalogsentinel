package io.catalogsentinel.api.web;

import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.core.metrics.DashboardRepository;
import io.catalogsentinel.core.source.CatalogSource;
import io.catalogsentinel.core.source.SourceService;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.source.SourceRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Monitoring", description = "Dashboard aggregates and processing metrics")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private final DashboardRepository dashboard;
    private final QueueClient queues;
    private final QueueNames names;
    private final Clock clock;
    private final SourceService sources;

    public DashboardController(DashboardRepository dashboard, QueueClient queues, QueueNames names, Clock clock,
                               SourceService sources) {
        this.dashboard = dashboard;
        this.queues = queues;
        this.names = names;
        this.clock = clock;
        this.sources = sources;
    }

    public record CoverageResponse(long targetSourceId, String targetSourceCode, Instant generatedAt,
                                   List<DashboardRepository.CoverageRow> categories, DashboardRepository.CoverageRow total) {
    }

    @GetMapping("/api/v1/dashboard/coverage")
    @Operation(summary = "Selection coverage of a marketplace by top-level category of the reference assortment",
            description = "For each category: reference products, how many the target carries, and the rest by gap "
                    + "status (open, confirmed, dismissed) or not yet evaluated. The parts add up to the reference "
                    + "count. Pass targetSourceId or targetSourceCode (a MARKETPLACE source).")
    public CoverageResponse coverage(@RequestParam(required = false) Long targetSourceId,
                                     @RequestParam(required = false) String targetSourceCode) {
        if ((targetSourceId == null) == (targetSourceCode == null)) {
            throw new DomainValidationException("targetSourceId", "pass exactly one of targetSourceId or targetSourceCode");
        }
        CatalogSource target = targetSourceId != null ? sources.get(targetSourceId) : sources.getByCode(targetSourceCode);
        if (target.role() != SourceRole.MARKETPLACE) {
            throw new DomainValidationException("targetSourceId", "must be a MARKETPLACE source");
        }
        List<DashboardRepository.CoverageRow> rows = dashboard.coverage(target.sourceId());
        DashboardRepository.CoverageRow total = new DashboardRepository.CoverageRow("all",
                rows.stream().mapToLong(DashboardRepository.CoverageRow::referenceProducts).sum(),
                rows.stream().mapToLong(DashboardRepository.CoverageRow::carried).sum(),
                rows.stream().mapToLong(DashboardRepository.CoverageRow::openGaps).sum(),
                rows.stream().mapToLong(DashboardRepository.CoverageRow::confirmedGaps).sum(),
                rows.stream().mapToLong(DashboardRepository.CoverageRow::dismissedGaps).sum(),
                rows.stream().mapToLong(DashboardRepository.CoverageRow::notEvaluated).sum());
        return new CoverageResponse(target.sourceId(), target.code(), clock.instant(), rows, total);
    }

    @GetMapping("/api/v1/dashboard/summary")
    @Operation(summary = "Catalog, matching, gap and ingestion totals for the overview page")
    public DashboardRepository.Summary summary() {
        return dashboard.summary(clock.instant());
    }

    @GetMapping("/api/v1/metrics/processing")
    @Operation(summary = "Throughput, end-to-end latency percentiles, outbox and queue backlog",
            description = "windowMinutes between 5 and 1440. Queue depths are read live from SQS; a queue that cannot "
                    + "be read is reported as unavailable rather than failing the whole response.")
    public Map<String, Object> processing(@RequestParam(defaultValue = "60") int windowMinutes) {
        if (windowMinutes < 5 || windowMinutes > 1440) {
            throw new DomainValidationException("windowMinutes", "must be between 5 and 1440");
        }
        DashboardRepository.ProcessingMetrics metrics = dashboard.processing(clock.instant(), Duration.ofMinutes(windowMinutes));
        Map<String, Object> queueDepths = new LinkedHashMap<>();
        for (String queue : List.of(names.listingEvents(), names.listingEventsDlq(), names.changeEvents(), names.changeEventsDlq())) {
            try {
                QueueClient.QueueDepth d = queues.depth(queue);
                queueDepths.put(queue, Map.of("visible", d.visible(), "inFlight", d.inFlight(), "delayed", d.delayed()));
            } catch (RuntimeException e) {
                log.warn("cannot read depth of queue {}: {}", queue, e.getMessage());
                queueDepths.put(queue, Map.of("unavailable", true));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", metrics.generatedAt());
        body.put("windowMinutes", metrics.windowMinutes());
        body.put("throughput", metrics.throughput());
        body.put("latencyByPath", metrics.latencyByPath());
        body.put("outbox", metrics.outbox());
        body.put("queues", queueDepths);
        return body;
    }
}
