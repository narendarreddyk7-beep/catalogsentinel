#!/usr/bin/env python3
"""Draws the README illustrations (light and dark variants) into this directory.

Every number drawn here comes from the code or from a recorded measurement:
  * match scores and evidence: RuleBasedMatcher (rules-v4) on golden-set listings sony-wh1000x-01/02/04
    (tests/golden-matching/golden-listings.tsv);
  * the gap: GapAssessor (gap-v1) for the silver variant against a marketplace that lists only listing -02,
    one reference source, no demand signal;
  * F1 by matcher version: docs/benchmarks/matching/README.md.
Re-run after changing any of them:  python3 docs/assets/readme/make_readme_svgs.py
Standard library only.
"""
from __future__ import annotations

from html import escape
from pathlib import Path

OUT = Path(__file__).resolve().parent

SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"

# Palette validated with the dataviz validator (CVD separation, contrast) against each surface.
THEMES = {
    "light": dict(bg="#FFFFFF", panel="#F6F8FA", card="#FFFFFF", line="#D0D7DE", track="#EAEEF2",
                  ink="#1F2328", muted="#59636E", teal="#0D9488", indigo="#4F46E5", amber="#C2410C",
                  teal_soft="#E6F4F2", amber_soft="#FBEDE6", indigo_soft="#EEEDFC"),
    "dark": dict(bg="#0D1117", panel="#161B22", card="#0D1117", line="#30363D", track="#21262D",
                 ink="#E6EDF3", muted="#9198A1", teal="#0D9488", indigo="#6366F1", amber="#D97706",
                 teal_soft="#10302D", amber_soft="#3A2410", indigo_soft="#1F2147"),
}


def t(x, y, text, size=14, fill="ink", weight=400, anchor="start", family=SANS, extra=""):
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'fill="var(--{fill})" text-anchor="{anchor}" {extra}>{escape(text)}</text>')


def wrap(text, max_chars):
    lines, cur = [], ""
    for word in text.split():
        if cur and len(cur) + 1 + len(word) > max_chars:
            lines.append(cur)
            cur = word
        else:
            cur = f"{cur} {word}".strip()
    if cur:
        lines.append(cur)
    return lines


def doc(width, height, body, theme, title, desc, style_extra=""):
    c = THEMES[theme]
    tokens = ";".join(f"--{k.replace('_', '-')}:{v}" for k, v in c.items())
    # var() names use dashes; keep the Python keys with underscores mapped above.
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="t d">
<title id="t">{escape(title)}</title>
<desc id="d">{escape(desc)}</desc>
<style>svg{{{tokens}}}{style_extra}</style>
<rect x="0.5" y="0.5" width="{width - 1}" height="{height - 1}" rx="12" fill="var(--panel)" stroke="var(--line)"/>
{body}
</svg>
"""


def v(name):  # token reference with dashes
    return f"var(--{name.replace('_', '-')})"


# --------------------------------------------------------------------------------------------- hero
def hero(theme):
    w, h = 960, 300
    bx, bw, top, bottom = 572, 340, 58, 222
    widths = [2, 1, 2, 3, 1, 2, 1, 3, 2, 1, 1, 2, 3, 1, 2, 2, 1, 3, 1, 2, 1, 1, 3, 2, 1, 2, 3, 1, 1, 2, 1, 3, 2, 1, 2, 1]
    gaps = {5, 13, 20, 27, 32}
    guards = {0, 1, 17, 18, 34, 35}
    layout, x = [], 0.0
    for i, wd in enumerate(widths):  # (index, left, width) in abstract units
        bar_w = 7.0 if i in gaps else wd * 1.6
        layout.append((i, x, bar_w))
        x += bar_w + wd * 0.4 + 1.2
    scale = bw / (x - 1.2)
    parts, gap_marks = [], []
    for i, left, bar_w in layout:
        x, bar_w = bx + left * scale, bar_w * scale
        bar_bottom = bottom + 14 if i in guards else bottom
        if i in gaps:
            parts.append(f'<rect class="gap g{len(gap_marks)}" x="{x:.1f}" y="{top}" width="{bar_w:.1f}" '
                         f'height="{bar_bottom - top}" rx="1.5" fill="{v("amber_soft")}" stroke="{v("amber")}" '
                         f'stroke-width="1.6" stroke-dasharray="5 3"/>')
            gap_marks.append(x + bar_w / 2)
        else:
            parts.append(f'<rect x="{x:.1f}" y="{top}" width="{bar_w:.1f}" height="{bar_bottom - top}" fill="{v("ink")}"/>')
    x = bx + bw
    for gx in gap_marks:
        parts.append(f'<path d="M{gx - 5:.1f} {top - 14} L{gx + 5:.1f} {top - 14} L{gx:.1f} {top - 6} Z" fill="{v("amber")}"/>')
    # One sweep of the scanner, three times, then it parks out of sight. Gaps brighten as it passes.
    cycle, travel = 5.0, 0.8
    span = x - bx
    style = (f"@keyframes scan{{0%{{transform:translateX(0);opacity:0}}4%{{opacity:1}}{travel * 100:.0f}%"
             f"{{transform:translateX({span:.0f}px);opacity:1}}{travel * 100 + 6:.0f}%,100%{{transform:translateX({span:.0f}px);opacity:0}}}}"
             f".scan{{animation:scan {cycle}s linear 0.6s 3 both}}"
             "")
    for i, gx in enumerate(gap_marks):
        # A cycle-long animation whose flash sits where the scanner crosses this gap keeps every pass in sync.
        at = (gx - bx) / span * travel * 100
        style += (f"@keyframes hit{i}{{0%,{max(at - 4, 0):.1f}%,{at + 6:.1f}%,100%{{fill-opacity:1}}"
                  f"{at + 1:.1f}%{{fill-opacity:0.2}}}}.g{i}{{animation:hit{i} {cycle}s linear 0.6s 3}}")
    style += "@media (prefers-reduced-motion: reduce){.scan,.gap{animation:none}.scan{opacity:0}}"
    scanner = (f'<g class="scan"><rect x="{bx - 9}" y="{top - 22}" width="18" height="{bottom - top + 44}" '
               f'fill="{v("teal")}" opacity="0.14" rx="3"/><rect x="{bx - 1}" y="{top - 22}" width="2" '
               f'height="{bottom - top + 44}" fill="{v("teal")}"/></g>')
    digits = t(bx + bw / 2, bottom + 40, "2 613885 775352", 17, "ink", 500, "middle", MONO, 'letter-spacing="5"')
    body = "\n".join([
        t(48, 104, "CatalogSentinel", 50, "ink", 700, extra='letter-spacing="-1"'),
        t(48, 148, "Matches product listings from many sources,", 19, "muted"),
        t(48, 174, "then shows which products a marketplace is missing,", 19, "muted"),
        t(48, 200, "with the evidence for every decision.", 19, "muted"),
        f'<rect x="48" y="236" width="5" height="20" fill="{v("ink")}"/>',
        t(62, 251, "carried", 14, "muted"),
        f'<rect x="132" y="236" width="9" height="20" rx="1.5" fill="{v("amber_soft")}" stroke="{v("amber")}" stroke-width="1.6" stroke-dasharray="5 3"/>',
        t(150, 251, "missing: a selection gap", 14, "muted"),
        *parts, digits, scanner,
    ])
    return doc(w, h, body, theme, "CatalogSentinel",
               "A barcode whose bars stand for products. Most bars are solid (carried by the marketplace); five are "
               "dashed amber outlines (missing products, selection gaps). A teal scanner line sweeps across them.",
               style)


# --------------------------------------------------------------------------------------------- story
LISTINGS = [  # id suffix, role, source label, title
    ("01", "reference", "Reference catalog", "Sony WH-1000XM5 Wireless Noise Canceling Headphones, Black"),
    ("02", "marketplace", "Marketplace A (the one we check)", "SONY WH1000XM5 Wireless Industry Leading Noise Cancelling Over-Ear Headphones - Black"),
    ("03", "marketplace", "Marketplace B", "Sony WH-1000XM5 Noise Cancelling Wireles Headphones Black"),
    ("04", "reference", "Reference catalog", "Sony WH-1000XM5 Wireless Noise Canceling Headphones, Silver"),
]


def story(theme):
    w, h = 960, 612
    out = []
    cx, cw, ch, cy0, cgap = 24, 300, 92, 76, 14
    px, pw = 412, 196
    rx, rw = 660, 276
    # Column headers: the three stages really are a sequence.
    for x, n, label in ((cx, "1", "Listings arrive"), (px, "2", "Matched to products"), (rx, "3", "Gap report for Marketplace A")):
        out.append(f'<circle cx="{x + 11}" cy="38" r="11" fill="{v("ink")}"/>')
        out.append(t(x + 11, 43, n, 13, "panel", 700, "middle"))
        out.append(t(x + 30, 43, label, 16, "ink", 600))
    card_mid = []
    for i, (lid, role, source, title) in enumerate(LISTINGS):
        y = cy0 + i * (ch + cgap) + (24 if i == 3 else 0)
        stripe = "indigo" if role == "reference" else "teal"
        out.append(f'<rect x="{cx}" y="{y}" width="{cw}" height="{ch}" rx="8" fill="{v("card")}" stroke="{v("line")}"/>')
        out.append(f'<rect x="{cx}" y="{y}" width="5" height="{ch}" rx="2" fill="{v(stripe)}"/>')
        out.append(t(cx + 18, y + 24, source, 13, "muted", 600))
        out.append(t(cx + cw - 12, y + 24, f"-{lid}", 12, "muted", 400, "end", MONO))
        for j, line in enumerate(wrap(title, 36)[:3]):
            out.append(t(cx + 18, y + 47 + j * 19, line, 14, "ink"))
        card_mid.append((cx + cw, y + ch / 2))
    # Products.
    black_y, silver_y = 150, 418
    for y, colour, linked in ((black_y, "Black", "3 listings linked"), (silver_y, "Silver", "1 listing linked")):
        out.append(f'<rect x="{px}" y="{y}" width="{pw}" height="96" rx="10" fill="{v("card")}" stroke="{v("ink")}" stroke-width="1.5"/>')
        out.append(t(px + 16, y + 26, "Canonical product", 12, "muted", 600))
        out.append(t(px + 16, y + 50, "Sony WH-1000XM5", 16, "ink", 700))
        out.append(t(px + 16, y + 72, colour, 15, "ink"))
        out.append(t(px + pw - 14, y + 72, linked, 12, "muted", 400, "end"))
    links = [  # card index, target y (product centre), label, colour
        (0, black_y + 30, "reference", "indigo"),
        (1, black_y + 48, "auto 0.90", "teal"),
        (2, black_y + 66, "auto 0.97", "teal"),
        (3, silver_y + 48, "reference", "indigo"),
    ]
    for idx, ty, label, colour in links:
        sx, sy = card_mid[idx]
        mx = (sx + px) / 2
        out.append(f'<path d="M{sx} {sy:.0f} C{mx} {sy:.0f} {mx} {ty} {px} {ty}" fill="none" stroke="{v(colour)}" stroke-width="2"/>')
        lx, ly = sx + 44, sy
        out.append(f'<rect x="{lx - 34}" y="{ly - 11:.0f}" width="68" height="22" rx="11" fill="{v("panel")}" stroke="{v(colour)}"/>')
        out.append(t(lx, ly + 4.5, label, 12, "ink", 600, "middle"))
    # The rejected comparison that makes the silver a gap.
    sx, sy = card_mid[3]
    out.append(f'<path d="M{px + pw / 2} {silver_y} L{px + pw / 2} {black_y + 96}" fill="none" stroke="{v("amber")}" stroke-width="2" stroke-dasharray="6 5"/>')
    mid = (silver_y + black_y + 96) / 2
    out.append(f'<rect x="{px + 4}" y="{mid - 38}" width="{pw - 8}" height="76" rx="8" fill="{v("panel")}" stroke="{v("amber")}"/>')
    out.append(t(px + pw / 2, mid - 15, "Silver vs Black: titles 0.78 alike,", 12.5, "ink", 400, "middle"))
    out.append(t(px + pw / 2, mid + 4, "but VARIANT_CONFLICT", 12.5, "ink", 600, "middle", MONO))
    out.append(t(px + pw / 2, mid + 23, "forces NO_MATCH", 12.5, "ink", 600, "middle", MONO))
    # Report rows.
    out.append(f'<path d="M{px + pw} {black_y + 48} L{rx} {black_y + 48}" stroke="{v("teal")}" stroke-width="2"/>')
    out.append(f'<rect x="{rx}" y="{black_y + 8}" width="{rw}" height="80" rx="10" fill="{v("teal_soft")}" stroke="{v("teal")}" stroke-width="1.5"/>')
    out.append(f'<circle cx="{rx + 24}" cy="{black_y + 36}" r="10" fill="{v("teal")}"/>')
    out.append(f'<path d="M{rx + 19} {black_y + 36} l3.5 3.5 l6.5 -7" fill="none" stroke="#FFFFFF" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append(t(rx + 44, black_y + 41, "Black: carried", 15, "ink", 700))
    out.append(t(rx + 44, black_y + 66, "listing -02 matched it (score 0.90)", 13, "ink"))
    ry = silver_y - 46
    out.append(f'<path d="M{px + pw} {silver_y + 48} L{rx} {silver_y + 48}" stroke="{v("amber")}" stroke-width="2" stroke-dasharray="6 5"/>')
    out.append(f'<rect x="{rx}" y="{ry}" width="{rw}" height="188" rx="10" fill="{v("amber_soft")}" stroke="{v("amber")}" stroke-width="1.5" stroke-dasharray="6 4"/>')
    out.append(f'<circle cx="{rx + 24}" cy="{ry + 28}" r="10" fill="none" stroke="{v("amber")}" stroke-width="2"/>')
    out.append(f'<path d="M{rx + 24} {ry + 22} v7" stroke="{v("amber")}" stroke-width="2.2" stroke-linecap="round"/>')
    out.append(f'<circle cx="{rx + 24}" cy="{ry + 33.5}" r="1.4" fill="{v("amber")}"/>')
    out.append(t(rx + 44, ry + 33, "Silver: missing", 15, "ink", 700))
    rows = [("reason", "VARIANT_NOT_CARRIED", True), ("confidence", "0.90", False),
            ("priority", "80 of 100, so P1", False), ("demand", "none known, 0.50 used", False),
            ("references", "1 of 1 carries it", False)]
    for j, (k, val, mono) in enumerate(rows):
        yy = ry + 64 + j * 24
        out.append(t(rx + 20, yy, k, 13, "muted"))
        out.append(t(rx + 108, yy, val, 13, "ink", 600, "start", MONO if mono else SANS))
    out.append(t(24, h - 22, "Real listings from the hand-labeled golden set, scored by the rules-v4 matcher and the gap-v1 assessor.", 13, "muted"))
    return doc(w, h, "\n".join(out), theme, "From listings to a selection gap",
               "Four listings of Sony WH-1000XM5 headphones arrive from a reference catalog and two marketplaces. "
               "Three black listings link to one canonical product (auto-match scores 0.90 and 0.97); the silver "
               "reference listing becomes its own product because a color conflict forces no match despite 0.78 "
               "title similarity. Marketplace A carries the black product; the silver one is reported missing: "
               "reason VARIANT_NOT_CARRIED, confidence 0.90, priority 80, P1.")


# --------------------------------------------------------------------------------------------- match cards
CARDS = [
    dict(heading="vs Marketplace A listing -02",
         title="SONY WH1000XM5 Wireless Industry Leading Noise Cancelling Over-Ear Headphones - Black",
         features=[("GTIN", "rule", None), ("Model number", "35 %", 0.92), ("Title", "25 %", 0.62),
                   ("Brand", "15 %", 0.90), ("Attributes", "15 %", 1.00), ("Category", "10 %", 1.00)],
         notes={"GTIN": "not on both sides"},
         highlight={"Model number": "teal", "Brand": "teal"},
         evidence=[("MODEL_REGION_SUFFIX", "teal"), ("BRAND_IN_TITLE", "teal"), ("ATTRIBUTES_AGREE", "teal"),
                   ("IDENTIFIER_AGREEMENT", "teal")],
         verdict=("AUTO_MATCH", "score 0.90", "teal"),
         why="Model and brand agree, so a reworded title still matches."),
    dict(heading="vs Reference listing -04",
         title="Sony WH-1000XM5 Wireless Noise Canceling Headphones, Silver",
         features=[("GTIN", "rule", 0.0), ("Model number", "35 %", 1.00), ("Title", "25 %", 0.78),
                   ("Brand", "15 %", 1.00), ("Attributes", "15 %", 0.0), ("Category", "10 %", 1.00)],
         notes={},
         highlight={"GTIN": "amber", "Attributes": "amber"},
         evidence=[("GTIN_CONFLICT", "amber"), ("VARIANT_CONFLICT: black vs silver", "amber"),
                   ("MODEL_MATCH", "teal"), ("BRAND_MATCH", "teal")],
         verdict=("NO_MATCH", "score 0.45", "amber"),
         why="A more similar title, yet a different product."),
]


def match_cards(theme):
    w, h = 960, 636
    out = [t(24, 40, "Reference listing -01", 13, "muted", 600),
           t(24, 64, "Sony WH-1000XM5 Wireless Noise Canceling Headphones, Black", 17, "ink", 700)]
    cw, top = 444, 88
    for i, c in enumerate(CARDS):
        x = 24 + i * (cw + 24)
        out.append(f'<rect x="{x}" y="{top}" width="{cw}" height="{h - top - 24}" rx="10" fill="{v("card")}" stroke="{v("line")}"/>')
        out.append(t(x + 20, top + 30, c["heading"], 13, "muted", 600))
        for j, line in enumerate(wrap(c["title"], 50)[:2]):
            out.append(t(x + 20, top + 54 + j * 20, line, 14.5, "ink", 600))
        fy = top + 112
        out.append(t(x + 20, fy - 8, "feature", 12, "muted"))
        out.append(t(x + 150, fy - 8, "weight", 12, "muted"))
        track_x, track_w = x + 208, 168
        for j, (name, weight, val) in enumerate(c["features"]):
            yy = fy + 14 + j * 34
            colour = c["highlight"].get(name)
            out.append(t(x + 20, yy + 5, name, 14, "ink", 600 if colour else 400))
            out.append(t(x + 150, yy + 5, weight, 13, "muted"))
            out.append(f'<rect x="{track_x}" y="{yy - 6}" width="{track_w}" height="12" rx="4" fill="{v("track")}"/>')
            if val is None:
                out.append(t(track_x + 8, yy + 4.5, c["notes"].get(name, "n/a"), 12, "muted"))
                out.append(t(x + cw - 20, yy + 5, "-", 14, "muted", 400, "end", MONO))
                continue
            fill = v(colour) if colour else v("muted")
            if val > 0:
                out.append(f'<rect x="{track_x}" y="{yy - 6}" width="{max(track_w * val, 4):.1f}" height="12" rx="4" fill="{fill}"/>')
            else:
                out.append(f'<rect x="{track_x}" y="{yy - 6}" width="4" height="12" rx="2" fill="{fill}"/>')
            out.append(t(x + cw - 20, yy + 5, f"{val:.2f}", 14, "ink", 600 if colour else 400, "end", MONO))
        ey = fy + 14 + 6 * 34 + 8
        out.append(t(x + 20, ey, "evidence stored with the pair", 12, "muted"))
        cx_, cy_ = x + 20, ey + 12
        for code, colour in c["evidence"]:
            wd = len(code) * 7.4 + 20
            if cx_ + wd > x + cw - 16:
                cx_, cy_ = x + 20, cy_ + 32
            out.append(f'<rect x="{cx_}" y="{cy_}" width="{wd:.0f}" height="24" rx="6" fill="{v(colour + "_soft")}" stroke="{v(colour)}"/>')
            out.append(t(cx_ + 10, cy_ + 16.5, code, 12, "ink", 500, "start", MONO))
            cx_ += wd + 8
        vy = h - 24 - 92
        label, score, colour = c["verdict"]
        badge_w = len(label) * 9.2 + 28
        out.append(f'<line x1="{x + 1}" y1="{vy}" x2="{x + cw - 1}" y2="{vy}" stroke="{v("line")}"/>')
        out.append(f'<rect x="{x + 20}" y="{vy + 18}" width="{badge_w:.0f}" height="32" rx="8" fill="{v(colour + "_soft")}" '
                   f'stroke="{v(colour)}" stroke-width="1.5"/>')
        out.append(t(x + 34, vy + 39, label, 15, "ink", 700, "start", MONO))
        out.append(t(x + 20 + badge_w + 14, vy + 39, score, 15, "ink", 600))
        out.append(t(x + 20, vy + 74, c["why"], 13.5, "muted"))
    return doc(w, h, "\n".join(out), theme, "Two comparisons by the rules-v4 matcher",
               "The black reference listing compared with two others. Against a reworded marketplace listing of the "
               "same product: model 0.92, title 0.62, brand 0.90, attributes 1.00, category 1.00, no GTIN on both "
               "sides; identifier agreement gives AUTO_MATCH with score 0.90. Against the silver reference listing: "
               "model 1.00, title 0.78, brand 1.00, but GTIN 0.00 and attributes 0.00; GTIN_CONFLICT and "
               "VARIANT_CONFLICT (black vs silver) give NO_MATCH with score 0.45.")


# --------------------------------------------------------------------------------------------- results chart
VERSIONS = ["title-only\nbaseline", "rules-v1", "rules-v2", "rules-v3", "rules-v4"]
SERIES = [  # name, colour, marker, values (assisted F1), label
    ("golden dev", "teal", "circle", [0.352, 0.745, 0.824, 0.824, 0.829], "golden dev, 150 listings"),
    ("golden holdout", "indigo", "square", [0.409, 0.762, 0.880, 0.898, 0.898], "golden holdout, 48 listings"),
]


def results_chart(theme):
    w, h = 960, 440
    left, right, ptop, pbottom = 84, 936, 122, 360
    xs = [left + 54, left + 250, left + 400, left + 550, left + 700]

    def y(val):
        return pbottom - (pbottom - ptop) * val

    out = [t(24, 40, "Matching quality by matcher version", 18, "ink", 700),
           t(24, 64, "F1 when the review queue counts as found. Pairwise, all pairs, hand-labeled sets.", 13.5, "muted")]
    # Legend (two series, so always present; also direct-labelled at the right).
    lx = 24
    for name, colour, marker, _, label in SERIES:
        out.append(marker_svg(marker, lx + 6, 92, colour))
        out.append(t(lx + 18, 97, label, 13, "muted"))
        lx += 220
    for g in (0, 0.25, 0.5, 0.75, 1.0):
        out.append(f'<line x1="{left}" y1="{y(g):.1f}" x2="{right}" y2="{y(g):.1f}" stroke="{v("line")}" stroke-width="{1 if g else 1.5}"/>')
        out.append(t(left - 10, y(g) + 4.5, f"{g:.2f}", 12.5, "muted", 400, "end", MONO))
    divider = (xs[0] + xs[1]) / 2
    out.append(f'<line x1="{divider}" y1="{ptop - 6}" x2="{divider}" y2="{pbottom}" stroke="{v("line")}" stroke-dasharray="3 4"/>')
    for x, label in zip(xs, VERSIONS):
        for k, part in enumerate(label.split("\n")):
            out.append(t(x, pbottom + 24 + k * 17, part, 13, "ink", 400, "middle", MONO if part.startswith("rules") else SANS))
    for name, colour, marker, vals, label in SERIES:
        pts = " ".join(f"{xs[i]},{y(vals[i]):.1f}" for i in range(1, len(vals)))
        out.append(f'<polyline points="{pts}" fill="none" stroke="{v(colour)}" stroke-width="2" stroke-linejoin="round"/>')
        for i, val in enumerate(vals):
            out.append(marker_svg(marker, xs[i], y(val), colour, ring=True))
    # Selective direct labels: baseline values and the latest version.
    for val, dy in ((SERIES[0][3][0], 22), (SERIES[1][3][0], -14)):
        out.append(t(xs[0], y(val) + dy, f"{val:.3f}", 13, "ink", 600, "middle", MONO))
    # Series names and their latest values at the right edge.
    for (name, colour, marker, vals, label), dy in zip(SERIES, (14, -6)):
        out.append(t(xs[4] + 18, y(vals[4]) + dy, f"{name.split()[1]} {vals[4]:.3f}", 13.5, "ink", 600))
    out.append(t(24, h - 18, "rules-v3 was motivated by a holdout error, so the v3 and v4 holdout values are optimistic. "
                 "Source: docs/benchmarks/matching.", 12.5, "muted"))
    return doc(w, h, "\n".join(out), theme, "Matching quality by matcher version",
               "Assisted F1 on the golden dev set (150 listings): title-only baseline 0.352, rules-v1 0.745, v2 0.824, "
               "v3 0.824, v4 0.829. On the golden holdout set (48 listings): baseline 0.409, v1 0.762, v2 0.880, "
               "v3 0.898, v4 0.898.")


def marker_svg(kind, x, y, colour, ring=False):
    stroke = f' stroke="{v("panel")}" stroke-width="2"' if ring else ""
    if kind == "circle":
        return f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{6 if ring else 5}" fill="{v(colour)}"{stroke}/>'
    s = 11 if ring else 9
    return f'<rect x="{x - s / 2:.1f}" y="{y - s / 2:.1f}" width="{s}" height="{s}" rx="1.5" fill="{v(colour)}"{stroke}/>'


def main():
    for name, fn in (("hero", hero), ("story", story), ("match-cards", match_cards), ("matching-quality", results_chart)):
        for theme in THEMES:
            (OUT / f"{name}-{theme}.svg").write_text(fn(theme), encoding="utf-8")
    print("wrote", sorted(p.name for p in OUT.glob("*.svg")))


if __name__ == "__main__":
    main()
