import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import fixtures from "./fixtures/api.json";

type FixtureKey = keyof typeof fixtures;
const table = fixtures as Record<string, unknown>;

/** Serves the contract-shaped fixtures; a key may pin one query parameter (e.g. ?queue=...). */
export const defaultHandlers = [
  http.get("*/api/*", ({ request }) => {
    const url = new URL(request.url);
    for (const [key, value] of Object.entries(table)) {
      const [method, target] = key.split(" ") as [string, string];
      if (method !== "GET") {
        continue;
      }
      const [path, query] = target.split("?");
      if (path !== url.pathname) {
        continue;
      }
      if (query) {
        const [k, v] = query.split("=") as [string, string];
        if (url.searchParams.get(k) !== v) {
          continue;
        }
      }
      return HttpResponse.json(value as Record<string, unknown>);
    }
    return HttpResponse.json({ status: 404, code: "NOT_FOUND", detail: `no fixture for ${url.pathname}` }, { status: 404 });
  }),
];

export const server = setupServer(...defaultHandlers);

export function fixture<K extends FixtureKey>(key: K): (typeof fixtures)[K] {
  return structuredClone(fixtures[key]);
}
