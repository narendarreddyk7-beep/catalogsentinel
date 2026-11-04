import { useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import { full } from "../lib/format";

/**
 * Stacked columns over time with a crosshair: the pointer snaps to the nearest column and one tooltip lists every
 * series at that time. Marks follow the house specs: columns at most 24px wide, 4px rounded top on the stack,
 * square at the baseline, 2px surface gap between segments, hairline grid.
 */
export interface Series {
  key: string;
  label: string;
  color: string;
}

export interface Column {
  label: string; // x label (time)
  values: Record<string, number>;
}

const HEIGHT = 200;
const PAD = { top: 10, right: 8, bottom: 26, left: 44 };

function niceMax(v: number): number {
  if (v <= 0) {
    return 1;
  }
  const exp = Math.pow(10, Math.floor(Math.log10(v)));
  const f = v / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return nice * exp;
}

/** Width of the chart's container in CSS pixels, so axis text renders at its true size instead of scaling. */
function useContainerWidth(fallback: number) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(([entry]) => {
      const w = Math.round(entry?.contentRect.width ?? 0);
      if (w > 0) {
        setWidth(w);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}

export function Columns({ columns, series, title }: { columns: Column[]; series: Series[]; title: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const { ref: boxRef, width } = useContainerWidth(720);
  const totals = columns.map((c) => series.reduce((s, x) => s + (c.values[x.key] ?? 0), 0));
  const yMax = niceMax(Math.max(0, ...totals));
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const slot = columns.length ? plotW / columns.length : plotW;
  const barW = Math.max(2, Math.min(24, slot - 2));
  const y = (v: number) => PAD.top + plotH - (v / yMax) * plotH;
  const ticks = [0, yMax / 2, yMax];
  const labelEvery = Math.max(1, Math.ceil(columns.length / 6));

  const onMove = (e: PointerEvent<SVGRectElement>) => {
    const svg = svgRef.current;
    if (!svg || columns.length === 0) {
      return;
    }
    const r = svg.getBoundingClientRect();
    const scale = width / r.width;
    const x = (e.clientX - r.left) * scale - PAD.left;
    setHover(Math.max(0, Math.min(columns.length - 1, Math.floor(x / slot))));
  };

  const stacks = useMemo(
    () =>
      columns.map((c) => {
        let acc = 0;
        const visible = series.filter((s) => (c.values[s.key] ?? 0) > 0);
        return visible.map((s, i) => {
          const v = c.values[s.key] ?? 0;
          const y0 = acc;
          acc += v;
          return { s, v, y0, y1: acc, top: i === visible.length - 1 };
        });
      }),
    [columns, series],
  );

  const hovered = hover === null ? null : columns[hover];
  const tipLeft = hover === null ? 0 : ((PAD.left + slot * (hover + 0.5)) / width) * 100;

  return (
    <div className="chart" ref={boxRef}>
      <ul className="legend" aria-label="Legend">
        {series.map((s) => (
          <li key={s.key}>
            <span className="swatch" style={{ background: s.color }} aria-hidden="true" />
            {s.label}
          </li>
        ))}
      </ul>
      <svg ref={svgRef} viewBox={`0 0 ${width} ${HEIGHT}`} width={width} height={HEIGHT} role="img" aria-label={title}>
        <g className="axis">
          {ticks.map((t) => (
            <g key={t}>
              <line className={t === 0 ? "baseline-rule" : "gridline"} x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} />
              <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end">
                {full(t)}
              </text>
            </g>
          ))}
          {columns.map((c, i) =>
            i % labelEvery === 0 ? (
              <text key={c.label + i} x={PAD.left + slot * (i + 0.5)} y={HEIGHT - 6} textAnchor="middle">
                {c.label}
              </text>
            ) : null,
          )}
        </g>
        {stacks.map((stack, i) => {
          const x = PAD.left + slot * i + (slot - barW) / 2;
          return (
            <g key={i}>
              {stack.map(({ s, y0, y1, top }) => {
                // 2px surface gap between stacked segments: each segment gives up 1px at both inner edges.
                const yTop = y(y1) + (top ? 0 : 1);
                const yBottom = y(y0) - (y0 === 0 ? 0 : 1);
                const h = Math.max(0, yBottom - yTop);
                if (h <= 0) {
                  return null;
                }
                const r = top ? Math.min(4, barW / 2, h) : 0;
                const d = top
                  ? `M${x},${yBottom} V${yTop + r} Q${x},${yTop} ${x + r},${yTop} H${x + barW - r} Q${x + barW},${yTop} ${x + barW},${yTop + r} V${yBottom} Z`
                  : `M${x},${yBottom} V${yTop} H${x + barW} V${yBottom} Z`;
                return <path key={s.key} d={d} fill={s.color} opacity={hover === null || hover === i ? 1 : 0.55} />;
              })}
            </g>
          );
        })}
        {hover !== null ? (
          <line className="crosshair" x1={PAD.left + slot * (hover + 0.5)} x2={PAD.left + slot * (hover + 0.5)} y1={PAD.top} y2={PAD.top + plotH} />
        ) : null}
        <rect
          x={PAD.left}
          y={PAD.top}
          width={plotW}
          height={plotH}
          fill="transparent"
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
        />
      </svg>
      {hovered ? (
        <div className="tooltip" style={{ left: `calc(${tipLeft}% + 12px)`, top: 24 }} role="presentation">
          <div className="tt-title">{hovered.label}</div>
          {series.map((s) => (
            <div className="tt-row" key={s.key}>
              <span className="tt-key" style={{ background: s.color }} />
              <strong>{full(hovered.values[s.key] ?? 0)}</strong>
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
                <th>Time</th>
                {series.map((s) => (
                  <th key={s.key} className="n">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {columns.map((c, i) => (
                <tr key={c.label + i}>
                  <td>{c.label}</td>
                  {series.map((s) => (
                    <td key={s.key} className="n">
                      {full(c.values[s.key] ?? 0)}
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
