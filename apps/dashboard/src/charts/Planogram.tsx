import { useMemo, useRef, useState, type PointerEvent } from "react";
import { Link } from "react-router";
import type { CoverageRow } from "../api/types";
import { full, percent } from "../lib/format";

/**
 * Selection coverage drawn as a shelf plan: one row per reference category, one facing per N reference products,
 * filled from the left with carried products, then confirmed and open gaps, dismissed gaps and products no
 * reconciliation has assessed yet. Every row links to the gaps behind it.
 */

export const STATES = [
  { key: "carried", label: "Carried by the marketplace", field: "carried" },
  { key: "confirmed", label: "Confirmed gap", field: "confirmedGaps" },
  { key: "open", label: "Open gap", field: "openGaps" },
  { key: "dismissed", label: "Dismissed gap", field: "dismissedGaps" },
  { key: "unassessed", label: "Not yet evaluated", field: "notEvaluated" },
] as const;

type StateKey = (typeof STATES)[number]["key"];
const MAX_FACINGS = 48;

/** Facings per state: largest-remainder rounding, and any non-zero state keeps at least one facing. */
export function allocateFacings(row: CoverageRow, unit: number): Record<StateKey, number> {
  const raw = STATES.map((s) => row[s.field] / unit);
  const total = Math.max(row.referenceProducts > 0 ? 1 : 0, Math.round(row.referenceProducts / unit));
  const out = raw.map(Math.floor);
  let remaining = total - out.reduce((a, b) => a + b, 0);
  const order = raw.map((v, i) => ({ i, frac: v - Math.floor(v) })).sort((a, b) => b.frac - a.frac);
  for (const { i } of order) {
    if (remaining <= 0) {
      break;
    }
    out[i] = (out[i] ?? 0) + 1;
    remaining--;
  }
  STATES.forEach((s, i) => {
    if (row[s.field] > 0 && out[i] === 0) {
      out[i] = 1;
      const largest = out.indexOf(Math.max(...out));
      if (largest !== i && (out[largest] ?? 0) > 1) {
        out[largest] = (out[largest] ?? 0) - 1;
      }
    }
  });
  return Object.fromEntries(STATES.map((s, i) => [s.key, out[i] ?? 0])) as Record<StateKey, number>;
}

export function facingUnit(rows: CoverageRow[]): number {
  const max = Math.max(0, ...rows.map((r) => r.referenceProducts));
  return Math.max(1, Math.ceil(max / MAX_FACINGS));
}

interface Tip {
  row: CoverageRow;
  x: number;
  y: number;
}

export function Planogram({ rows, gapsLink }: { rows: CoverageRow[]; gapsLink: (row: CoverageRow) => string }) {
  const unit = useMemo(() => facingUnit(rows), [rows]);
  const [tip, setTip] = useState<Tip | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const show = (row: CoverageRow, e: PointerEvent<HTMLElement> | null, el?: HTMLElement) => {
    const r = box.current?.getBoundingClientRect();
    if (!r) {
      return;
    }
    if (e) {
      setTip({ row, x: e.clientX - r.left + 14, y: e.clientY - r.top + 14 });
    } else if (el) {
      const b = el.getBoundingClientRect();
      setTip({ row, x: b.left - r.left + 180, y: b.bottom - r.top + 4 });
    }
  };

  let index = 0;
  return (
    <div className="chart" ref={box}>
      <ul className="legend" aria-label="Legend">
        {STATES.map((s) => (
          <li key={s.key}>
            <span className="facing" data-state={s.key} style={{ width: 9, height: 13 }} aria-hidden="true" />
            {s.label}
          </li>
        ))}
        <li>{unit === 1 ? "Each facing is one product" : `Each facing ≈ ${unit} products`}</li>
      </ul>
      <div className="planogram" data-reveal="true" role="list" aria-label="Coverage by category">
        <div className="pg-head" aria-hidden="true">
          <span>Category</span>
          <span className="hide-sm">Reference assortment</span>
          <span className="n">Carried</span>
          <span className="n">To review</span>
          <span className="n hide-sm">Coverage</span>
        </div>
        {rows.map((row) => {
          const facings = allocateFacings(row, unit);
          const summary = `${row.category}: ${full(row.carried)} of ${full(row.referenceProducts)} reference products carried, `
            + `${full(row.openGaps)} open gaps, ${full(row.confirmedGaps)} confirmed, ${full(row.dismissedGaps)} dismissed, `
            + `${full(row.notEvaluated)} not yet evaluated`;
          return (
            <Link
              key={row.category}
              to={gapsLink(row)}
              className="pg-row"
              role="listitem"
              aria-label={summary}
              onPointerMove={(e) => show(row, e)}
              onPointerLeave={() => setTip(null)}
              onFocus={(e) => show(row, null, e.currentTarget)}
              onBlur={() => setTip(null)}
            >
              <span className="pg-cat">{row.category}</span>
              <span className="pg-facings" aria-hidden="true">
                {STATES.flatMap((s) =>
                  Array.from({ length: facings[s.key] }, (_, i) => (
                    <span
                      key={`${s.key}-${i}`}
                      className="facing"
                      data-state={s.key}
                      style={{ ["--i" as string]: index++ }}
                    />
                  )),
                )}
              </span>
              <span className="n">{full(row.carried)}</span>
              <span className="n">{full(row.openGaps + row.confirmedGaps)}</span>
              <span className="n hide-sm">{percent(row.carried, row.referenceProducts)}</span>
            </Link>
          );
        })}
      </div>
      {tip ? (
        <div className="tooltip" style={{ left: tip.x, top: tip.y }} role="presentation">
          <div className="tt-title">{tip.row.category}</div>
          {STATES.map((s) => (
            <div className="tt-row" key={s.key}>
              <span className="facing" data-state={s.key} style={{ width: 8, height: 11 }} />
              <strong>{full(tip.row[s.field])}</strong>
              <span>{s.label}</span>
            </div>
          ))}
        </div>
      ) : null}
      <details className="table-view">
        <summary>Show as table</summary>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>Category</th>
                <th className="n">Reference products</th>
                {STATES.map((s) => (
                  <th key={s.key} className="n">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.category}>
                  <td>{r.category}</td>
                  <td className="n">{full(r.referenceProducts)}</td>
                  {STATES.map((s) => (
                    <td key={s.key} className="n">
                      {full(r[s.field])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
