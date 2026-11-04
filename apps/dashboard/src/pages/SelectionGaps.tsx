import { useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { describeError } from "../api/client";
import { useGap, useGapDecision, useGaps } from "../api/queries";
import type { Gap, GapStatus } from "../api/types";
import { Meter } from "../charts/BarList";
import { LoadMore, PageHeader } from "../components/Shell";
import { Empty, ErrorState, Loading, QueryState } from "../components/States";
import { GapStatusBadge, Priority } from "../components/Status";
import { TargetPicker, useTarget } from "../components/Target";
import { ago, full, label, score, when } from "../lib/format";

const REASONS = ["NO_CANDIDATE", "ONLY_NON_MATCHING_CANDIDATES", "VARIANT_NOT_CARRIED", "UNCONFIRMED_CANDIDATE", "CANDIDATE_REJECTED"];

/** Decisions the gap lifecycle allows from each status (mirrors GapLifecycle in the domain). */
export const ALLOWED: Record<GapStatus, { decision: string; label: string; needsReason?: boolean }[]> = {
  OPEN: [
    { decision: "CONFIRM", label: "Confirm gap" },
    { decision: "DISMISS", label: "Dismiss", needsReason: true },
    { decision: "RESOLVE", label: "Mark resolved" },
  ],
  CONFIRMED: [
    { decision: "DISMISS", label: "Dismiss", needsReason: true },
    { decision: "RESOLVE", label: "Mark resolved" },
  ],
  DISMISSED: [{ decision: "REOPEN", label: "Reopen" }],
  RESOLVED: [],
};

const DONE: Record<string, string> = {
  CONFIRM: "Gap confirmed.",
  DISMISS: "Gap dismissed. Future reconciliations will not reopen it.",
  RESOLVE: "Gap marked resolved.",
  REOPEN: "Gap reopened.",
};

function GapFilters() {
  const [params, setParams] = useSearchParams();
  const set = (key: string, value: string) =>
    setParams((p) => {
      if (value) {
        p.set(key, value);
      } else {
        p.delete(key);
      }
      return p;
    });
  const root = params.get("categoryRoot");
  return (
    <div className="filters">
      <label className="field">
        Status
        <select value={params.get("status") ?? "OPEN"} onChange={(e) => set("status", e.target.value)}>
          <option value="OPEN">Open</option>
          <option value="CONFIRMED">Confirmed</option>
          <option value="DISMISSED">Dismissed</option>
          <option value="RESOLVED">Resolved</option>
        </select>
      </label>
      <label className="field">
        Priority
        <select value={params.get("priority") ?? ""} onChange={(e) => set("priority", e.target.value)}>
          <option value="">Any priority</option>
          <option value="P1">P1</option>
          <option value="P2">P2</option>
          <option value="P3">P3</option>
        </select>
      </label>
      <label className="field">
        Why it is a gap
        <select value={params.get("reason") ?? ""} onChange={(e) => set("reason", e.target.value)}>
          <option value="">Any reason</option>
          {REASONS.map((r) => (
            <option key={r} value={r}>
              {label(r)}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        Minimum confidence
        <select value={params.get("minConfidence") ?? ""} onChange={(e) => set("minConfidence", e.target.value)}>
          <option value="">Any</option>
          <option value="0.5">0.50 and above</option>
          <option value="0.8">0.80 and above</option>
        </select>
      </label>
      {root ? (
        <p className="field">
          Category
          <span className="actions">
            <strong>{root}</strong>
            <button type="button" className="btn btn-quiet" onClick={() => set("categoryRoot", "")}>
              Show all categories
            </button>
          </span>
        </p>
      ) : null}
    </div>
  );
}

function GapTable({ targetId, selectedId }: { targetId: number; selectedId: number | undefined }) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const gaps = useGaps({
    targetSourceId: targetId,
    status: params.get("status") ?? "OPEN",
    priority: params.get("priority") ?? undefined,
    reason: params.get("reason") ?? undefined,
    categoryRoot: params.get("categoryRoot") ?? undefined,
    minConfidence: params.get("minConfidence") ?? undefined,
    limit: 50,
  });
  return (
    <QueryState
      query={gaps}
      what="gaps"
      isEmpty={(d) => d.pages[0]?.items.length === 0}
      empty={
        <Empty title="No gaps match these filters">
          Either the marketplace carries everything here, or no reconciliation has run yet. Start one under{" "}
          <Link to="/jobs">Batch jobs</Link>.
        </Empty>
      }
    >
      {(d) => {
        const rows = d.pages.flatMap((p) => p.items);
        return (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Priority</th>
                    <th>Product</th>
                    <th>Why</th>
                    <th>Confidence</th>
                    <th>Reference</th>
                    <th>Last detected</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((g: Gap) => (
                    <tr
                      key={g.gapId}
                      className="clickable"
                      aria-selected={g.gapId === selectedId}
                      onClick={() => navigate({ pathname: `/gaps/${g.gapId}`, search: params.toString() })}
                    >
                      <td>
                        <Priority value={g.priority} />
                      </td>
                      <td>
                        <Link className="cell-title" to={{ pathname: `/gaps/${g.gapId}`, search: params.toString() }} onClick={(e) => e.stopPropagation()}>
                          {g.productTitle}
                        </Link>
                        <span className="cell-sub">{[g.productBrand, g.productCategory].filter(Boolean).join(", ")}</span>
                      </td>
                      <td>{label(g.reason)}</td>
                      <td style={{ minWidth: 120 }}>
                        <span className="num">{score(g.confidence)}</span>
                        <Meter value={g.confidence} max={1} tone="neutral" label={`Confidence ${score(g.confidence)}`} />
                      </td>
                      <td title={g.referenceTitle ?? undefined}>
                        {g.referenceSourceCode}
                        {g.referenceGtin ? <span className="cell-sub num">GTIN {g.referenceGtin}</span> : null}
                      </td>
                      <td>
                        {ago(g.lastDetectedAt)}
                        <span className="cell-sub">{full(g.detectionCount)} detections</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <LoadMore hasNextPage={gaps.hasNextPage} isFetchingNextPage={gaps.isFetchingNextPage} fetchNextPage={gaps.fetchNextPage} shown={rows.length} />
          </>
        );
      }}
    </QueryState>
  );
}

function DecisionForm({ gap }: { gap: Gap }) {
  const mutation = useGapDecision(gap.gapId);
  const [reason, setReason] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const options = ALLOWED[gap.status] ?? [];
  if (options.length === 0) {
    return <p className="cell-sub">Resolved gaps are final; the next reconciliation reopens a new gap if the product disappears again.</p>;
  }
  const decide = (decision: string, needsReason?: boolean) => {
    if (needsReason && !reason.trim()) {
      setDone(null);
      return;
    }
    mutation.mutate(
      { decision, reason: reason.trim() || undefined },
      { onSuccess: (r) => setDone(r.result.changed ? DONE[decision] ?? "Saved." : "Nothing changed: the gap was already in that state.") },
    );
  };
  return (
    <div className="detail" style={{ gap: 12 }}>
      {done ? (
        <p className="notice" role="status">
          {done}
        </p>
      ) : null}
      {mutation.error ? <ErrorState error={mutation.error} /> : null}
      <label className="field">
        Reason (required to dismiss)
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. discontinued by the supplier" />
      </label>
      <div className="actions">
        {options.map((o) => (
          <button
            key={o.decision}
            type="button"
            className={o.decision === "CONFIRM" ? "btn btn-primary" : "btn"}
            disabled={mutation.isPending || (o.needsReason === true && !reason.trim())}
            onClick={() => decide(o.decision, o.needsReason)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function GapPanel({ gapId }: { gapId: number }) {
  const detail = useGap(gapId);
  if (detail.isPending) {
    return <Loading what="gap" />;
  }
  if (!detail.data) {
    return <ErrorState error={detail.error} retry={() => void detail.refetch()} />;
  }
  const { gap: g, history } = detail.data;
  const demand = g.evidence.demand;
  return (
    <div className="detail">
      <div>
        <p className="actions">
          <Priority value={g.priority} />
          <GapStatusBadge status={g.status} />
        </p>
        <h2 style={{ marginTop: 8 }}>{g.productTitle}</h2>
        <p className="cell-sub">
          Missing from {g.targetSourceCode}; first detected {when(g.firstDetectedAt)}
        </p>
      </div>
      {g.evidence.explanation ? <p>{g.evidence.explanation}</p> : null}
      <dl className="kv">
        <dt>Why</dt>
        <dd>{label(g.reason)}</dd>
        <dt>Confidence</dt>
        <dd className="num">{score(g.confidence)}</dd>
        <dt>Priority score</dt>
        <dd className="num">{score(g.priorityScore)}</dd>
        <dt>Reference listing</dt>
        <dd>
          <Link to={`/catalog/listings/${g.referenceListingId}`}>{g.referenceTitle ?? `#${g.referenceListingId}`}</Link>
          <span className="cell-sub">
            {g.referenceSourceCode}
            {g.referenceGtin ? `, GTIN ${g.referenceGtin}` : ""}
          </span>
        </dd>
        <dt>Closest marketplace listing</dt>
        <dd>
          {g.bestCandidateListingId ? (
            <>
              <Link to={`/catalog/listings/${g.bestCandidateListingId}`}>{g.bestCandidateTitle}</Link>
              <span className="cell-sub">
                {g.bestCandidateSourceCode}, match score {score(g.bestCandidateScore)}
              </span>
            </>
          ) : (
            "none found"
          )}
        </dd>
        <dt>Reference coverage</dt>
        <dd>
          {g.evidence.referenceCoverage
            ? `${g.evidence.referenceCoverage.sources} of ${g.evidence.referenceCoverage.of} reference sources list it`
            : "–"}
        </dd>
        <dt>Demand</dt>
        <dd>
          {demand?.source === "SYNTHETIC" ? (
            <>
              <span className="num">{score(demand.value)}</span> <span className="synthetic-note">synthetic index, not real demand data</span>
            </>
          ) : (
            "unknown (scored as neutral)"
          )}
        </dd>
      </dl>
      <section>
        <h3>Decision</h3>
        <div style={{ marginTop: 8 }}>
          <DecisionForm gap={g} />
        </div>
      </section>
      <section>
        <h3>History</h3>
        {history.length === 0 ? (
          <p className="cell-sub" style={{ marginTop: 8 }}>
            No decisions yet.
          </p>
        ) : (
          <ol className="timeline" style={{ marginTop: 8 }}>
            {history.map((h) => (
              <li key={h.decisionId}>
                <strong>{label(h.decision)}</strong> by {h.reviewer}, {when(h.decidedAt)}
                <span className="cell-sub">
                  {label(h.previousStatus)} to {label(h.newStatus)}
                  {h.reason ? `: ${h.reason}` : ""}
                </span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

export function SelectionGapsPage() {
  const { gapId } = useParams();
  const selected = gapId ? Number(gapId) : undefined;
  const { target, marketplaces, loading, error, setTarget } = useTarget();
  const errorDetail = error ? describeError(error) : null;
  return (
    <>
      <PageHeader
        title="Selection gaps"
        lede="Products a reference supplier lists that the marketplace does not carry, highest priority first. Confirm the ones worth sourcing; dismiss the rest with a reason."
      >
        <TargetPicker marketplaces={marketplaces} target={target} onChange={setTarget} />
      </PageHeader>
      <GapFilters />
      {loading ? (
        <Loading what="marketplaces" />
      ) : !target ? (
        errorDetail ? <ErrorState error={error} /> : <Empty title="No marketplace registered">Register a MARKETPLACE source first.</Empty>
      ) : (
        <div className={selected ? "master-detail" : undefined}>
          <section aria-label="Gaps">
            <GapTable targetId={target.sourceId} selectedId={selected} />
          </section>
          {selected ? (
            <aside aria-label="Gap detail">
              <GapPanel gapId={selected} />
            </aside>
          ) : null}
        </div>
      )}
    </>
  );
}
