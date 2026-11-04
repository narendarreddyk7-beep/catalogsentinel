import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useIngestionJob, useIngestionJobs, useReconciliationJobs, useStartReconciliation } from "../api/queries";
import type { IngestionJob } from "../api/types";
import { Meter } from "../charts/BarList";
import { LoadMore, PageHeader } from "../components/Shell";
import { Empty, ErrorState, QueryState } from "../components/States";
import { JobStatus, StatusBadge } from "../components/Status";
import { useTarget } from "../components/Target";
import { ago, duration, full, label, when } from "../lib/format";

const STAT_LABELS: Record<string, string> = {
  listingsMatched: "Listings matched first",
  identitiesMerged: "Duplicate products merged",
  productsEvaluated: "Reference products evaluated",
  productsCarried: "Carried",
  gapsOpened: "Gaps opened",
  gapsRefreshed: "Gaps refreshed",
  gapsResolved: "Gaps resolved",
  gapsSuppressedByDismissal: "Kept dismissed",
};

function shortKey(key: string) {
  const [source, hash] = key.split(":");
  return hash ? `${source}:${hash.slice(0, 8)}` : key;
}

function IngestionTable({ selectedId }: { selectedId: number | undefined }) {
  const jobs = useIngestionJobs({ limit: 20 });
  const navigate = useNavigate();
  return (
    <QueryState
      query={jobs}
      what="ingestion jobs"
      isEmpty={(d) => d.pages[0]?.items.length === 0}
      empty={
        <Empty title="No batch exports ingested yet">
          Run the PySpark job on an export manifest (jobs/catalog-batch); it registers itself here and reports progress.
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
                    <th>Export</th>
                    <th>Status</th>
                    <th className="n">Rows</th>
                    <th className="n">Rejected</th>
                    <th className="n">Events</th>
                    <th>Finished</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((j: IngestionJob) => (
                    <tr key={j.jobId} className="clickable" aria-selected={j.jobId === selectedId} onClick={() => navigate(`/jobs/ingestion/${j.jobId}`)}>
                      <td>
                        <Link className="cell-title" to={`/jobs/ingestion/${j.jobId}`} onClick={(e) => e.stopPropagation()}>
                          Job {j.jobId}
                        </Link>
                        <span className="cell-sub">{shortKey(j.jobKey)}</span>
                      </td>
                      <td>
                        <JobStatus status={j.status} />
                        {j.attempt > 1 ? <span className="cell-sub">attempt {j.attempt}</span> : null}
                      </td>
                      <td className="n">{full(j.rowsTotal)}</td>
                      <td className="n">{full(j.rowsRejected)}</td>
                      <td className="n">{full(j.eventsPublished)}</td>
                      <td>{j.finishedAt ? ago(j.finishedAt) : j.startedAt ? `running since ${when(j.startedAt)}` : "not started"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <LoadMore hasNextPage={jobs.hasNextPage} isFetchingNextPage={jobs.isFetchingNextPage} fetchNextPage={jobs.fetchNextPage} shown={rows.length} />
          </>
        );
      }}
    </QueryState>
  );
}

function IngestionPanel({ jobId }: { jobId: number }) {
  const detail = useIngestionJob(jobId);
  return (
    <QueryState query={detail} what="job">
      {({ job: j, manifest, errors, progress }) => {
        const files = Array.isArray(manifest.files) ? manifest.files.length : 0;
        return (
          <div className="detail">
            <div>
              <p className="actions">
                <JobStatus status={j.status} />
                {manifest.syntheticData === true ? <span className="synthetic-note">synthetic export</span> : null}
              </p>
              <h2 style={{ marginTop: 8 }}>
                Job {j.jobId}: {j.sourceCode}
              </h2>
              <p className="cell-sub">
                Export {String(manifest.exportId ?? "–")}, {full(files)} files, attempt {j.attempt}
              </p>
            </div>
            <section>
              <h3>Downstream processing</h3>
              <p style={{ marginTop: 6 }}>
                <span className="num">{full(progress.processedEvents)}</span> of{" "}
                <span className="num">{full(j.eventsPublished)}</span> published events processed by the worker
                {progress.fullyProcessed ? "." : progress.remainingEvents ? `; ${full(progress.remainingEvents)} still queued or dead-lettered.` : "."}
              </p>
              <div style={{ marginTop: 8, maxWidth: 420 }}>
                <Meter value={progress.processedEvents} max={j.eventsPublished ?? 0} label="Processed events" />
              </div>
              {Object.keys(progress.byOutcome).length > 0 ? (
                <table className="data" style={{ marginTop: 12, maxWidth: 420 }}>
                  <thead>
                    <tr>
                      <th>Outcome</th>
                      <th className="n">Events</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(progress.byOutcome).map(([k, v]) => (
                      <tr key={k}>
                        <td>{label(k)}</td>
                        <td className="n">{full(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : null}
            </section>
            <dl className="kv">
              <dt>Rows in export</dt>
              <dd className="num">{full(j.rowsTotal)}</dd>
              <dt>Valid / rejected</dt>
              <dd className="num">
                {full(j.rowsValid)} / {full(j.rowsRejected)}
              </dd>
              <dt>Started</dt>
              <dd>{when(j.startedAt)}</dd>
              <dt>Duration</dt>
              <dd>{duration(j.startedAt, j.finishedAt)}</dd>
              <dt>Input</dt>
              <dd>{j.inputUri ?? "–"}</dd>
              <dt>Curated output</dt>
              <dd>{j.outputUri ?? "–"}</dd>
              <dt>Rejected records</dt>
              <dd>{j.rejectedUri ?? "–"}</dd>
              <dt>Job key</dt>
              <dd>{j.jobKey}</dd>
            </dl>
            {errors.length > 0 ? (
              <section>
                <h3>Errors reported</h3>
                <ol className="timeline" style={{ marginTop: 8 }}>
                  {errors.map((e, i) => (
                    <li key={i}>
                      <StatusBadge tone="critical">{when(e.at)}</StatusBadge>
                      <span className="cell-sub">{e.message}</span>
                    </li>
                  ))}
                </ol>
              </section>
            ) : null}
          </div>
        );
      }}
    </QueryState>
  );
}

function Reconciliation() {
  const { target, marketplaces } = useTarget();
  const [code, setCode] = useState<string>("");
  const jobs = useReconciliationJobs();
  const start = useStartReconciliation();
  const chosen = code || target?.code || "";
  return (
    <>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          if (chosen) {
            start.mutate({ targetSourceCode: chosen });
          }
        }}
      >
        <label className="field">
          Marketplace
          <select value={chosen} onChange={(e) => setCode(e.target.value)}>
            {marketplaces.map((m) => (
              <option key={m.sourceId} value={m.code}>
                {m.name}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="btn btn-primary" disabled={!chosen || start.isPending}>
          Start reconciliation
        </button>
        {start.data ? (
          <p className="notice" role="status" style={{ margin: 0 }}>
            Reconciliation {start.data.jobId} {label(start.data.status).toLowerCase()} for {start.data.targetSourceCode}.
          </p>
        ) : null}
      </form>
      {start.error ? <ErrorState error={start.error} /> : null}
      <QueryState
        query={jobs}
        what="reconciliation jobs"
        isEmpty={(d) => d.pages[0]?.items.length === 0}
        empty={<Empty title="No reconciliation has run yet">Start one above to compute selection gaps.</Empty>}
      >
        {(d) => (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Run</th>
                  <th>Status</th>
                  <th>Results</th>
                  <th>Duration</th>
                </tr>
              </thead>
              <tbody>
                {d.pages
                  .flatMap((p) => p.items)
                  .map((j) => (
                    <tr key={j.jobId}>
                      <td>
                        <span className="cell-title">Run {j.jobId}</span>
                        <span className="cell-sub">
                          {j.targetSourceCode}, requested by {j.requestedBy} {ago(j.createdAt)}
                        </span>
                      </td>
                      <td>
                        <JobStatus status={j.status} />
                        {j.errorMessage ? <span className="cell-sub">{j.errorMessage}</span> : null}
                      </td>
                      <td>
                        {j.stats
                          ? Object.entries(j.stats)
                              .filter(([, v]) => v > 0)
                              .map(([k, v]) => (
                                <span key={k} className="cell-sub">
                                  {STAT_LABELS[k] ?? label(k)}: <span className="num">{full(v)}</span>
                                </span>
                              ))
                          : "–"}
                      </td>
                      <td>{duration(j.startedAt, j.finishedAt)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
    </>
  );
}

export function BatchJobsPage() {
  const { jobId } = useParams();
  const selected = jobId ? Number(jobId) : undefined;
  return (
    <>
      <PageHeader
        title="Batch jobs"
        lede="Supplier exports ingested by the batch pipeline, and reconciliation runs that recompute selection gaps."
      />
      <section className="shelf" aria-labelledby="ingest-h">
        <div className="shelf-head">
          <h2 id="ingest-h">Export ingestion</h2>
          <p>Progress counts events the worker has processed, read from the idempotency ledger.</p>
        </div>
        <div className={selected ? "master-detail" : undefined}>
          <div>
            <IngestionTable selectedId={selected} />
          </div>
          {selected ? (
            <aside aria-label="Ingestion job detail">
              <IngestionPanel jobId={selected} />
            </aside>
          ) : null}
        </div>
      </section>
      <section className="shelf" aria-labelledby="recon-h">
        <div className="shelf-head">
          <h2 id="recon-h">Reconciliation</h2>
          <p>One run per marketplace at a time; a run matches pending listings, then reassesses every reference product.</p>
        </div>
        <Reconciliation />
      </section>
    </>
  );
}
