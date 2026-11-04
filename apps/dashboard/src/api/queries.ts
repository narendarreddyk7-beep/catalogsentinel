import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idempotencyKey, request, withQuery } from "./client";
import type {
  CanonicalProduct,
  CanonicalProductDetail,
  Candidate,
  Coverage,
  DashboardSummary,
  DeadLetterPeek,
  DecisionResponse,
  Gap,
  GapDetail,
  IngestionJob,
  IngestionJobDetail,
  Listing,
  ListingDetail,
  Page,
  ProcessingMetrics,
  ReconciliationJob,
  ReplayResult,
  Source,
} from "./types";

type Filters = Record<string, string | number | boolean | undefined>;

/** Cursor pagination: each page fetches the next cursor; "Load more" appends. */
function usePaged<T>(key: unknown[], path: string, filters: Filters, enabled = true) {
  return useInfiniteQuery({
    queryKey: [...key, filters],
    queryFn: ({ pageParam, signal }) =>
      request<Page<T>>(withQuery(path, { ...filters, cursor: pageParam ?? undefined }), { signal }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => (last.page.hasMore ? last.page.nextCursor : undefined),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export const useSources = (role?: "MARKETPLACE" | "REFERENCE") =>
  useQuery({
    queryKey: ["sources", role],
    queryFn: ({ signal }) => request<Page<Source>>(withQuery("/api/v1/sources", { role, limit: 200 }), { signal }),
    staleTime: 60_000,
  });

export const useSummary = () =>
  useQuery({
    queryKey: ["summary"],
    queryFn: ({ signal }) => request<DashboardSummary>("/api/v1/dashboard/summary", { signal }),
    refetchInterval: 30_000,
  });

export const useCoverage = (targetSourceId: number | undefined) =>
  useQuery({
    queryKey: ["coverage", targetSourceId],
    queryFn: ({ signal }) =>
      request<Coverage>(withQuery("/api/v1/dashboard/coverage", { targetSourceId }), { signal }),
    enabled: targetSourceId !== undefined,
    placeholderData: keepPreviousData,
  });

export const useProcessing = (windowMinutes: number) =>
  useQuery({
    queryKey: ["processing", windowMinutes],
    queryFn: ({ signal }) =>
      request<ProcessingMetrics>(withQuery("/api/v1/metrics/processing", { windowMinutes }), { signal }),
    refetchInterval: 15_000,
    placeholderData: keepPreviousData,
  });

export const useListings = (filters: Filters) => usePaged<Listing>(["listings"], "/api/v1/listings", filters);

export const useListing = (listingId: number | undefined) =>
  useQuery({
    queryKey: ["listing", listingId],
    queryFn: ({ signal }) => request<ListingDetail>(`/api/v1/listings/${listingId}`, { signal }),
    enabled: listingId !== undefined,
  });

export const useCanonicalProducts = (filters: Filters) =>
  usePaged<CanonicalProduct>(["canonical-products"], "/api/v1/canonical-products", filters);

export const useCanonicalProduct = (id: number | null | undefined) =>
  useQuery({
    queryKey: ["canonical-product", id],
    queryFn: ({ signal }) => request<CanonicalProductDetail>(`/api/v1/canonical-products/${id}`, { signal }),
    enabled: id !== undefined && id !== null,
  });

export const useGaps = (filters: Filters, enabled = true) =>
  usePaged<Gap>(["gaps"], "/api/v1/gaps", filters, enabled);

export const useGap = (gapId: number | undefined) =>
  useQuery({
    queryKey: ["gap", gapId],
    queryFn: ({ signal }) => request<GapDetail>(`/api/v1/gaps/${gapId}`, { signal }),
    enabled: gapId !== undefined,
  });

export const useCandidates = (filters: Filters) =>
  usePaged<Candidate>(["candidates"], "/api/v1/match-candidates", filters);

export const useCandidate = (candidateId: number | undefined) =>
  useQuery({
    queryKey: ["candidate", candidateId],
    queryFn: ({ signal }) => request<Candidate>(`/api/v1/match-candidates/${candidateId}`, { signal }),
    enabled: candidateId !== undefined,
  });

export const useIngestionJobs = (filters: Filters) =>
  usePaged<IngestionJob>(["ingestion-jobs"], "/api/v1/ingestion/jobs", filters);

export const useIngestionJob = (jobId: number | undefined) =>
  useQuery({
    queryKey: ["ingestion-job", jobId],
    queryFn: ({ signal }) => request<IngestionJobDetail>(`/api/v1/ingestion/jobs/${jobId}`, { signal }),
    enabled: jobId !== undefined,
    // Progress keeps moving while the worker drains the job's events.
    refetchInterval: (q) => (q.state.data?.progress.fullyProcessed ? false : 5_000),
  });

export const useReconciliationJobs = () =>
  usePaged<ReconciliationJob>(["reconciliation-jobs"], "/api/v1/reconciliation/jobs", { limit: 20 });

export const useDeadLetters = (queue: string) =>
  useQuery({
    queryKey: ["dead-letters", queue],
    queryFn: ({ signal }) =>
      request<DeadLetterPeek>(withQuery("/api/v1/admin/dead-letters", { queue, max: 10 }), { signal }),
    retry: false,
  });

// ------------------------------------------------------------------ mutations

export function useGapDecision(gapId: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { decision: string; reason?: string }) =>
      request<DecisionResponse<Gap>>(`/api/v1/gaps/${gapId}/decisions`, {
        method: "POST",
        body,
        idempotencyKey: idempotencyKey(),
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["gap", gapId] });
      void client.invalidateQueries({ queryKey: ["gaps"] });
      void client.invalidateQueries({ queryKey: ["coverage"] });
      void client.invalidateQueries({ queryKey: ["summary"] });
    },
  });
}

export function useMatchDecision(candidateId: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { decision: "APPROVE" | "REJECT"; reason?: string }) =>
      request<DecisionResponse<Candidate>>(`/api/v1/match-candidates/${candidateId}/decisions`, {
        method: "POST",
        body,
        idempotencyKey: idempotencyKey(),
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["candidate", candidateId] });
      void client.invalidateQueries({ queryKey: ["candidates"] });
      void client.invalidateQueries({ queryKey: ["summary"] });
    },
  });
}

export function useStartReconciliation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { targetSourceCode: string }) =>
      request<ReconciliationJob>("/api/v1/reconciliation/jobs", {
        method: "POST",
        body: { ...body, requestedBy: "dashboard" },
        idempotencyKey: idempotencyKey(),
      }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["reconciliation-jobs"] }),
  });
}

export function useReplayDeadLetters() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { queue: string; maxMessages: number }) =>
      request<ReplayResult>("/api/v1/admin/dead-letters/replay", { method: "POST", body }),
    onSuccess: (_r, vars) => {
      void client.invalidateQueries({ queryKey: ["dead-letters", vars.queue] });
      void client.invalidateQueries({ queryKey: ["processing"] });
    },
  });
}
