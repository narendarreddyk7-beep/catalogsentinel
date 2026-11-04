import { useSearchParams } from "react-router";
import { useDeadLetters, useProcessing, useReplayDeadLetters } from "../api/queries";
import type { ProcessingMetrics, QueueDepth } from "../api/types";
import { BarList } from "../charts/BarList";
import { Columns, type Column, type Series } from "../charts/Columns";
import { PageHeader } from "../components/Shell";
import { Empty, ErrorState, QueryState } from "../components/States";
import { StatusBadge } from "../components/Status";
import { ago, count, full, label, millis, seconds } from "../lib/format";

const WINDOWS = [
  { minutes: 15, label: "Last 15 minutes" },
  { minutes: 60, label: "Last hour" },
  { minutes: 360, label: "Last 6 hours" },
  { minutes: 1440, label: "Last 24 hours" },
];

const SERIES: Series[] = [
  { key: "applied", label: "Changed a listing", color: "var(--series-applied)" },
  { key: "unchanged", label: "No change (same content or duplicate)", color: "var(--series-neutral)" },
  { key: "ordering", label: "Rejected by version rules", color: "var(--series-rejected)" },
];

const SERIES_OF: Record<string, string> = {
  CREATED: "applied",
  UPDATED: "applied",
  WITHDRAWN: "applied",
  REACTIVATED: "applied",
  UNCHANGED: "unchanged",
  STALE_IGNORED: "ordering",
  VERSION_CONFLICT: "ordering",
};

const hhmm = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: "UTC" });

/** Buckets per-minute outcome counts into at most ~60 columns covering the whole window (empty buckets kept). */
export function bucketThroughput(m: ProcessingMetrics): { columns: Column[]; bucketMinutes: number } {
  const bucketMinutes = m.windowMinutes <= 60 ? 1 : m.windowMinutes <= 360 ? 5 : 30;
  const end = Math.floor(new Date(m.generatedAt).getTime() / 60_000);
  const start = end - m.windowMinutes + 1;
  const n = Math.ceil(m.windowMinutes / bucketMinutes);
  const columns: Column[] = Array.from({ length: n }, (_, i) => ({
    label: hhmm.format(new Date((start + i * bucketMinutes) * 60_000)),
    values: { applied: 0, unchanged: 0, ordering: 0 },
  }));
  for (const p of m.throughput) {
    const minute = Math.floor(new Date(p.minute).getTime() / 60_000);
    const i = Math.floor((minute - start) / bucketMinutes);
    const col = columns[i];
    const series = SERIES_OF[p.outcome];
    if (col && series) {
      col.values[series] = (col.values[series] ?? 0) + p.events;
    }
  }
  return { columns, bucketMinutes };
}

function isAvailable(d: QueueDepth): d is { visible: number; inFlight: number; delayed: number } {
  return !("unavailable" in d);
}

function Queues({ m }: { m: ProcessingMetrics }) {
  const entries = Object.entries(m.queues);
  return (
    <dl className="stats">
      {entries.map(([name, depth]) => {
        const dlq = name.endsWith("-dlq");
        if (!isAvailable(depth)) {
          return (
            <div className="stat" key={name}>
              <dt>{name}</dt>
              <dd>–</dd>
              <span className="sub">
                <StatusBadge tone="warning">depth unavailable</StatusBadge>
              </span>
            </div>
          );
        }
        const total = depth.visible + depth.inFlight + depth.delayed;
        return (
          <div className="stat" key={name}>
            <dt>{name}</dt>
            <dd>{count(total)}</dd>
            <span className="sub">
              {dlq ? (
                total > 0 ? (
                  <StatusBadge tone="critical">{full(total)} dead-lettered</StatusBadge>
                ) : (
                  <StatusBadge tone="good">empty</StatusBadge>
                )
              ) : (
                `${full(depth.visible)} waiting, ${full(depth.inFlight)} in flight, ${full(depth.delayed)} delayed`
              )}
            </span>
          </div>
        );
      })}
      <div className="stat">
        <dt>Change events not yet published</dt>
        <dd>{count(m.outbox.pending)}</dd>
        <span className="sub">
          {m.outbox.failed > 0 ? (
            <StatusBadge tone="critical">{full(m.outbox.failed)} failed after retries</StatusBadge>
          ) : m.outbox.oldestPendingSeconds > 60 ? (
            <StatusBadge tone="warning">oldest waiting {seconds(m.outbox.oldestPendingSeconds)}</StatusBadge>
          ) : (
            <StatusBadge tone="good">relay keeping up</StatusBadge>
          )}
        </span>
      </div>
    </dl>
  );
}

function DeadLetterQueue({ queue }: { queue: string }) {
  const peek = useDeadLetters(queue);
  const replay = useReplayDeadLetters();
  return (
    <div>
      <h3>{queue}</h3>
      <QueryState
        query={peek}
        what="dead letters"
        isEmpty={(d) => d.messages.length === 0}
        empty={<p className="cell-sub" style={{ marginTop: 6 }}>No dead-lettered messages.</p>}
      >
        {(d) => (
          <>
            <p className="cell-sub" style={{ marginTop: 6 }}>
              A sample of messages that failed processing; they return to {d.sourceQueue} when replayed.
            </p>
            <ul className="timeline" style={{ marginTop: 8 }}>
              {d.messages.map((msg) => (
                <li key={msg.messageId}>
                  <strong>{msg.attributes.failureReason ?? "Moved by the queue's redrive policy"}</strong>
                  <span className="cell-sub">
                    Message {msg.messageId.slice(0, 8)}, received {full(msg.receiveCount)} times, sent {ago(msg.sentAt)}
                    {msg.attributes.replayCount ? `, replayed ${msg.attributes.replayCount} times before` : ""}
                  </span>
                </li>
              ))}
            </ul>
            <p className="actions" style={{ marginTop: 10 }}>
              <button
                type="button"
                className="btn"
                disabled={replay.isPending}
                onClick={() => replay.mutate({ queue, maxMessages: 100 })}
              >
                Replay to {d.sourceQueue}
              </button>
              <span className="cell-sub">Fix the cause first, or the messages come back here.</span>
            </p>
          </>
        )}
      </QueryState>
      {replay.data ? (
        <p className="notice" role="status">
          Replayed {full(replay.data.replayed)} message(s) to {replay.data.sourceQueue}
          {replay.data.failed > 0 ? `; ${full(replay.data.failed)} could not be sent` : ""}.
        </p>
      ) : null}
      {replay.error ? <ErrorState error={replay.error} /> : null}
    </div>
  );
}

export function SystemHealthPage() {
  const [params, setParams] = useSearchParams();
  const windowMinutes = Number(params.get("window")) || 60;
  const processing = useProcessing(windowMinutes);
  return (
    <>
      <PageHeader title="System health" lede="Whether ingestion is keeping up: throughput, end-to-end latency, queue backlogs and dead letters." />
      <div className="filters">
        <label className="field">
          Time range
          <select
            value={windowMinutes}
            onChange={(e) =>
              setParams((p) => {
                p.set("window", e.target.value);
                return p;
              })
            }
          >
            {WINDOWS.map((w) => (
              <option key={w.minutes} value={w.minutes}>
                {w.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <QueryState query={processing} what="processing metrics">
        {(m) => {
          const { columns, bucketMinutes } = bucketThroughput(m);
          const totalEvents = m.throughput.reduce((a, p) => a + p.events, 0);
          return (
            <>
              <section className="shelf" aria-labelledby="tp-h">
                <div className="shelf-head">
                  <h2 id="tp-h">Listing events processed per {bucketMinutes === 1 ? "minute" : `${bucketMinutes} minutes`}</h2>
                  <p>
                    {full(totalEvents)} events in the window. Duplicates and stale versions are normal with at-least-once
                    delivery; they are recorded, not applied.
                  </p>
                </div>
                {totalEvents === 0 ? (
                  <Empty title="No events processed in this window">Send listing events or run a batch export to see throughput.</Empty>
                ) : (
                  <Columns columns={columns} series={SERIES} title="Listing events processed over time, by outcome" />
                )}
              </section>
              <section className="shelf" aria-labelledby="lat-h">
                <div className="shelf-head">
                  <h2 id="lat-h">Time from acceptance to committed write (p95)</h2>
                  <p>Per ingestion path; batch events include time queued behind the rest of their export.</p>
                </div>
                {m.latencyByPath.length === 0 ? (
                  <Empty title="No latency samples in this window" />
                ) : (
                  <div className="split">
                    <BarList
                      label="95th percentile latency by ingestion path"
                      bars={m.latencyByPath.map((l) => ({ key: l.ingestionPath, label: label(l.ingestionPath), value: l.p95Ms, display: millis(l.p95Ms) }))}
                    />
                    <div className="table-wrap">
                      <table className="data">
                        <thead>
                          <tr>
                            <th>Path</th>
                            <th className="n">Events</th>
                            <th className="n">p50</th>
                            <th className="n">p95</th>
                            <th className="n">p99</th>
                            <th className="n">Max</th>
                          </tr>
                        </thead>
                        <tbody>
                          {m.latencyByPath.map((l) => (
                            <tr key={l.ingestionPath}>
                              <td>{label(l.ingestionPath)}</td>
                              <td className="n">{full(l.events)}</td>
                              <td className="n">{millis(l.p50Ms)}</td>
                              <td className="n">{millis(l.p95Ms)}</td>
                              <td className="n">{millis(l.p99Ms)}</td>
                              <td className="n">{millis(l.maxMs)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}
              </section>
              <section className="shelf" aria-labelledby="q-h">
                <div className="shelf-head">
                  <h2 id="q-h">Queues and outbox</h2>
                  <p>Read live from SQS and the outbox table, {ago(m.generatedAt)}.</p>
                </div>
                <Queues m={m} />
              </section>
              <section className="shelf" aria-labelledby="dlq-h">
                <div className="shelf-head">
                  <h2 id="dlq-h">Dead letters</h2>
                  <p>Needs the admin role when security is enabled.</p>
                </div>
                <div className="split">
                  {Object.keys(m.queues)
                    .filter((q) => q.endsWith("-dlq"))
                    .map((q) => (
                      <DeadLetterQueue key={q} queue={q} />
                    ))}
                </div>
              </section>
            </>
          );
        }}
      </QueryState>
    </>
  );
}
