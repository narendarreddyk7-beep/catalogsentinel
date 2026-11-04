from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from catalog_batch.validation import (WITHDRAWN, format_instant, from_csv_row, parse_instant, validate)

NOW = datetime(2026, 11, 3, 12, 0, tzinfo=timezone.utc)
DEFAULT = datetime(2026, 11, 1, tzinfo=timezone.utc)


def fields(record):
    return sorted(v.field for v in validate(record, DEFAULT, NOW).violations)


def test_valid_upsert_is_cleaned_like_listing_content():
    v = validate({"externalProductId": "A-1", "sourceVersion": "7", "title": "  Kettle  ", "brand": " ",
                  "gtin": "4006381-333931", "attributes": {" color ": " Black ", "empty": " ", "n": 3, "b": True}},
                 DEFAULT, NOW)
    assert v.valid, v.violations
    assert (v.title, v.brand, v.source_version, v.gtin) == ("Kettle", None, 7, "04006381333931")
    assert v.attributes == {"color": "Black", "n": "3", "b": "true"}
    assert v.occurred_at == DEFAULT
    assert len(v.content_hash()) == 64


def test_every_violation_is_reported_with_java_field_names():
    assert fields({"sourceVersion": -1, "gtin": "123", "title": " ", "brand": "x" * 201,
                   "attributes": {f"k{i}": "v" for i in range(65)}}) == [
        "externalProductId", "payload.attributes", "payload.brand", "payload.gtin", "payload.title", "sourceVersion"]
    assert fields({"externalProductId": "A", "title": "t", "eventType": "LISTING_DELETED"}) == ["eventType",
                                                                                               "sourceVersion"]
    assert fields({"externalProductId": "x" * 129, "sourceVersion": 1, "title": "t"}) == ["externalProductId"]
    assert fields({"externalProductId": "A", "sourceVersion": True, "title": "t"}) == ["sourceVersion"]
    assert fields({"externalProductId": "A", "sourceVersion": 1.5, "title": "t"}) == ["sourceVersion"]
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": "t", "attributes": {"k": {"nested": 1}}}) \
        == ["payload.attributes"]
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": "t", "attributes": "not json"}) \
        == ["payload.attributes"]


def test_lengths_are_utf16_code_units_like_java():
    emoji_title = "\U0001F600" * 250  # 250 code points = 500 UTF-16 units: allowed
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": emoji_title}) == []
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": emoji_title + "x"}) == ["payload.title"]


def test_occurred_at_rules():
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": "t",
                   "updatedAt": format_instant(NOW + timedelta(minutes=6))}) == ["occurredAt"]
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": "t",
                   "updatedAt": format_instant(NOW + timedelta(minutes=4))}) == []
    assert fields({"externalProductId": "A", "sourceVersion": 1, "title": "t", "updatedAt": "yesterday"}) \
        == ["occurredAt"]


def test_withdrawals_ignore_content_columns():
    v = validate({"externalProductId": "A", "sourceVersion": 2, "eventType": WITHDRAWN, "title": "ignored",
                  "gtin": "bad"}, DEFAULT, NOW)
    assert v.valid and v.title is None and v.content_hash() is None


@pytest.mark.parametrize("text,expected", [
    ("2026-11-03T10:15:30Z", "2026-11-03T10:15:30Z"),
    ("2026-11-03T10:15:30.120Z", "2026-11-03T10:15:30.120Z"),
    ("2026-11-03T12:15:30+02:00", "2026-11-03T10:15:30Z"),
    ("2026-11-03T10:15:30.123456789Z", "2026-11-03T10:15:30.123456Z"),
    ("2026-11-03T10:15Z", "2026-11-03T10:15:00Z"),
])
def test_instants_round_trip_in_java_instant_format(text, expected):
    assert format_instant(parse_instant(text)) == expected


@pytest.mark.parametrize("text", ["2026-11-03", "2026-11-03T10:15:30", "not a date", 20261103])
def test_instants_without_offset_are_rejected(text):
    assert parse_instant(text) is None


def test_csv_rows_map_to_record_fields():
    assert from_csv_row({"external_product_id": "A", "source_version": "3", "title": "T", "extra": "x"}) == {
        "externalProductId": "A", "sourceVersion": "3", "title": "T"}
