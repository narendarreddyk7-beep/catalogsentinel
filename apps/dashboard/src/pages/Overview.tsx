import { Link } from "react-router";
import { useCoverage, useSummary } from "../api/queries";
import type { CoverageRow, DashboardSummary } from "../api/types";
import { Planogram } from "../charts/Planogram";
import { PageHeader } from "../components/Shell";
import { Empty, ErrorState, Loading, QueryState } from "../components/States";
import { StatusBadge } from "../components/Status";
import { TargetPicker, useTarget } from "../components/Target";
import { ago, count, full, label, percent } from "../lib/format";

const APPLIED = ["CREATED", "UPDATED", "WITHDRAWN", "REACTIVATED"];
const ORDERING = ["STALE_IGNORED", "VERSION_CONFLICT"];

function sum(map: Record<string, number> | undefined, keys?: string[]): number {
  if (!map) {
    return 0;
  }
  return Object.entries(map).reduce((a, [k, v]) => a + (keys === undefined || keys.includes(k) ? v : 0), 0);
}

function CatalogShelf({ s }: { s: DashboardSummary }) {
  return (
    <section className="shelf" aria-labelledby="catalog-h">
      <div className="shelf-head">
        <h2 id="catalog-h">Catalog</h2>
      </div>
      <dl className="stats">
        <div className="stat">
          <dt>Active listings</dt>
          <dd>
            <Link to="/catalog?status=ACTIVE">{count(s.listingsByStatus.ACTIVE ?? 0)}</Link>
          </dd>
          <span className="sub">{full(s.listingsByStatus.WITHDRAWN ?? 0)} withdrawn</span>
        </div>
        <div className="stat">
          <dt>Canonical products</dt>
          <dd>
            <Link to="/matching/products">{count(s.canonicalProducts)}</Link>
          </dd>
          <span className="sub">distinct items after matching</span>
        </div>
        <div className="stat">
          <dt>Sources</dt>
          <dd>{full(sum(s.sourcesByRole))}</dd>
          <span className="sub">
            {full(s.sourcesByRole.MARKETPLACE ?? 0)} marketplace, {full(s.sourcesByRole.REFERENCE ?? 0)} reference
          </span>
        </div>
        <div className="stat">
          <dt>Events processed, last 24 h</dt>
          <dd>{count(sum(s.eventsLast24hByOutcome))}</dd>
          <span className="sub">
            {full(sum(s.eventsLast24hByOutcome, APPLIED))} changed a listing,{" "}
            {full(sum(s.eventsLast24hByOutcome, ORDERING))} rejected by version rules
          </span>
        </div>
      </dl>
    </section>
  );
}

function AttentionShelf({ s }: { s: DashboardSummary }) {
  const pending = s.matchCandidatesByReviewStatus.PENDING ?? 0;
  const outboxFailed = s.outboxBacklog.FAILED ?? 0;
  return (
    <section className="shelf" aria-labelledby="attention-h">
      <div className="shelf-head">
        <h2 id="attention-h">Needs attention</h2>
      </div>
      <dl className="stats">
        <div className="stat">
          <dt>Matches waiting for review</dt>
          <dd>
            <Link to="/matching">{count(pending)}</Link>
          </dd>
          <span className="sub">pairs the matcher will not merge on its own</span>
        </div>
        <div className="stat">
          <dt>High-priority gaps</dt>
          <dd>
            <Link to="/gaps?priority=P1">{count(s.openGapsByPriority.P1 ?? 0)}</Link>
          </dd>
          <span className="sub">
            {full(s.openGapsByPriority.P2 ?? 0)} medium and {full(s.openGapsByPriority.P3 ?? 0)} low priority
          </span>
        </div>
        <div className="stat">
          <dt>Change events not yet published</dt>
          <dd>
            <Link to="/health">{count((s.outboxBacklog.PENDING ?? 0) + outboxFailed)}</Link>
          </dd>
          <span className="sub">
            {outboxFailed > 0 ? <StatusBadge tone="critical">{full(outboxFailed)} failed</StatusBadge> : "none failed"}
          </span>
        </div>
      </dl>
    </section>
  );
}

function CoverageShelf({ targetId, targetName, summary }: { targetId: number; targetName: string; summary: DashboardSummary | undefined }) {
  const coverage = useCoverage(targetId);
  const last = summary?.lastReconciliation;
  return (
    <section className="shelf" aria-labelledby="coverage-h">
      <QueryState
        query={coverage}
        what="coverage"
        isEmpty={(c) => c.total.referenceProducts === 0}
        empty={
          <Empty title="No reference assortment yet">
            Coverage compares {targetName} with reference suppliers. Ingest a reference catalog (a batch export or
            listing events from a REFERENCE source), then start a reconciliation under Batch jobs.
          </Empty>
        }
      >
        {(c) => {
          const t: CoverageRow = c.total;
          const gaps = t.openGaps + t.confirmedGaps;
          return (
            <>
              <div className="shelf-head" style={{ alignItems: "flex-end" }}>
                <div>
                  <h2 id="coverage-h" className="visually-hidden">
                    Coverage by category
                  </h2>
                  <p className="hero-figure">{percent(t.carried, t.referenceProducts)}</p>
                  <p style={{ marginTop: 6, color: "var(--ink-2)" }}>
                    of the reference assortment is carried: {full(t.carried)} of {full(t.referenceProducts)} products,
                    with <Link to={`/gaps?target=${targetId}&status=OPEN`}>{full(gaps)} gaps</Link> to review.
                  </p>
                </div>
                <p className="cell-sub">
                  {last?.jobId
                    ? `Last reconciliation, for ${last.targetSourceCode}: ${label(last.status).toLowerCase()} ${ago(last.finishedAt)}. `
                    : "No reconciliation has run yet. "}
                  <Link to="/jobs">Open batch jobs</Link>
                </p>
              </div>
              {t.notEvaluated > 0 ? (
                <p className="notice">
                  {full(t.notEvaluated)} reference products have not been evaluated for {targetName} yet. Start a
                  reconciliation under <Link to="/jobs">Batch jobs</Link> to compute their gaps.
                </p>
              ) : null}
              <Planogram
                rows={c.categories}
                gapsLink={(row) =>
                  `/gaps?target=${targetId}&status=OPEN&categoryRoot=${encodeURIComponent(row.category)}`
                }
              />
            </>
          );
        }}
      </QueryState>
    </section>
  );
}

export function OverviewPage() {
  const { target, marketplaces, loading, error, setTarget } = useTarget();
  const summary = useSummary();
  return (
    <>
      <PageHeader
        title="Selection coverage"
        lede={
          target
            ? `Which reference products ${target.name} carries, shelf by shelf. Amber facings are products a reference supplier lists that this marketplace does not.`
            : "Which reference products the marketplace carries, by category."
        }
      >
        <TargetPicker marketplaces={marketplaces} target={target} onChange={setTarget} />
      </PageHeader>
      {loading ? (
        <Loading what="sources" />
      ) : target ? (
        <CoverageShelf targetId={target.sourceId} targetName={target.name} summary={summary.data} />
      ) : error ? (
        <ErrorState error={error} />
      ) : (
        <Empty title="No marketplace registered">
          Register a MARKETPLACE source (POST /api/v1/sources) to see its selection coverage.
        </Empty>
      )}
      <QueryState query={summary} what="catalog summary">
        {(s) => (
          <>
            <AttentionShelf s={s} />
            <CatalogShelf s={s} />
          </>
        )}
      </QueryState>
    </>
  );
}
