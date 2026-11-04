# Dashboard design notes

**Audience.** Category and selection managers triaging gaps and match reviews; engineers on call checking
whether ingestion keeps up. **Primary job.** Show where the assortment has holes and whether the pipeline is
healthy, and let people act on the review queues.

## Concept: a planogram

Retail shelf plans are the vernacular of assortment work, so the console borrows their structure:

- **Shelves, not cards.** Each page is a stack of shelves: content sits on the page plane and a heavy 3px
  "shelf edge" closes each group. No boxed tiles, no drop shadows.
- **The signature element** is the Overview's coverage planogram: one row per reference category, one facing per
  N reference products, stocked from the left with carried products, then confirmed and open gaps, dismissed
  gaps and products no reconciliation has assessed. Every row links to the gap list behind it
  (`categoryRoot` filter), and its parts always add up to the reference count (`CoverageIT`).
- Everything else is deliberately quiet so that one element carries the identity.

## Tokens

| Role | Light | Dark |
|---|---|---|
| Page plane (also the chart surface) | `#f5f7f9` | `#151c24` |
| Ink / secondary / muted text | `#17212b` / `#4b5967` / `#66727e` | `#e6ecf1` / `#a9b6c2` / `#8794a1` |
| Carried (on shelf) | `#00866e` | `#13a584` |
| Gap (empty facing) | `#e3a008` | `#c28400` |
| Awaiting review | `#5146c9` | `#8077e6` |
| Throughput series: changed / no change / rejected by version rules | `#2a78d6` / `#a3adb7` / `#eb6834` | `#3987e5` / `#4f5b67` / `#d95926` |
| Status (reserved, always with an icon and a label) | good `#0ca30c`, warning `#fab219`, critical `#d03b3b` | same |

Colour means state only. The coverage set and the throughput pair were validated with the data-viz palette
checker against the page surface in each mode (lightness band, chroma floor, colour-vision-deficiency
separation, normal-vision floor). Light-mode amber (2.1:1) and orange (2.98:1) fall below 3:1 against the
surface, so both charts carry visible counts and a table view, as the relief rule requires. Muted text is
4.58:1 on the light page.

**Type.** Archivo (variable, self-hosted via `@fontsource-variable/archivo`): condensed widths (75–87.5 %) for
headings and figures, normal width for text, tabular figures only in tables and axes, proportional figures for
large numbers. Sentence case everywhere; no all-caps labels.

## Charts

Hand-rolled SVG so the marks follow one spec: columns at most 24px, 4px rounded data end and square baseline,
2px surface gaps between stacked segments, hairline grid, crosshair tooltip that lists every series, text in
text tokens (never in series colours), and a "Show as table" view under every chart.

## States and copy

Loading, empty and error states exist for every query. Errors say what failed and what to do, and show the
request's correlation id so it can be found in the API logs. Stale data stays visible, dimmed, while it
refetches. Synthetic demand is labelled wherever it appears.
