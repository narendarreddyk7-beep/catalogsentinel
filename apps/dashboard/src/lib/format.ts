const integer = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
const compact = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 });

/** 1,284 in full below 10,000, then 12.9K / 4.2M. */
export function count(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) {
    return "–";
  }
  return Math.abs(n) < 10_000 ? integer.format(n) : compact.format(n);
}

export function full(n: number | null | undefined): string {
  return n === null || n === undefined ? "–" : integer.format(n);
}

export function percent(part: number, whole: number): string {
  if (!whole) {
    return "–";
  }
  const p = (100 * part) / whole;
  return `${p >= 99.5 || p < 10 ? p.toFixed(1) : Math.round(p)}%`;
}

export function score(n: number | null | undefined): string {
  return n === null || n === undefined ? "–" : n.toFixed(2);
}

export function millis(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) {
    return "–";
  }
  if (ms < 1) {
    return "<1 ms";
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  if (ms < 60_000) {
    return `${trimZero((ms / 1000).toFixed(ms < 10_000 ? 1 : 0))} s`;
  }
  return `${trimZero((ms / 60_000).toFixed(1))} min`;
}

function trimZero(s: string): string {
  return s.endsWith(".0") ? s.slice(0, -2) : s;
}

export function seconds(s: number | null | undefined): string {
  return s === null || s === undefined ? "–" : millis(s * 1000);
}

const dateTime = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "UTC",
});

export function when(iso: string | null | undefined): string {
  if (!iso) {
    return "–";
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : `${dateTime.format(d)} UTC`;
}

export function ago(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return "never";
  }
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) {
    return iso;
  }
  const s = Math.round((now - t) / 1000);
  if (s < 45) {
    return "just now";
  }
  const m = Math.round(s / 60);
  if (m < 60) {
    return `${m} min ago`;
  }
  const h = Math.round(m / 60);
  if (h < 36) {
    return `${h} h ago`;
  }
  return `${Math.round(h / 24)} days ago`;
}

export function duration(startIso: string | null | undefined, endIso: string | null | undefined): string {
  if (!startIso || !endIso) {
    return "–";
  }
  return millis(new Date(endIso).getTime() - new Date(startIso).getTime());
}

const ACRONYMS: Record<string, string> = { gtin: "GTIN", api: "API", sqs: "SQS", dlq: "DLQ", id: "ID", ids: "IDs" };
const PHRASES: Record<string, string> = {
  BRAND_MODEL: "Brand and model",
  TITLE_TRIGRAM: "Title similarity",
  SYNC_API: "Direct API",
  ASYNC_QUEUE: "Queue",
};

/** UPPER_SNAKE enum values in sentence case: VARIANT_NOT_CARRIED -> "Variant not carried", GTIN_EXACT -> "GTIN exact". */
export function label(code: string | null | undefined): string {
  if (!code) {
    return "–";
  }
  const phrase = PHRASES[code];
  if (phrase) {
    return phrase;
  }
  const words = code.toLowerCase().split("_").map((w) => ACRONYMS[w] ?? w);
  const first = words[0] ?? "";
  words[0] = first === first.toUpperCase() ? first : first.charAt(0).toUpperCase() + first.slice(1);
  return words.join(" ");
}
