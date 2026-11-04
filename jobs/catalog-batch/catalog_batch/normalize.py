"""Python port of the Java text normalization rules (io.catalogsentinel.domain.normalize, ``norm-v2``).

The batch pipeline must normalize exactly like the online path, otherwise curated batch output and the
listings the API stores would disagree. Parity is enforced by tests against two shared fixtures:
``tests/fixtures/normalization-cases.tsv`` (hand-written edge cases, also consumed by the Java tests) and
``tests/fixtures/normalization-parity-golden.tsv`` (Java output for every field of the golden catalogs,
regenerated and checked by a Java test).

Porting notes (Java ``java.util.regex`` vs Python ``re``):

* Java's ``\\b``, ``\\d``, ``\\s`` are ASCII-only by default (JDK 19+), so every pattern here uses ``re.ASCII``.
* ``\\p{M}`` has no ``re`` equivalent; combining marks are removed with ``unicodedata.category``.
* Lengths and ordering follow Java ``String`` semantics (UTF-16 code units) where they matter.
"""
from __future__ import annotations

import hashlib
import re
import unicodedata
from decimal import ROUND_HALF_UP, Decimal
from typing import Mapping, Optional

# Tracks ProductNormalizer.VERSION. norm-v2 changed only attribute extraction (voltage, plain ounces), which runs
# in Java at ingestion; the text rules ported here are identical to norm-v1.
VERSION = "norm-v2"

_A = re.ASCII

_NON_ALNUM_KEEP_DOT = re.compile(r"[^a-z0-9.\s]", _A)
_STRAY_DOT = re.compile(r"(?<!\d)\.|\.(?!\d)", _A)
_WHITESPACE = re.compile(r"\s+", _A)
_NON_ALNUM = re.compile(r"[^a-z0-9]+", _A)

_DOUBLE_A = re.compile(r"\bdouble\s+a\b", _A)
_TRIPLE_A = re.compile(r"\btriple\s+a\b", _A)
_FRACTION = re.compile(r"\b(\d{1,2})/(2|3|4|8|16)\b", _A)
_QUART = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:quarts?|qts?)\b", _A)
_GALLON = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:gallons?|gal)\b", _A)
_VOLT = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:volts?|v)\b", _A)
_N_IN_M = re.compile(r"\b(\d)\s*-?\s*in\s*-?\s*(\d)\b", _A)
_INCH_MARK = re.compile(r"(\d)\s*(?:''|\")", _A)
_INCH_WORD = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:inches|inch|in)\b", _A)
_TB = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:terabytes?|tb)\b", _A)
_GB = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:gigabytes?|gigs?|gb)\b", _A)
_MB = re.compile(r"\b(\d+)\s*-?\s*(?:megabytes?|mb)\b", _A)
_MAH = re.compile(r"\b(\d+)\s*-?\s*mah\b", _A)
_WATT = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:watts?|w)\b", _A)
_HERTZ = re.compile(r"\b(\d+)\s*-?\s*(?:hertz|hz)\b", _A)
_FLUID_OZ = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:fl\.?\s*oz\.?|fluid\s+ounces?)", _A)
_OUNCE = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:ounces?|oz)\b", _A)
_MILLILITER = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:milliliters?|millilitres?|ml)\b", _A)
_LITER = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:liters?|litres?|ltr|l)\b", _A)
_POUND = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:pounds?|lbs?)\b", _A)
_KILOGRAM = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*(?:kilograms?|kilos?|kg)\b", _A)
_MILLIMETER = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*mm\b", _A)
_CENTIMETER = re.compile(r"\b(\d+(?:\.\d+)?)\s*-?\s*cm\b", _A)
_PACK_OF = re.compile(r"\b(?:pack|set|box|case)\s+of\s+(\d+)\b", _A)
_N_PACK = re.compile(r"\b(\d+)\s*-?\s*(?:packs?|pk|count|ct)\b", _A)
_ORDINAL_GEN = re.compile(r"\b(\d+)(?:st|nd|rd|th)\s*-?\s*gen(?:eration)?\b", _A)
_GEN_NUMBER = re.compile(r"\bgen(?:eration)?\s*-?\s*(\d+)\b", _A)
_WORD_GEN = re.compile(r"\b(first|second|third|fourth|fifth|sixth|seventh|eighth)\s+gen(?:eration)?\b", _A)
_MARK = re.compile(r"\b(?:mark|mk)\s*-?\s*(iii|ii|iv|v|vi|[2-9])\b", _A)
_HYPHEN_JOIN = re.compile(r"(?<=[a-z])-(?=[a-z0-9])|(?<=[0-9])-(?=[a-z])", _A)
_CATEGORY_SEPARATORS = re.compile(r"\s*(?:>|/|\||\\)\s*", _A)
_NON_ALNUM_MODEL = re.compile(r"[^a-z0-9]", _A)

_ORDINAL_WORDS = {"first": "1", "second": "2", "third": "3", "fourth": "4",
                  "fifth": "5", "sixth": "6", "seventh": "7", "eighth": "8"}
_ROMAN = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6"}
_TOKEN_SYNONYMS = {"grey": "gray", "colour": "color", "aluminium": "aluminum",
                   "favourite": "favorite", "centre": "center"}

_CORPORATE_SUFFIXES = {"inc", "incorporated", "corp", "corporation", "co", "company", "ltd", "limited", "llc",
                       "gmbh", "ag", "plc", "sa", "srl", "bv", "kk", "pty", "oy", "ab", "spa", "nv"}
_GENERIC_TRAILING_WORDS = {"electronics", "international", "group", "brands", "technologies", "technology", "usa",
                           "america", "global", "products", "industries", "holdings", "official"}
_BRAND_ALIASES = {
    "hewlett packard": "hp",
    "hewlett packard enterprise": "hpe",
    "apple computer": "apple",
    "procter and gamble": "procter gamble",
    "p and g": "procter gamble",
    "wd": "western digital",
    "lg electronic": "lg",
    "bose audio": "bose",
    "dewalt tools": "dewalt",
    "black and decker": "black decker",
    "black plus decker": "black decker",
    "kitchen aid": "kitchenaid",
    "lego group": "lego",
    "the lego group": "lego",
}

# Java String.strip() removes code points for which Character.isWhitespace is true. Unlike Python's
# str.strip(), that excludes no-break spaces (U+00A0, U+2007, U+202F) and includes U+001C..U+001F.
_JAVA_WHITESPACE = "".join(chr(c) for c in [*range(0x09, 0x0E), *range(0x1C, 0x21), 0x1680, *range(0x2000, 0x2007),
                                            *range(0x2008, 0x200B), 0x2028, 0x2029, 0x205F, 0x3000])


def java_strip(s: str) -> str:
    """Equivalent of Java ``String.strip()``."""
    return s.strip(_JAVA_WHITESPACE)


def java_length(s: str) -> int:
    """Java ``String.length()``: UTF-16 code units, so a non-BMP character counts as two."""
    return len(s.encode("utf-16-le")) // 2


def fold(value: Optional[str]) -> Optional[str]:
    """Lowercase, strip diacritics and unify typographic characters (``TextNormalizer.fold``)."""
    if value is None:
        return None
    s = unicodedata.normalize("NFKD", value)
    s = "".join(ch for ch in s if not unicodedata.category(ch).startswith("M"))
    s = s.lower()  # same results as Java toLowerCase(Locale.ROOT), including the final-sigma rule
    s = (s.replace("’", "'").replace("‘", "'")
         .replace("“", '"').replace("”", '"').replace("″", '"')
         .replace("–", "-").replace("—", "-").replace("×", "x")
         .replace("'", "")
         .replace("&", " and ")
         .replace("+", " plus "))
    s = _DOUBLE_A.sub("aa", s)
    s = _TRIPLE_A.sub("aaa", s)
    return s


def _apply_token_synonyms(s: str) -> str:
    if not s:
        return s
    return " ".join(_TOKEN_SYNONYMS.get(t, t) for t in s.split(" "))


def basic(value: Optional[str]) -> Optional[str]:
    """Fold and collapse to alphanumeric tokens separated by single spaces (``TextNormalizer.basic``)."""
    if value is None:
        return None
    s = _NON_ALNUM.sub(" ", fold(value))
    s = _apply_token_synonyms(java_strip(_WHITESPACE.sub(" ", s)))
    return s or None


def _decimal(numerator: int, denominator: int) -> str:
    d = (Decimal(numerator) / Decimal(denominator)).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)
    return format(d.normalize(), "f")


def canonicalize_units(s: str) -> str:
    """Rewrites unit and variant expressions into compact tokens; input must be folded."""
    s = _FRACTION.sub(lambda m: _decimal(int(m.group(1)), int(m.group(2))), s)
    s = _N_IN_M.sub(r"\1in\2", s)
    s = _INCH_MARK.sub(r"\1in ", s)
    s = _INCH_WORD.sub(r"\1in", s)
    s = _TB.sub(r"\1tb", s)
    s = _GB.sub(r"\1gb", s)
    s = _MB.sub(r"\1mb", s)
    s = _MAH.sub(r"\1mah", s)
    s = _HERTZ.sub(r"\1hz", s)
    s = _FLUID_OZ.sub(r"\1floz ", s)
    s = _OUNCE.sub(r"\1oz", s)
    s = _MILLILITER.sub(r"\1ml", s)
    s = _LITER.sub(r"\1l", s)
    s = _QUART.sub(r"\1qt", s)
    s = _GALLON.sub(r"\1gal", s)
    s = _POUND.sub(r"\1lb", s)
    s = _KILOGRAM.sub(r"\1kg", s)
    s = _MILLIMETER.sub(r"\1mm", s)
    s = _CENTIMETER.sub(r"\1cm", s)
    s = _WATT.sub(r"\1w", s)
    s = _VOLT.sub(r"\1v", s)
    s = _PACK_OF.sub(r"\1pk", s)
    s = _N_PACK.sub(r"\1pk", s)
    s = _ORDINAL_GEN.sub(r"gen\1", s)
    s = _GEN_NUMBER.sub(r"gen\1", s)
    s = _WORD_GEN.sub(lambda m: "gen" + _ORDINAL_WORDS[m.group(1)], s)
    s = _MARK.sub(lambda m: "mk" + _ROMAN.get(m.group(1), m.group(1)), s)
    return s


def normalize_title(value: Optional[str]) -> Optional[str]:
    """Full title normalization (``TextNormalizer.normalizeTitle``)."""
    if value is None:
        return None
    s = fold(value)
    s = canonicalize_units(s)
    s = _HYPHEN_JOIN.sub("", s)
    s = _NON_ALNUM_KEEP_DOT.sub(" ", s)
    s = _STRAY_DOT.sub(" ", s)
    s = java_strip(_WHITESPACE.sub(" ", s))
    s = _apply_token_synonyms(s)
    return s or None


def normalize_brand(value: Optional[str]) -> Optional[str]:
    """``BrandNormalizer.normalize``: corporate suffixes and generic trailing words dropped, aliases applied."""
    s = basic(None if value is None else value.replace(".", ""))
    if s is None:
        return None
    aliased = _BRAND_ALIASES.get(s)
    if aliased is not None:
        return aliased
    tokens = s.split(" ")
    if len(tokens) > 1 and tokens[0] == "the":
        tokens.pop(0)
    changed = True
    while changed and len(tokens) > 1:
        changed = False
        if tokens[-1] in _CORPORATE_SUFFIXES or tokens[-1] in _GENERIC_TRAILING_WORDS:
            tokens.pop()
            changed = True
    result = " ".join(tokens)
    return _BRAND_ALIASES.get(result, result)


def normalize_model(value: Optional[str]) -> Optional[str]:
    """``ModelNormalizer.normalize``: lowercase alphanumerics after unit/Mark/Gen canonicalization."""
    if value is None:
        return None
    s = _NON_ALNUM_MODEL.sub("", canonicalize_units(fold(value)))
    return s or None


def normalize_category(value: Optional[str]) -> Optional[str]:
    """``CategoryNormalizer.normalize``: ``"Electronics/Headphones"`` -> ``"electronics > headphones"``."""
    if value is None:
        return None
    parts = [p for p in (basic(raw) for raw in _CATEGORY_SEPARATORS.split(value)) if p is not None]
    return " > ".join(parts) if parts else None


def category_leaf(normalized_path: Optional[str]) -> Optional[str]:
    if normalized_path is None:
        return None
    idx = normalized_path.rfind(" > ")
    return normalized_path if idx < 0 else normalized_path[idx + 3:]


def category_root(normalized_path: Optional[str]) -> Optional[str]:
    if normalized_path is None:
        return None
    idx = normalized_path.find(" > ")
    return normalized_path if idx < 0 else normalized_path[:idx]


# ------------------------------------------------------------------------------------------------ GTIN

def _gtin_check_digit_ok(digits: str) -> bool:
    total, weight = 0, 3
    for ch in reversed(digits[:-1]):
        total += (ord(ch) - 48) * weight
        weight = 1 if weight == 3 else 3
    return (10 - total % 10) % 10 == ord(digits[-1]) - 48


def _all_ascii_digits(s: str) -> bool:
    return bool(s) and all("0" <= c <= "9" for c in s)


def parse_gtin(raw: Optional[str]) -> Optional[str]:
    """``Gtin.parse``: GTIN-8/12/13/14 with valid check digit, returned as canonical 14 digits, else None."""
    if raw is None:
        return None
    digits = raw.replace(" ", "").replace("-", "")
    if not _all_ascii_digits(digits) or len(digits) not in (8, 12, 13, 14):
        return None
    if not _gtin_check_digit_ok(digits):
        return None
    padded = "0" * (14 - len(digits)) + digits
    if set(padded) == {"0"}:
        return None
    return padded


# ------------------------------------------------------------------------------------------------ hashing

def _utf16_key(s: str) -> bytes:
    return s.encode("utf-16-be")


class CanonicalForm:
    """Port of ``CanonicalForm``: ``name=<utf16-length>:<value>\\n``, ``name=~\\n`` for null."""

    def __init__(self) -> None:
        self._parts: list[str] = []

    def field(self, name: str, value: Optional[str]) -> "CanonicalForm":
        if value is None:
            self._parts.append(f"{name}=~\n")
        else:
            self._parts.append(f"{name}={java_length(value)}:{value}\n")
        return self

    def map_field(self, name: str, values: Optional[Mapping[str, str]]) -> "CanonicalForm":
        if values is None:
            return self.field(name, None)
        self._parts.append(f"{name}={{{len(values)}\n")
        for key in sorted(values, key=_utf16_key):
            self.field("  " + key, values[key])
        self._parts.append("}\n")
        return self

    def text(self) -> str:
        return "".join(self._parts)

    def sha256_hex(self) -> str:
        return hashlib.sha256(self.text().encode("utf-8")).hexdigest()


def content_hash(title: Optional[str], brand: Optional[str], model: Optional[str], category: Optional[str],
                 gtin: Optional[str], attributes: Mapping[str, str]) -> str:
    """``ListingContent.contentHash`` over already-cleaned content (trimmed, canonical GTIN, cleaned attributes)."""
    return (CanonicalForm().field("title", title).field("brand", brand).field("model", model)
            .field("category", category).field("gtin", gtin).map_field("attributes", attributes).sha256_hex())


def normalize_all(title: Optional[str], brand: Optional[str], model: Optional[str],
                  category: Optional[str]) -> dict[str, Optional[str]]:
    """Normalized fields as stored in ``product_listings`` (title, brand, model, category, category leaf)."""
    normalized_category = normalize_category(category)
    return {
        "normalized_title": normalize_title(title),
        "normalized_brand": normalize_brand(brand),
        "normalized_model": normalize_model(model),
        "normalized_category": normalized_category,
        "category_leaf": category_leaf(normalized_category),
    }

