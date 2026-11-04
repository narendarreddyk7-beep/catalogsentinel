import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { allocateFacings, facingUnit } from "../src/charts/Planogram";
import { renderAt } from "./render";
import { fixture, server } from "./server";

describe("Overview", () => {
  it("leads with the share of the reference assortment carried and one planogram row per category", async () => {
    renderAt("/");

    // 4,440 of 5,765 carried = 77%
    expect(await screen.findByText("77%", { selector: ".hero-figure" })).toBeInTheDocument();
    expect(screen.getByText(/4,440 of 5,765 products/)).toBeInTheDocument();
    const rows = within(screen.getByRole("list", { name: "Coverage by category" })).getAllByRole("listitem");
    expect(rows).toHaveLength(6);
    expect(rows[0]).toHaveAccessibleName(/electronics: 1,395 of 1,820 reference products carried, 302 open gaps/);
    expect(rows[0]).toHaveAttribute("href", "/gaps?target=1&status=OPEN&categoryRoot=electronics");
  });

  it("tells the operator to run a reconciliation when products have not been evaluated", async () => {
    renderAt("/");
    expect(await screen.findByText(/60 reference products have not been evaluated/)).toBeInTheDocument();
  });

  it("shows attention figures that link to the lists behind them", async () => {
    renderAt("/");
    const pending = await screen.findByRole("link", { name: "148" });
    expect(pending).toHaveAttribute("href", "/matching");
    expect(screen.getByRole("link", { name: "96" })).toHaveAttribute("href", "/gaps?priority=P1");
    expect(screen.getByText("411 medium and 582 low priority")).toBeInTheDocument();
  });

  it("explains an empty reference assortment instead of drawing an empty chart", async () => {
    const empty = fixture("GET /api/v1/dashboard/coverage");
    empty.categories = [];
    empty.total = { ...empty.total, referenceProducts: 0, carried: 0, openGaps: 0, confirmedGaps: 0, dismissedGaps: 0, notEvaluated: 0 };
    server.use(http.get("*/api/v1/dashboard/coverage", () => HttpResponse.json(empty)));
    renderAt("/");
    expect(await screen.findByText("No reference assortment yet")).toBeInTheDocument();
  });

  it("reports API failures with the correlation id to search for", async () => {
    server.use(
      http.get("*/api/v1/dashboard/summary", () =>
        HttpResponse.json({ status: 503, code: "DATABASE_UNAVAILABLE", detail: "The database is unavailable", correlationId: "corr-123" }, { status: 503 }),
      ),
    );
    renderAt("/");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Database unavailable (503)");
    expect(alert).toHaveTextContent("corr-123");
  });
});

describe("planogram facings", () => {
  const row = { category: "x", referenceProducts: 1000, carried: 960, openGaps: 3, confirmedGaps: 0, dismissedGaps: 37, notEvaluated: 0 };

  it("keeps at least one facing for every non-zero state and stays close to the total", () => {
    const unit = facingUnit([row]);
    const f = allocateFacings(row, unit);
    expect(unit).toBe(21); // ceil(1000 / 48 facings)
    expect(f.open).toBe(1);
    expect(f.confirmed).toBe(0);
    const total = Object.values(f).reduce((a, b) => a + b, 0);
    expect(Math.abs(total - Math.round(1000 / unit))).toBeLessThanOrEqual(1);
  });

  it("draws one facing per product when the assortment is small", () => {
    const small = { ...row, referenceProducts: 5, carried: 3, openGaps: 2, dismissedGaps: 0 };
    expect(facingUnit([small])).toBe(1);
    expect(allocateFacings(small, 1)).toEqual({ carried: 3, confirmed: 0, open: 2, dismissed: 0, unassessed: 0 });
  });
});
