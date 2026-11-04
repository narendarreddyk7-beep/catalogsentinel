"""CatalogSentinel batch ingestion (PySpark).

Validates a supplier export described by a manifest, normalizes it with the same rules as the online path
(``norm-v2`` text rules), writes curated Parquet and rejected records, and publishes idempotent listing events that carry
the ingestion job id. See ``jobs/catalog-batch/README.md``.
"""

__version__ = "0.1.0"
