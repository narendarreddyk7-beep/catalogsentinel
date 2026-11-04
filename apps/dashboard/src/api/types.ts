/**
 * Response shapes of the CatalogSentinel API. Field names mirror the Java records serialized by Jackson
 * (apps/catalog-api/.../web and libs/catalog-core). Instants are ISO-8601 strings; BigDecimal values are numbers.
 */

export interface Page<T> {
  items: T[];
  page: { limit: number; nextCursor: string | null; hasMore: boolean };
}

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

export type SourceRole = "MARKETPLACE" | "REFERENCE";

export interface Source {
  sourceId: number;
  code: string;
  name: string;
  role: SourceRole;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardSummary {
  generatedAt: string;
  sourcesByRole: Record<string, number>;
  listingsByStatus: Record<string, number>;
  canonicalProducts: number;
  matchCandidatesByReviewStatus: Record<string, number>;
  gapsByStatus: Record<string, number>;
  openGapsByPriority: Record<string, number>;
  eventsLast24hByOutcome: Record<string, number>;
  outboxBacklog: Record<string, number>;
  lastReconciliation: { jobId?: number; targetSourceCode?: string; status?: string; finishedAt?: string | null };
}

export interface CoverageRow {
  category: string;
  referenceProducts: number;
  carried: number;
  openGaps: number;
  confirmedGaps: number;
  dismissedGaps: number;
  notEvaluated: number;
}

export interface Coverage {
  targetSourceId: number;
  targetSourceCode: string;
  generatedAt: string;
  categories: CoverageRow[];
  total: CoverageRow;
}

export interface ThroughputPoint {
  minute: string;
  outcome: string;
  events: number;
}

export interface LatencyStats {
  ingestionPath: string;
  events: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
}

export type QueueDepth = { visible: number; inFlight: number; delayed: number } | { unavailable: true };

export interface ProcessingMetrics {
  generatedAt: string;
  windowMinutes: number;
  throughput: ThroughputPoint[];
  latencyByPath: LatencyStats[];
  outbox: { pending: number; failed: number; oldestPendingSeconds: number };
  queues: Record<string, QueueDepth>;
}

export type ListingStatus = "ACTIVE" | "WITHDRAWN";

export interface Listing {
  listingId: number;
  sourceId: number;
  sourceCode: string;
  sourceRole: SourceRole;
  externalProductId: string;
  status: ListingStatus;
  sourceVersion: number;
  title: string | null;
  brand: string | null;
  model: string | null;
  category: string | null;
  gtin: string | null;
  attributes: Record<string, string>;
  normalizedTitle: string | null;
  normalizedBrand: string | null;
  normalizedModel: string | null;
  categoryLeaf: string | null;
  extractedAttributes: Record<string, string>;
  contentHash: string | null;
  lastEventId: string | null;
  sourceUpdatedAt: string | null;
  withdrawnAt: string | null;
  createdAt: string;
  updatedAt: string;
  canonicalProductId: number | null;
  linkConfidence: number | null;
  linkMethod: string | null;
}

export interface ProcessedEvent {
  eventId: string;
  sourceId: number;
  externalProductId: string;
  listingId: number;
  eventType: "LISTING_UPSERTED" | "LISTING_WITHDRAWN";
  requestFingerprint: string;
  requestedVersion: number;
  appliedVersion: number | null;
  outcome: string;
  reason: string | null;
  ingestionPath: string;
  ingestionJobId: number | null;
  correlationId: string | null;
  occurredAt: string;
  receivedAt: string;
  processedAt: string;
}

export interface ListingDetail {
  listing: Listing;
  history: ProcessedEvent[];
}

export interface Evidence {
  code: string;
  effect: "SUPPORTS" | "CONTRADICTS" | "BLOCKS_AUTO_MATCH" | "FORCES_NO_MATCH";
  detail: string | null;
}

export interface ListingSide {
  listingId: number;
  sourceCode: string;
  sourceRole: string;
  externalProductId: string;
  title: string | null;
  brand: string | null;
  model: string | null;
  gtin: string | null;
  category: string | null;
  status: string;
  attributes: Record<string, string>;
}

export interface Candidate {
  candidateId: number;
  score: number;
  decision: "AUTO_MATCH" | "REVIEW" | "NO_MATCH";
  reviewStatus: string;
  matcherVersion: string;
  retrievalMethod: string;
  featureScores: Record<string, number | null>;
  evidence: Evidence[];
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
  updatedAt: string;
  left: ListingSide;
  right: ListingSide;
}

export interface CanonicalProduct {
  canonicalProductId: number;
  title: string;
  brand: string | null;
  model: string | null;
  category: string | null;
  gtin: string | null;
  categoryLeaf: string | null;
  status: string;
  mergedIntoId: number | null;
  representativeListingId: number | null;
  activeListings: number;
  marketplaceListings: number;
  createdAt: string;
  updatedAt: string;
}

export interface LinkedListing {
  listingId: number;
  sourceCode: string;
  sourceRole: string;
  externalProductId: string;
  title: string | null;
  status: string;
  confidence: number | null;
  matchMethod: string;
  reviewStatus: string;
  viaCandidateId: number | null;
}

export interface CanonicalProductDetail {
  product: CanonicalProduct;
  listings: LinkedListing[];
}

export type GapStatus = "OPEN" | "CONFIRMED" | "DISMISSED" | "RESOLVED";

export interface GapEvidence {
  assessor?: string;
  reason?: string;
  explanation?: string;
  referenceListings?: number[];
  referenceCoverage?: { sources: number; of: number };
  demand?: { value: number; source: "SYNTHETIC" | "UNKNOWN" };
  bestCandidate?: Record<string, unknown>;
}

export interface Gap {
  gapId: number;
  canonicalProductId: number;
  targetSourceId: number;
  targetSourceCode: string;
  status: GapStatus;
  reason: string;
  confidence: number;
  priorityScore: number;
  priority: "P1" | "P2" | "P3";
  bestCandidateListingId: number | null;
  bestCandidateScore: number | null;
  evidence: GapEvidence;
  detectionCount: number;
  firstDetectedAt: string;
  lastDetectedAt: string;
  lastJobId: number | null;
  resolvedAt: string | null;
  resolution: string | null;
  updatedAt: string;
  productTitle: string;
  productBrand: string | null;
  productCategory: string | null;
  referenceListingId: number;
  referenceTitle: string | null;
  referenceSourceCode: string;
  referenceGtin: string | null;
  bestCandidateTitle: string | null;
  bestCandidateSourceCode: string | null;
}

export interface ReviewDecision {
  decisionId: number;
  matchCandidateId: number | null;
  gapId: number | null;
  decision: string;
  previousStatus: string | null;
  newStatus: string;
  reviewer: string;
  reason: string | null;
  idempotencyKey: string | null;
  decidedAt: string;
}

export interface GapDetail {
  gap: Gap;
  history: ReviewDecision[];
}

export interface DecisionResult {
  subjectId: number;
  previousStatus: string;
  newStatus: string;
  changed: boolean;
  decisionId: number | null;
}

export interface DecisionResponse<T> {
  result: DecisionResult;
  subject: T;
}

export interface ReconciliationJob {
  jobId: number;
  targetSourceCode: string;
  referenceSourceCode: string | null;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | string;
  requestedBy: string;
  stats: Record<string, number> | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface IngestionJob {
  jobId: number;
  jobKey: string;
  sourceCode: string;
  status: "REGISTERED" | "RUNNING" | "PUBLISHED" | "FAILED";
  attempt: number;
  inputUri: string | null;
  outputUri: string | null;
  rejectedUri: string | null;
  rowsTotal: number | null;
  rowsValid: number | null;
  rowsRejected: number | null;
  eventsPublished: number | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  updatedAt: string;
}

export interface IngestionJobDetail {
  job: IngestionJob;
  manifest: Record<string, unknown>;
  errors: { at: string; message: string }[];
  progress: {
    processedEvents: number;
    byOutcome: Record<string, number>;
    remainingEvents: number | null;
    fullyProcessed: boolean;
    firstProcessedAt: string | null;
    lastProcessedAt: string | null;
  };
}

export interface DeadLetter {
  messageId: string;
  receiveCount: number;
  sentAt: string;
  attributes: Record<string, string>;
  body: string;
  bodyTruncated: boolean;
}

export interface DeadLetterPeek {
  queue: string;
  sourceQueue: string;
  messages: DeadLetter[];
  note: string;
}

export interface ReplayResult {
  deadLetterQueue: string;
  sourceQueue: string;
  replayed: number;
  failed: number;
  messageIds: string[];
}
