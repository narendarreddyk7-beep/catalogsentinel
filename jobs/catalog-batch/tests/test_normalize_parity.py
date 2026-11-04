"""The Python port of the normalization text rules must produce byte-identical output to the Java normalizers."""
from __future__ import annotations

from pathlib import Path

import pytest

from catalog_batch import normalize as n

REPO = Path(__file__).resolve().parents[3]
HAND_WRITTEN = REPO / "tests" / "fixtures" / "normalization-cases.tsv"
GOLDEN = REPO / "tests" / "fixtures" / "normalization-parity-golden.tsv"
GOLDEN_CATALOGS = [REPO / "tests" / "golden-matching" / "golden-listings.tsv",
                   REPO / "tests" / "golden-matching" / "golden-holdout.tsv"]

FUNCTIONS = {
    "title": n.normalize_title,
    "brand": n.normalize_brand,
    "model": n.normalize_model,
    "category": n.normalize_category,
    "gtin": n.parse_gtin,
}


def _rows(path: Path):
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        kind, value, expected = line.split("\t")
        yield kind, value, None if expected == "<null>" else expected


def _cases(path: Path, kinds):
    return [pytest.param(k, v, e, id=f"{k}:{v[:40]}") for k, v, e in _rows(path) if k in kinds]


@pytest.mark.parametrize("kind,value,expected", _cases(HAND_WRITTEN, FUNCTIONS))
def test_hand_written_cases(kind, value, expected):
    assert FUNCTIONS[kind](None if value == "<null>" else value) == expected


@pytest.mark.parametrize("kind,value,expected", _cases(GOLDEN, FUNCTIONS))
def test_java_output_for_golden_catalog_fields(kind, value, expected):
    assert FUNCTIONS[kind](value) == expected


def _golden_listings():
    for path in GOLDEN_CATALOGS:
        lines = path.read_text(encoding="utf-8").splitlines()
        for line in lines[1:]:
            if line.strip() and not line.startswith("#"):
                yield line.split("\t")


def _attributes(cell: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in cell.split(";") if cell.strip() else []:
        eq = pair.find("=")
        if eq > 0:
            out[n.java_strip(pair[:eq])] = n.java_strip(pair[eq + 1:])
    return out


def test_content_hashes_match_java():
    expected = {value: hash_ for kind, value, hash_ in _rows(GOLDEN) if kind == "content_hash"}
    checked = 0
    for c in _golden_listings():
        listing_id, title, brand, model, category, gtin, attrs = c[0], c[4], c[5], c[6], c[7], c[8], c[9]

        def clean(s):
            s = n.java_strip(s) if s is not None else None
            return s or None

        attributes = {k: v for k, v in ((clean(k), clean(v)) for k, v in _attributes(attrs).items()) if k and v}
        got = n.content_hash(clean(title), clean(brand), clean(model), clean(category),
                             n.parse_gtin(clean(gtin)) if clean(gtin) else None, attributes)
        assert got == expected[listing_id], listing_id
        checked += 1
    assert checked == len(expected) > 190


def test_fold_and_helpers_follow_java_string_semantics():
    assert n.fold("Crème Brûlée ’Deluxe’ & Co + 2×") == "creme brulee deluxe  and  co  plus  2x"
    assert n.java_length("a\U0001F600") == 3  # surrogate pair counts as two UTF-16 units
    assert n.java_strip(" x ") == " x "  # NBSP is not Java whitespace
    assert n.java_strip(" x\t") == "x"
    assert n.category_root("electronics > audio > headphones") == "electronics"
    assert n.category_leaf("electronics > audio > headphones") == "headphones"


@pytest.mark.parametrize("numerator,denominator,expected", [(1, 2, "0.5"), (1, 3, "0.333"), (2, 3, "0.667"),
                                                            (1, 16, "0.063"), (16, 16, "1"), (20, 2, "10")])
def test_fractions_round_like_java_bigdecimal(numerator, denominator, expected):
    assert n.normalize_title(f"{numerator}/{denominator} cup") == f"{expected} cup"
