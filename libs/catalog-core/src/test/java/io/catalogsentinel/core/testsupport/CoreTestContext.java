package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.core.batch.IngestionJobRepository;
import io.catalogsentinel.core.batch.IngestionJobService;
import io.catalogsentinel.core.ingestion.IngestionMetrics;
import io.catalogsentinel.core.ingestion.ListingEventMapper;
import io.catalogsentinel.core.ingestion.ListingIngestionService;
import io.catalogsentinel.core.ingestion.ProcessedEventRepository;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.listing.ListingQueryRepository;
import io.catalogsentinel.core.listing.ListingQueryService;
import io.catalogsentinel.core.listing.ListingRepository;
import io.catalogsentinel.core.matching.CanonicalProductRepository;
import io.catalogsentinel.core.matching.MatchCandidateRepository;
import io.catalogsentinel.core.matching.MatchQueryRepository;
import io.catalogsentinel.core.matching.MatchingRepository;
import io.catalogsentinel.core.matching.MatchingService;
import io.catalogsentinel.domain.matching.RuleBasedMatcher;
import io.catalogsentinel.core.gaps.GapQueryRepository;
import io.catalogsentinel.core.gaps.GapRepository;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository;
import io.catalogsentinel.core.gaps.ReconciliationService;
import io.catalogsentinel.core.metrics.DashboardRepository;
import io.catalogsentinel.core.review.ReviewDecisionRepository;
import io.catalogsentinel.core.review.ReviewService;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.core.source.SourceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * Hand-wired object graph for core integration tests: the same classes the Spring Boot applications wire,
 * without needing an application context. Keeps core tests fast and framework-version independent.
 */
public class CoreTestContext {

    public final DataSource dataSource;
    public final JdbcTemplate jdbcTemplate;
    public final NamedParameterJdbcTemplate jdbc;
    public final TransactionTemplate tx;
    public final JsonCodec json = new TestJsonCodec();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));
    public final RecordingIngestionMetrics metrics = new RecordingIngestionMetrics();

    public final SourceRepository sourceRepository;
    public final SourceService sourceService;
    public final ListingRepository listingRepository;
    public final ListingQueryRepository listingQueryRepository;
    public final ProcessedEventRepository processedEventRepository;
    public final OutboxRepository outboxRepository;
    public final ListingIngestionService ingestionService;
    public final ListingQueryService listingQueryService;
    public final ListingEventMapper eventMapper;
    public final MatchingRepository matchingRepository;
    public final MatchCandidateRepository matchCandidateRepository;
    public final CanonicalProductRepository canonicalProductRepository;
    public final MatchingService matchingService;
    public final MatchQueryRepository matchQueryRepository;
    public final ReconciliationJobRepository reconciliationJobRepository;
    public final GapRepository gapRepository;
    public final GapQueryRepository gapQueryRepository;
    public final ReviewDecisionRepository reviewDecisionRepository;
    public final ReconciliationService reconciliationService;
    public final ReviewService reviewService;
    public final DashboardRepository dashboardRepository;
    public final IngestionJobRepository ingestionJobRepository;
    public final IngestionJobService ingestionJobService;

    public CoreTestContext() {
        this(TestDatabase.dataSource());
    }

    /** With an explicit (for example pooled) data source; the schema must already be migrated. */
    public CoreTestContext(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.tx.setTimeout(30);
        this.sourceRepository = new SourceRepository(jdbc);
        this.sourceService = new SourceService(sourceRepository);
        this.listingRepository = new ListingRepository(jdbc);
        this.listingQueryRepository = new ListingQueryRepository(jdbc, json);
        this.processedEventRepository = new ProcessedEventRepository(jdbc);
        this.outboxRepository = new OutboxRepository(jdbc, json);
        this.ingestionService = new ListingIngestionService(sourceRepository, listingRepository,
                processedEventRepository, outboxRepository, tx, json, clock, metrics);
        this.listingQueryService = new ListingQueryService(listingQueryRepository, processedEventRepository);
        this.eventMapper = new ListingEventMapper(clock);
        this.matchingRepository = new MatchingRepository(jdbc, json);
        this.matchCandidateRepository = new MatchCandidateRepository(jdbc, json);
        this.canonicalProductRepository = new CanonicalProductRepository(jdbc);
        this.reviewDecisionRepository = new ReviewDecisionRepository(jdbc);
        this.matchingService = new MatchingService(matchingRepository, matchCandidateRepository,
                canonicalProductRepository, new RuleBasedMatcher(), reviewDecisionRepository, tx, clock);
        this.matchQueryRepository = new MatchQueryRepository(jdbc, json);
        this.reconciliationJobRepository = new ReconciliationJobRepository(jdbc, json);
        this.gapRepository = new GapRepository(jdbc, json);
        this.gapQueryRepository = new GapQueryRepository(jdbc);
        this.reconciliationService = new ReconciliationService(reconciliationJobRepository, gapRepository,
                sourceRepository, matchingService, reviewDecisionRepository, tx, clock);
        this.reviewService = new ReviewService(matchCandidateRepository, gapRepository, reviewDecisionRepository,
                matchingService, tx, clock);
        this.dashboardRepository = new DashboardRepository(jdbc);
        this.ingestionJobRepository = new IngestionJobRepository(jdbc);
        this.ingestionJobService = new IngestionJobService(ingestionJobRepository, sourceRepository, json, tx, clock);
    }

    public void reset() {
        TestDatabase.reset(dataSource);
        metrics.clear();
    }

    public long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    public IngestionMetrics ingestionMetrics() {
        return metrics;
    }
}
