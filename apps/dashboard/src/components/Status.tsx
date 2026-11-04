import type { ReactNode } from "react";
import { label } from "../lib/format";

export type Tone = "good" | "warning" | "critical" | "neutral" | "carried" | "gap" | "confirmed" | "dismissed" | "review";

/** Status colours never stand alone: every badge pairs a shape with its label. */
function Glyph({ tone }: { tone: Tone }) {
  switch (tone) {
    case "good":
      return (
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <circle cx="6" cy="6" r="6" fill="var(--good)" />
          <path d="M3.2 6.2 5.1 8l3.7-4" stroke="#fff" strokeWidth="1.6" fill="none" strokeLinecap="round" />
        </svg>
      );
    case "warning":
      return (
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M6 .8 11.4 11H.6Z" fill="var(--warning)" />
          <path d="M6 4.3v3.2M6 9.1v.2" stroke="#17212b" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      );
    case "critical":
      return (
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M3.5 0h5L12 3.5v5L8.5 12h-5L0 8.5v-5Z" fill="var(--critical)" />
          <path d="M4 4l4 4M8 4 4 8" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      );
    case "neutral":
      return (
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <circle cx="6" cy="6" r="4" fill="none" stroke="var(--ink-2)" strokeWidth="1.5" />
        </svg>
      );
    default: {
      const fill = { carried: "var(--carried)", gap: "var(--gap)", confirmed: "var(--gap)", dismissed: "var(--dismissed)", review: "var(--review)" }[tone];
      return (
        <svg width="9" height="13" viewBox="0 0 9 13" aria-hidden="true">
          <rect width="9" height="13" rx="1.5" fill={fill} />
          {tone === "confirmed" ? <rect y="9" width="9" height="4" fill="var(--ink)" /> : null}
        </svg>
      );
    }
  }
}

export function StatusBadge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className="status">
      <Glyph tone={tone} />
      <span>{children}</span>
    </span>
  );
}

const JOB_TONE: Record<string, Tone> = {
  SUCCEEDED: "good",
  PUBLISHED: "good",
  FAILED: "critical",
  RUNNING: "neutral",
  PENDING: "neutral",
  REGISTERED: "neutral",
};

export function JobStatus({ status }: { status: string }) {
  return <StatusBadge tone={JOB_TONE[status] ?? "neutral"}>{label(status)}</StatusBadge>;
}

const GAP_TONE: Record<string, Tone> = { OPEN: "gap", CONFIRMED: "confirmed", DISMISSED: "dismissed", RESOLVED: "carried" };

export function GapStatusBadge({ status }: { status: string }) {
  return <StatusBadge tone={GAP_TONE[status] ?? "neutral"}>{label(status)}</StatusBadge>;
}

const REVIEW_TONE: Record<string, Tone> = {
  PENDING: "review",
  APPROVED: "carried",
  AUTO_ACCEPTED: "carried",
  REJECTED: "dismissed",
  SUPERSEDED: "dismissed",
  NOT_APPLICABLE: "dismissed",
};

export function ReviewStatusBadge({ status }: { status: string }) {
  return <StatusBadge tone={REVIEW_TONE[status] ?? "neutral"}>{label(status)}</StatusBadge>;
}

export function Priority({ value }: { value: string }) {
  return (
    <span className="priority" data-priority={value} title={`Priority ${value.slice(1)}`}>
      {value}
    </span>
  );
}
