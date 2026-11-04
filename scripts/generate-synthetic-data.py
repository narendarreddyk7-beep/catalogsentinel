#!/usr/bin/env python3
"""Generates a SYNTHETIC multi-source catalog for CatalogSentinel demos, batch tests and benchmarks.

Everything produced here is invented: brands, models, titles, GTINs and demand figures. Nothing is derived from
any real retailer's catalog, sales, demand or inventory data.

* Brands are invented names. GTINs use the GS1 restricted-circulation prefix 2 (never assigned to trade items)
  with valid check digits.
* ``synthetic_demand_index`` (0..1, long-tailed) is attached to reference listings so gap prioritisation has a
  demand input. It is random, labeled synthetic in the attribute name, in every manifest (``syntheticData``)
  and in the generated README, and must never be presented as real demand.

Output (``--out``)::

    northwind-supply/  contoso-wholesale/  acme-market/   batch exports: manifest.json + listings-*.jsonl
    acme-market-delta/                                  optional second export: updates and withdrawals
    truth/listings.tsv     every listing -> hidden product identity and the perturbations applied
    truth/gaps.tsv         products carried by a reference source but not by the marketplace (true gaps)
    truth/labeled-sample.tsv  a family-complete sample in the golden-set format for EvaluationCli
    summary.json, README.md

The marketplace side is deliberately noisy (title rewrites, unit formats, brand aliases, missing fields,
seller duplicates, a few wrong GTINs and invalid records), because those are what the matcher and the batch
validator must cope with. Matching quality measured on this data reflects robustness to *these* perturbations,
chosen by the same team that wrote the matcher; it is not an estimate of real-world accuracy.

Usage: python3 scripts/generate-synthetic-data.py --size 10k --out build/synthetic-10k [--seed 7] [--with-delta]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from pathlib import Path

GENERATOR_VERSION = "synthgen-v1"
SIZES = {"2k": 2_000, "10k": 10_000, "100k": 100_000, "1m": 1_000_000}  # 2k: quick test runs
FILE_RECORDS = 50_000
EXPORTED_AT = "2026-11-01T00:00:00Z"
DELTA_EXPORTED_AT = "2026-11-02T00:00:00Z"
TARGET = "acme-market"
REFERENCES = ["northwind-supply", "contoso-wholesale"]

BRANDS = ["Altora", "Brightfield", "Corvane", "Dunmore", "Elsworth", "Falkner", "Glenrock", "Harrowgate",
          "Ironvale", "Juniper Lane", "Kestrelline", "Larkspur", "Mosswood", "Nettleton", "Oakhurst", "Pellham",
          "Quillon", "Ravenna", "Stellan", "Thornbury", "Umbrell", "Vantor", "Westbrook", "Yarrowby", "Zephyrine",
          "Ashcombe", "Brackwell", "Cindral", "Dravo", "Emberline", "Foxmere", "Galeth", "Holloway Home",
          "Isterline", "Jorvik Tools", "Kalmia", "Lumenor", "Merrow", "Northvale", "Orrin"]
LEGAL_SUFFIXES = ["Inc.", "LLC", "Ltd.", "Corporation", "Co.", "GmbH", "Group", "Brands", "International"]
LINES = ["Pro", "Max", "Air", "Core", "Prime", "Studio", "Classic", "Edge", "Flex", "Nova", "Terra", "Aero"]
COLORS = ["Black", "White", "Silver", "Blue", "Red", "Gray", "Green", "Navy"]
MARKETING = ["New", "Official", "Genuine", "Premium", "Best Seller", "Free Shipping", "Fast Delivery"]

# category path, product nouns, variant axis, marketplace category label
TAXONOMY = [
    ("Electronics > Audio > Headphones", ["Wireless Headphones", "Over-Ear Headphones", "Earbuds"], "storage_none", "Headphones"),
    ("Electronics > Storage > Flash Drives", ["USB Flash Drive", "Thumb Drive"], "storage", "Computer Accessories"),
    ("Electronics > Displays > Monitors", ["LED Monitor", "Gaming Monitor"], "screen", "Monitors"),
    ("Home > Kitchen > Cookware", ["Nonstick Frying Pan", "Skillet"], "pan", "Kitchen"),
    ("Home > Kitchen > Small Appliances", ["Electric Kettle", "Coffee Maker"], "volume", "Appliances"),
    ("Tools > Power Tools > Drills", ["Cordless Drill", "Impact Driver"], "volt", "Power Tools"),
    ("Sports > Outdoor > Water Bottles", ["Insulated Water Bottle", "Travel Tumbler"], "floz", "Outdoor"),
    ("Home > Storage > Containers", ["Food Storage Containers", "Storage Bins"], "pack", "Home Storage"),
    ("Office > Supplies > Batteries", ["Alkaline Batteries"], "battery", "Batteries"),
    ("Toys > Building > Construction Sets", ["Building Bricks Set", "Construction Kit"], "pieces", "Toys"),
]
VARIANTS = {
    "storage_none": [("", {})],
    "storage": [("32GB", {"capacity": "32 GB"}), ("64GB", {"capacity": "64 GB"}), ("128GB", {"capacity": "128 GB"}),
                ("256GB", {"capacity": "256 GB"})],
    "screen": [('24"', {"screen_size": "24 in"}), ('27"', {"screen_size": "27 in"}), ('32"', {"screen_size": "32 in"})],
    "pan": [("8 Inch", {"size": "8 in"}), ("10 Inch", {"size": "10 in"}), ("12 Inch", {"size": "12 in"})],
    "volume": [("1.0 L", {"capacity": "1.0 L"}), ("1.7 L", {"capacity": "1.7 L"})],
    "volt": [("12V", {"voltage": "12 V"}), ("20V", {"voltage": "20 V"})],
    "floz": [("18 oz", {"capacity": "18 oz"}), ("24 oz", {"capacity": "24 oz"}), ("32 oz", {"capacity": "32 oz"})],
    "pack": [("Set of 4", {"pack": "4"}), ("Set of 8", {"pack": "8"}), ("Set of 12", {"pack": "12"})],
    "battery": [("AA 16 Count", {"size": "AA", "count": "16"}), ("AAA 16 Count", {"size": "AAA", "count": "16"}),
                ("AA 24 Count", {"size": "AA", "count": "24"})],
    "pieces": [("500 Pieces", {"pieces": "500"}), ("1000 Pieces", {"pieces": "1000"})],
}
UNIT_REWRITES = [("GB", " GB"), ("GB", "-Gigabyte"), (" Inch", '"'), (" Inch", "-in"), (" L", " Liter"),
                 ("V", " Volt"), (" oz", " Ounce"), ("Set of ", "") , (" Count", "-Pack")]


def gtin13(rng_key: str) -> str:
    body = "2" + str(int(hashlib.sha256(rng_key.encode()).hexdigest(), 16))[:11]
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body))
    return body + str((10 - total % 10) % 10)


class Generator:
    def __init__(self, size: int, seed: int):
        self.size = size
        self.rng = random.Random(seed)
        self.seed = seed
        self.products: list[dict] = []
        self.listings: dict[str, list[dict]] = {s: [] for s in [*REFERENCES, TARGET]}
        self.truth: list[tuple] = []
        self.counters = {s: 0 for s in self.listings}

    # ------------------------------------------------------------------ product universe
    def build_products(self) -> None:
        # ~1.97 listings per product on average (reference coverage, marketplace coverage, seller duplicates)
        target_products = max(50, round(self.size / 1.97))
        family = 0
        weights = [1 / (i + 1) ** 0.8 for i in range(len(BRANDS))]  # long-tailed brand popularity
        while len(self.products) < target_products:
            family += 1
            category, nouns, axis, mkt_label = self.rng.choice(TAXONOMY)
            brand = self.rng.choices(BRANDS, weights)[0]
            prefix = "".join(w[0] for w in brand.split()).upper() + brand.split()[0][1].upper()
            model_base = f"{prefix}-{self.rng.randint(1000, 9899)}{self.rng.choice(['', 'X', 'S', 'E'])}"
            line = self.rng.choice(LINES)
            noun = self.rng.choice(nouns)
            variants = VARIANTS[axis]
            n_variants = self.rng.choice([1, 1, 2, 2, 3, 4])
            colors = self.rng.sample(COLORS, k=min(len(COLORS), max(1, n_variants // max(1, len(variants)) + 1)))
            combos = [(v, c) for v in variants for c in colors]
            self.rng.shuffle(combos)
            for (variant_text, variant_attrs), color in combos[:n_variants]:
                key = f"p{len(self.products) + 1:07d}"
                self.products.append({
                    "key": key, "family": f"f{family:06d}", "brand": brand, "line": line, "noun": noun,
                    "model": model_base, "variant": variant_text, "variant_attrs": variant_attrs, "color": color,
                    "category": category, "mkt_category": mkt_label, "gtin": gtin13(f"{self.seed}:{key}"),
                    "demand": round(min(1.0, self.rng.betavariate(0.7, 3.0)), 4),
                })
                if len(self.products) >= target_products:
                    break

    def canonical_title(self, p: dict) -> str:
        parts = [p["brand"], p["line"], p["model"], p["variant"], p["noun"]]
        return " ".join(x for x in parts if x) + f", {p['color']}"

    # ------------------------------------------------------------------ listings
    def next_id(self, source: str) -> str:
        self.counters[source] += 1
        prefix = {"northwind-supply": "NW", "contoso-wholesale": "CW", "acme-market": "AM"}[source]
        return f"{prefix}-{self.counters[source]:08d}"

    def reference_listing(self, source: str, p: dict) -> dict:
        tags = []
        brand = p["brand"]
        if self.rng.random() < 0.3:
            brand = f"{p['brand']} {self.rng.choice(LEGAL_SUFFIXES)}"
            tags.append("legal_brand")
        attributes = {"color": p["color"], **p["variant_attrs"], "synthetic_demand_index": f"{p['demand']:.4f}"}
        record = {"externalProductId": self.next_id(source), "sourceVersion": 1, "title": self.canonical_title(p),
                  "brand": brand, "model": p["model"], "category": p["category"], "attributes": attributes}
        if self.rng.random() < 0.9:
            record["gtin"] = p["gtin"]
        else:
            tags.append("no_gtin")
        return self.add(source, record, p, tags)

    def marketplace_listing(self, p: dict, duplicate: bool = False) -> dict:
        rng, tags = self.rng, (["seller_duplicate"] if duplicate else [])
        title = self.canonical_title(p)
        if rng.random() < 0.35:
            title = title.replace(f" {p['model']}", "")
            tags.append("model_dropped")
        if p["variant"] and rng.random() < 0.4:
            for old, new in rng.sample(UNIT_REWRITES, len(UNIT_REWRITES)):
                if old in title:
                    title = title.replace(old, new, 1)
                    tags.append("unit_format")
                    break
        if rng.random() < 0.25:
            title = f"{p['color']} " + title.replace(f", {p['color']}", "")
            tags.append("reordered")
        if rng.random() < 0.2:
            title = f"{rng.choice(MARKETING)} {title}" if rng.random() < 0.5 else f"{title} - {rng.choice(MARKETING)}"
            tags.append("marketing")
        if rng.random() < 0.1:
            title = title.upper() if rng.random() < 0.5 else title.lower()
            tags.append("case")
        if rng.random() < 0.03:
            title = typo(rng, title)
            tags.append("typo")
        brand = p["brand"]
        r = rng.random()
        if r < 0.15:
            brand = ""
            tags.append("no_brand")
        elif r < 0.30:
            brand = f"{p['brand'].upper()} {rng.choice(['Official', 'Electronics', 'Store', 'USA'])}"
            tags.append("brand_variant")
        model = p["model"]
        r = rng.random()
        if r < 0.3:
            model = ""
            tags.append("no_model")
        elif r < 0.5:
            model = rng.choice([model.replace("-", ""), model.lower(), model.replace("-", " "), model + "/US"])
            tags.append("model_format")
        record = {"externalProductId": self.next_id(TARGET), "sourceVersion": 1, "title": title, "brand": brand,
                  "model": model, "category": p["mkt_category"] if rng.random() < 0.7 else p["category"],
                  "attributes": {"color": p["color"]} if rng.random() < 0.6 else {}}
        r = rng.random()
        if r < 0.55:
            gtin = p["gtin"]
            if rng.random() < 0.2:
                gtin = "0" + gtin if rng.random() < 0.5 else f"{gtin[:1]} {gtin[1:7]} {gtin[7:]}"
                tags.append("gtin_format")
            record["gtin"] = gtin
        elif r < 0.56:
            siblings = [q for q in self.family_of(p) if q["key"] != p["key"]]
            if siblings:
                record["gtin"] = rng.choice(siblings)["gtin"]
                tags.append("wrong_variant_gtin")
        if rng.random() < 0.003:
            record, tag = make_invalid(rng, record)
            tags.append(tag)
        return self.add(TARGET, record, p, tags)

    def family_of(self, p: dict) -> list[dict]:
        return self.families[p["family"]]

    def add(self, source: str, record: dict, p: dict, tags: list[str]) -> dict:
        self.listings[source].append(record)
        self.truth.append((source, record["externalProductId"], p["key"], p["family"], ",".join(tags)))
        return record

    def build_listings(self) -> None:
        self.families: dict[str, list[dict]] = {}
        for p in self.products:
            self.families.setdefault(p["family"], []).append(p)
        self.carried_by_reference: dict[str, list[str]] = {}
        self.carried_by_target: set[str] = set()
        for p in self.products:
            refs = []
            if self.rng.random() < 0.85:
                refs.append("northwind-supply")
            if self.rng.random() < 0.35:
                refs.append("contoso-wholesale")
            for source in refs:
                self.reference_listing(source, p)
            self.carried_by_reference[p["key"]] = refs
            # The marketplace carries most referenced products and some exclusives of its own.
            if (refs and self.rng.random() < 0.75) or (not refs and self.rng.random() < 0.6):
                self.carried_by_target.add(p["key"])
                self.marketplace_listing(p)
                if self.rng.random() < 0.04:
                    self.marketplace_listing(p, duplicate=True)

    # ------------------------------------------------------------------ output
    def write(self, out: Path, with_delta: bool) -> dict:
        out.mkdir(parents=True, exist_ok=True)
        exports = {}
        for source, records in self.listings.items():
            exports[source] = write_export(out / source, source, records, f"synthetic-{self.seed}-{source}-full",
                                           EXPORTED_AT)
        if with_delta:
            delta = []
            for record in self.listings[TARGET]:
                r = self.rng.random()
                if r < 0.05 and "title" in record and record.get("title"):
                    delta.append({**record, "sourceVersion": 2, "title": record["title"] + " (Updated)",
                                  "updatedAt": "2026-11-01T12:00:00Z"})
                elif r < 0.055:
                    delta.append({"externalProductId": record["externalProductId"], "sourceVersion": 2,
                                  "eventType": "LISTING_WITHDRAWN", "updatedAt": "2026-11-01T12:00:00Z"})
            exports[f"{TARGET}-delta"] = write_export(out / f"{TARGET}-delta", TARGET, delta,
                                                      f"synthetic-{self.seed}-{TARGET}-delta", DELTA_EXPORTED_AT)
        truth = out / "truth"
        truth.mkdir(exist_ok=True)
        with open(truth / "listings.tsv", "w", encoding="utf-8") as f:
            f.write("source_code\texternal_product_id\tproduct_key\tfamily_key\tperturbations\n")
            for row in self.truth:
                f.write("\t".join(row) + "\n")
        gaps = [(p, refs) for p in self.products for refs in [self.carried_by_reference[p["key"]]]
                if refs and p["key"] not in self.carried_by_target]
        with open(truth / "gaps.tsv", "w", encoding="utf-8") as f:
            f.write("target_source\tproduct_key\treference_sources\tsynthetic_demand_index\n")
            for p, refs in gaps:
                f.write(f"{TARGET}\t{p['key']}\t{','.join(refs)}\t{p['demand']:.4f}\n")
        sample = self.write_labeled_sample(truth / "labeled-sample.tsv")
        summary = {
            "generator": GENERATOR_VERSION, "seed": self.seed, "requestedListings": self.size,
            "products": len(self.products), "families": len(self.families),
            "listings": {s: len(r) for s, r in self.listings.items()},
            "totalListings": sum(len(r) for r in self.listings.values()),
            "trueGaps": len(gaps), "labeledSampleListings": sample, "exports": exports,
            "syntheticData": True,
        }
        (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        (out / "README.md").write_text(README.format(**summary, size=self.size), encoding="utf-8")
        return summary

    def write_labeled_sample(self, path: Path, max_listings: int = 600) -> int:
        """Whole families (positives and hard negatives together), golden-set TSV format."""
        by_product: dict[str, list[tuple[str, dict]]] = {}
        for source, records in self.listings.items():
            for r in records:
                by_product.setdefault(self._product_of[source, r["externalProductId"]], []).append((source, r))
        families = sorted(self.families)
        self.rng.shuffle(families)
        rows, label = [], {"northwind-supply": "ref", "contoso-wholesale": "ref2", TARGET: "mkt"}
        for fam in families:
            fam_rows = [(s, r, p) for p in self.families[fam] for s, r in by_product.get(p["key"], [])]
            if len(rows) + len(fam_rows) > max_listings:
                continue
            rows.extend(fam_rows)
            if len(rows) >= max_listings * 0.95:
                break
        with open(path, "w", encoding="utf-8") as f:
            f.write("id\tsource\ttruth_key\tfamily\ttitle\tbrand\tmodel\tcategory\tgtin\tattributes\n")
            for source, r, p in rows:
                attrs = ";".join(f"{k}={v}" for k, v in sorted(r.get("attributes", {}).items()))
                f.write("\t".join([f"{source}:{r['externalProductId']}", label[source], p["key"], p["family"],
                                   r.get("title") or "", r.get("brand") or "", r.get("model") or "",
                                   r.get("category") or "", r.get("gtin") or "", attrs]) + "\n")
        return len(rows)

    @property
    def _product_of(self) -> dict[tuple[str, str], str]:
        if not hasattr(self, "_product_index"):
            self._product_index = {(s, eid): key for s, eid, key, _, _ in self.truth}
        return self._product_index


def typo(rng: random.Random, title: str) -> str:
    words = title.split(" ")
    candidates = [i for i, w in enumerate(words) if len(w) > 4 and w.isalpha()]
    if not candidates:
        return title
    i = rng.choice(candidates)
    w = words[i]
    j = rng.randrange(1, len(w) - 2)
    words[i] = w[:j] + w[j + 1] + w[j] + w[j + 2:]
    return " ".join(words)


def make_invalid(rng: random.Random, record: dict) -> tuple[dict, str]:
    kind = rng.choice(["invalid_no_title", "invalid_gtin", "invalid_long_title", "invalid_negative_version"])
    record = dict(record)
    if kind == "invalid_no_title":
        record["title"] = " "
    elif kind == "invalid_gtin":
        record["gtin"] = record.get("gtin", "2000000000000")[:-1] + "x"
    elif kind == "invalid_long_title":
        record["title"] = (record["title"] + " ") * 40
    else:
        record["sourceVersion"] = -1
    return record, kind


def write_export(directory: Path, source: str, records: list[dict], export_id: str, exported_at: str) -> dict:
    directory.mkdir(parents=True, exist_ok=True)
    entries = []
    for n, start in enumerate(range(0, max(1, len(records)), FILE_RECORDS), start=1):
        chunk = records[start:start + FILE_RECORDS]
        text = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in chunk)
        name = f"listings-{n:05d}.jsonl"
        (directory / name).write_text(text, encoding="utf-8")
        entries.append({"path": name, "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                        "records": len(chunk)})
    manifest = {"manifestVersion": 1, "sourceCode": source, "exportId": export_id, "exportedAt": exported_at,
                "format": "jsonl", "syntheticData": True, "files": entries,
                "description": f"SYNTHETIC data generated by {GENERATOR_VERSION}; not real catalog or demand data"}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return {"manifest": str(directory / "manifest.json"), "records": len(records), "files": len(entries)}


README = """# Synthetic CatalogSentinel dataset ({generator}, seed {seed})

**Synthetic data.** Brands, products, GTINs (GS1 restricted prefix 2) and `synthetic_demand_index` values are
invented by `scripts/generate-synthetic-data.py`. They do not describe any real catalog, sales, demand or
inventory.

| | |
|---|---|
| requested listings | {size} |
| products / families | {products} / {families} |
| listings per source | {listings} |
| true gaps (reference-carried, not on acme-market) | {trueGaps} |
| labeled sample for EvaluationCli | {labeledSampleListings} listings |

Truth files: `truth/listings.tsv` (listing -> product, perturbations), `truth/gaps.tsv`,
`truth/labeled-sample.tsv`. Matching quality measured on this data reflects robustness to the perturbations this
generator applies (chosen by the matcher's authors), not real-world accuracy.
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--size", choices=sorted(SIZES), default="10k")
    parser.add_argument("--out", required=True)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--with-delta", action="store_true", help="also write an acme-market delta export")
    args = parser.parse_args(argv)
    started = time.perf_counter()
    gen = Generator(SIZES[args.size], args.seed)
    gen.build_products()
    gen.build_listings()
    summary = gen.write(Path(args.out), args.with_delta)
    summary["elapsedSeconds"] = round(time.perf_counter() - started, 2)
    print(json.dumps({k: summary[k] for k in ("products", "listings", "totalListings", "trueGaps",
                                              "labeledSampleListings", "elapsedSeconds")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
