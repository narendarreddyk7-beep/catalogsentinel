/**
 * Ranked horizontal bars on one baseline (single series, so no legend: the heading names it). Bars are 12px,
 * square at the baseline with a 4px rounded data end; the value sits at the bar tip in text ink.
 */
export interface Bar {
  key: string;
  label: string;
  value: number;
  display: string;
  hint?: string;
}

export function BarList({ bars, color = "var(--series-applied)", label, max }: { bars: Bar[]; color?: string; label: string; max?: number }) {
  const top = max ?? Math.max(0, ...bars.map((b) => b.value));
  return (
    <div className="chart">
      <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 10 }} aria-label={label}>
        {bars.map((b) => {
          const pct = top > 0 ? Math.max(b.value > 0 ? 0.6 : 0, (100 * b.value) / top) : 0;
          return (
            <li
              key={b.key}
              style={{ display: "grid", gridTemplateColumns: "minmax(110px, 180px) minmax(0, 1fr)", gap: 12, alignItems: "center" }}
              title={b.hint}
            >
              <span style={{ fontSize: 14 }}>{b.label}</span>
              <span style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
                <span
                  aria-hidden="true"
                  style={{ display: "block", height: 12, width: `${pct}%`, maxWidth: "calc(100% - 72px)", background: color, borderRadius: "0 4px 4px 0" }}
                />
                <span className="num" style={{ fontSize: 13, whiteSpace: "nowrap" }}>
                  {b.display}
                </span>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function Meter({ value, max, tone, label }: { value: number; max: number; tone?: "review" | "neutral"; label: string }) {
  const pct = max > 0 ? Math.min(100, (100 * value) / max) : 0;
  return (
    <span
      className="meter"
      data-tone={tone}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
    >
      <span style={{ width: `${pct}%` }} />
    </span>
  );
}
