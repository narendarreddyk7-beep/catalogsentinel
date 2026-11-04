import type { ReactNode } from "react";
import { describeError } from "../api/client";

export function Loading({ what }: { what: string }) {
  return (
    <p className="loading" role="status">
      Loading {what}…
    </p>
  );
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const d = describeError(error);
  return (
    <div className="state state-error" role="alert">
      <h3>{d.title}</h3>
      <p>{d.detail}</p>
      {d.reference ? <p className="ref">Correlation id {d.reference}: search the API logs for it.</p> : null}
      {retry ? (
        <p style={{ marginTop: 10 }}>
          <button type="button" className="btn" onClick={retry}>
            Try again
          </button>
        </p>
      ) : null}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state">
      <h3>{title}</h3>
      {children ? <p>{children}</p> : null}
    </div>
  );
}

/** Renders the right state for a query: loading, error, empty or content (stale data stays visible, dimmed). */
export function QueryState<T>({
  query,
  what,
  isEmpty,
  empty,
  children,
}: {
  query: { data: T | undefined; error: unknown; isPending: boolean; isFetching: boolean; refetch: () => unknown };
  what: string;
  isEmpty?: (data: T) => boolean;
  empty?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending) {
    return <Loading what={what} />;
  }
  if (query.data === undefined) {
    return <ErrorState error={query.error} retry={() => void query.refetch()} />;
  }
  if (isEmpty && isEmpty(query.data)) {
    return <>{empty}</>;
  }
  return (
    <div className={query.isFetching ? "refreshing" : undefined} aria-busy={query.isFetching}>
      {query.error ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : null}
      {children(query.data)}
    </div>
  );
}
