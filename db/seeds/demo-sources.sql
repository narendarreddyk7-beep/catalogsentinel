-- Demo sources for a local database (idempotent). The same sources are created through the API by
-- scripts/seed-demo.sh; use this only when loading data with psql directly.
INSERT INTO catalog_sources (code, name, role, enabled) VALUES
    ('acme-market', 'Acme Market', 'MARKETPLACE', true),
    ('northwind-supply', 'Northwind Supply', 'REFERENCE', true),
    ('contoso-wholesale', 'Contoso Wholesale', 'REFERENCE', true)
ON CONFLICT (code) DO NOTHING;
