import type { Problem } from "./types";

/**
 * Same-origin JSON client. The API key (only needed when the API runs with security enabled) is kept in
 * sessionStorage for the current tab; production deployments are expected to put SSO in front of the console.
 */
const KEY_STORAGE = "catalogsentinel.apiKey";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly correlationId: string | undefined;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, problem: Problem, fallbackCorrelationId?: string) {
    super(problem.detail || problem.title || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code;
    this.correlationId = problem.correlationId ?? fallbackCorrelationId;
    this.fieldErrors = problem.errors ?? [];
  }
}

export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(KEY_STORAGE);
  } catch {
    return null;
  }
}

export function setApiKey(key: string | null): void {
  try {
    if (key) {
      sessionStorage.setItem(KEY_STORAGE, key);
    } else {
      sessionStorage.removeItem(KEY_STORAGE);
    }
  } catch {
    /* storage unavailable (private mode); the key simply is not remembered */
  }
}

function newId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
}

export function idempotencyKey(): string {
  return newId();
}

type Query = Record<string, string | number | boolean | null | undefined>;

export function withQuery(path: string, query?: Query): string {
  if (!query) {
    return path;
  }
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== "") {
      params.set(k, String(v));
    }
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PATCH";
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const correlationId = newId();
  const headers: Record<string, string> = { Accept: "application/json", "X-Correlation-Id": correlationId };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (options.idempotencyKey) {
    headers["Idempotency-Key"] = options.idempotencyKey;
  }
  const key = getApiKey();
  if (key) {
    headers["X-Api-Key"] = key;
  }
  // Absolute URL on the page's own origin (also what fetch implementations outside a browser require).
  const url = new URL(path, globalThis.location?.href ?? "http://localhost/").toString();
  let response: Response;
  try {
    response = await fetch(url, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw e;
    }
    throw new ApiError(0, { code: "UNREACHABLE", detail: "The CatalogSentinel API could not be reached." }, correlationId);
  }
  const text = await response.text();
  const payload: unknown = text ? safeJson(text) : null;
  if (!response.ok) {
    const problem: Problem = isObject(payload) ? (payload as Problem) : { detail: text || response.statusText };
    throw new ApiError(response.status, problem, response.headers.get("X-Correlation-Id") ?? correlationId);
  }
  return payload as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** Operator-facing explanation of an error: what failed and what to do next. */
export function describeError(error: unknown): { title: string; detail: string; reference?: string } {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return {
        title: "The API is not reachable",
        detail: "Check that catalog-api is running (docker compose ps) and that this page is served through its proxy.",
        reference: error.correlationId,
      };
    }
    if (error.status === 401 || error.status === 403) {
      return {
        title: error.status === 401 ? "An API key is required" : "This API key cannot do that",
        detail:
          error.status === 401
            ? "Security is enabled on this API. Enter a key under Access in the sidebar."
            : "The key in use lacks the role for this action (reviewer or admin).",
        reference: error.correlationId,
      };
    }
    const fields = error.fieldErrors.map((f) => `${f.field} ${f.message}`).join("; ");
    return {
      title: error.code ? `${humanize(error.code)} (${error.status})` : `Request failed (${error.status})`,
      detail: fields ? `${error.message}: ${fields}` : error.message,
      reference: error.correlationId,
    };
  }
  return { title: "Something went wrong", detail: error instanceof Error ? error.message : String(error) };
}

export function humanize(code: string): string {
  const s = code.toLowerCase().replace(/_/g, " ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}
