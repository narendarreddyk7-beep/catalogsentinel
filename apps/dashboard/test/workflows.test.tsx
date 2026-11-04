import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { bucketThroughput } from "../src/pages/SystemHealth";
import type { ProcessingMetrics } from "../src/api/types";
import { renderAt } from "./render";
import { fixture, server } from "./server";

describe("Catalog explorer", () => {
  it("searches through the API and opens a listing with its event history", async () => {
    const seen: string[] = [];
    server.events.on("request:start", ({ request }) => {
      seen.push(request.url);
    });
    const { user } = renderAt("/catalog");
    await screen.findByText("Brightfield Nova BR-3265E 24 oz Insulated Water Bottle, Navy");
    await user.type(screen.getByRole("searchbox", { name: "Search titles" }), "water bottle");
    await user.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(seen.some((u) => u.includes("/api/v1/listings?") && u.includes("q=water+bottle"))).toBe(true));

    await user.click(screen.getByRole("link", { name: "Brightfield Nova BR-3265E 24 oz Insulated Water Bottle, Navy" }));
    const detail = await screen.findByRole("complementary", { name: "Listing detail" });
    expect(await within(detail).findByText("brightfield nova br3265e 24oz insulated water bottle navy")).toBeInTheDocument();
    expect(within(detail).getByText("synthetic")).toBeInTheDocument();
    expect(within(detail).getByRole("link", { name: "job 12" })).toHaveAttribute("href", "/jobs/ingestion/12");
    expect(within(detail).getByText("4 s")).toBeInTheDocument(); // received -> processed
  });
});

describe("Selection gaps", () => {
  it("labels demand as synthetic and requires a reason to dismiss", async () => {
    const { user } = renderAt("/gaps/501");
    const detail = await screen.findByRole("complementary", { name: "Gap detail" });
    expect(await within(detail).findByText("synthetic index, not real demand data")).toBeInTheDocument();
    const dismiss = within(detail).getByRole("button", { name: "Dismiss" });
    expect(dismiss).toBeDisabled();
    await user.type(within(detail).getByRole("textbox"), "discontinued");
    expect(dismiss).toBeEnabled();
  });

  it("confirms a gap with an idempotency key and reports the outcome", async () => {
    let posted: { body: unknown; key: string | null } | null = null;
    server.use(
      http.post("*/api/v1/gaps/501/decisions", async ({ request }) => {
        posted = { body: await request.json(), key: request.headers.get("Idempotency-Key") };
        const gap = fixture("GET /api/v1/gaps/501").gap;
        return HttpResponse.json({ result: { subjectId: 501, previousStatus: "OPEN", newStatus: "CONFIRMED", changed: true, decisionId: 9 }, subject: { ...gap, status: "CONFIRMED" } });
      }),
    );
    const { user } = renderAt("/gaps/501");
    await user.click(await screen.findByRole("button", { name: "Confirm gap" }));
    expect(await screen.findByText("Gap confirmed.")).toBeInTheDocument();
    expect(posted).not.toBeNull();
    expect(posted!.body).toEqual({ decision: "CONFIRM" });
    expect(posted!.key).toMatch(/[0-9a-f-]{36}/);
  });

  it("passes the planogram's category to the gap list", async () => {
    const seen: string[] = [];
    server.events.on("request:start", ({ request }) => {
      seen.push(request.url);
    });
    renderAt("/gaps?target=1&status=OPEN&categoryRoot=sports");
    expect(await screen.findByText("Show all categories")).toBeInTheDocument();
    await waitFor(() => expect(seen.some((u) => u.includes("/api/v1/gaps?") && u.includes("categoryRoot=sports") && u.includes("targetSourceId=1"))).toBe(true));
  });
});

describe("Product matching", () => {
  it("shows both listings side by side, the evidence, and records an approval", async () => {
    let decision: unknown = null;
    server.use(
      http.post("*/api/v1/match-candidates/88/decisions", async ({ request }) => {
        decision = await request.json();
        return HttpResponse.json({ result: { subjectId: 88, previousStatus: "PENDING", newStatus: "APPROVED", changed: true, decisionId: 3 }, subject: fixture("GET /api/v1/match-candidates/88") });
      }),
    );
    const { user } = renderAt("/matching/88");
    const detail = await screen.findByRole("complementary", { name: "Detail" });
    expect(await within(detail).findByText("Variant conflict")).toBeInTheDocument();
    expect(within(detail).getByText("ounces 18 vs 24")).toBeInTheDocument();
    expect(within(detail).getByText("Rules it out")).toBeInTheDocument(); // effect is announced, not colour-only
    await user.click(within(detail).getByRole("button", { name: "Approve match" }));
    expect(await within(detail).findByText(/Approved: both listings now share one product/)).toBeInTheDocument();
    expect(decision).toEqual({ decision: "APPROVE" });
  });
});

describe("Batch jobs", () => {
  it("shows downstream progress of an export from the idempotency ledger", async () => {
    renderAt("/jobs/ingestion/12");
    const detail = await screen.findByRole("complementary", { name: "Ingestion job detail" });
    expect(await within(detail).findByText(/232 still queued or dead-lettered/)).toBeInTheDocument();
    expect(within(detail).getByRole("meter", { name: "Processed events" })).toHaveAttribute("aria-valuenow", "4101");
    expect(within(detail).getByText("synthetic export")).toBeInTheDocument();
  });

  it("explains why a reconciliation could not start", async () => {
    server.use(
      http.post("*/api/v1/reconciliation/jobs", () =>
        HttpResponse.json({ status: 409, code: "RECONCILIATION_IN_PROGRESS", detail: "a reconciliation for acme-market is already running", correlationId: "c-9" }, { status: 409 }),
      ),
    );
    const { user } = renderAt("/jobs");
    await user.click(await screen.findByRole("button", { name: "Start reconciliation" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Reconciliation in progress (409)");
    expect(alert).toHaveTextContent("already running");
  });
});

describe("System health", () => {
  it("draws throughput with a table view and lists dead letters with their failure reason", async () => {
    renderAt("/health");
    expect(await screen.findByRole("img", { name: "Listing events processed over time, by outcome" })).toBeInTheDocument();
    expect(await screen.findByText("UNKNOWN_SOURCE: source 'late-source' is not registered")).toBeInTheDocument();
    expect(screen.getByText("3 dead-lettered")).toBeInTheDocument();
    expect(await screen.findByText("No dead-lettered messages.")).toBeInTheDocument();
  });

  it("tells a reader without the admin role why replay failed", async () => {
    server.use(
      http.post("*/api/v1/admin/dead-letters/replay", () =>
        HttpResponse.json({ status: 403, code: "FORBIDDEN", detail: "The API key lacks the role for this operation" }, { status: 403 }),
      ),
    );
    const { user } = renderAt("/health");
    await user.click(await screen.findByRole("button", { name: "Replay to catalog-listing-events" }));
    expect(await screen.findByText("This API key cannot do that")).toBeInTheDocument();
  });

  it("buckets per-minute outcomes over the whole window", () => {
    const m = fixture("GET /api/v1/metrics/processing") as ProcessingMetrics;
    const { columns, bucketMinutes } = bucketThroughput(m);
    expect(bucketMinutes).toBe(1);
    expect(columns).toHaveLength(60);
    const sum = (k: string) => columns.reduce((a, c) => a + (c.values[k] ?? 0), 0);
    const expected = (outcomes: string[]) => m.throughput.filter((p) => outcomes.includes(p.outcome)).reduce((a, p) => a + p.events, 0);
    expect(sum("applied")).toBe(expected(["CREATED"]));
    expect(sum("unchanged")).toBe(expected(["UNCHANGED"]));
    expect(sum("ordering")).toBe(expected(["STALE_IGNORED"]));
    const six = bucketThroughput({ ...m, windowMinutes: 360 });
    expect(six.bucketMinutes).toBe(5);
    expect(six.columns).toHaveLength(72);
  });
});
