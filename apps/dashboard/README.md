# CatalogSentinel dashboard

React 19 + TypeScript + Vite operations console. Six views, all backed by the real API (no demo data in the
bundle): Overview (selection coverage planogram), Catalog explorer, Selection gaps, Product matching, Batch jobs,
System health. Design rationale: [DESIGN.md](DESIGN.md).

```bash
npm ci
npm run dev        # http://localhost:5173, proxies /api to http://localhost:8080 (API_PROXY_TARGET to change)
npm test           # Vitest + Testing Library + MSW (contract-shaped fixtures in test/fixtures/api.json)
npm run build      # type-check, then production bundle in dist/
```

The API has no CORS configuration, so the console is always served same-origin: by the Vite proxy in
development and by nginx in the container (`Dockerfile`, `nginx.conf`). When the API runs with security
enabled, enter a key under "Set API key"; it is kept in `sessionStorage` for the tab only. A real deployment
should put SSO in front of the console instead of distributing API keys to browsers.
