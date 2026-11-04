import { useEffect, useState, type ReactNode } from "react";
import { NavLink } from "react-router";
import { getApiKey, setApiKey } from "../api/client";

const NAV = [
  { to: "/", label: "Overview", end: true },
  { to: "/catalog", label: "Catalog explorer" },
  { to: "/gaps", label: "Selection gaps" },
  { to: "/matching", label: "Product matching" },
  { to: "/jobs", label: "Batch jobs" },
  { to: "/health", label: "System health" },
];

type Theme = "system" | "light" | "dark";
const THEME_KEY = "catalogsentinel.theme";

function readTheme(): Theme {
  try {
    const t = localStorage.getItem(THEME_KEY);
    return t === "light" || t === "dark" ? t : "system";
  } catch {
    return "system";
  }
}

function applyTheme(theme: Theme) {
  if (theme === "system") {
    document.documentElement.removeAttribute("data-theme");
  } else {
    document.documentElement.setAttribute("data-theme", theme);
  }
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* not remembered */
  }
}

function AccessKey() {
  const [editing, setEditing] = useState(false);
  const [hasKey, setHasKey] = useState(() => getApiKey() !== null);
  const [value, setValue] = useState("");
  if (!editing) {
    return (
      <button type="button" className="btn btn-quiet" onClick={() => setEditing(true)}>
        {hasKey ? "Change API key" : "Set API key"}
      </button>
    );
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setApiKey(value.trim() || null);
        setHasKey(value.trim() !== "");
        setValue("");
        setEditing(false);
      }}
      className="field"
    >
      <label htmlFor="api-key">API key (kept for this tab only)</label>
      <input id="api-key" type="password" autoComplete="off" value={value} onChange={(e) => setValue(e.target.value)} />
      <span className="actions">
        <button type="submit" className="btn">
          Save key
        </button>
        <button type="button" className="btn btn-quiet" onClick={() => setEditing(false)}>
          Cancel
        </button>
      </span>
    </form>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(readTheme);
  useEffect(() => applyTheme(theme), [theme]);
  return (
    <div className="shell">
      <aside className="rail">
        <div className="wordmark">
          CatalogSentinel
          <small>Simulated catalog system</small>
        </div>
        <nav aria-label="Main">
          <ul>
            {NAV.map((n) => (
              <li key={n.to}>
                <NavLink to={n.to} end={n.end}>
                  {n.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="rail-footer">
          <p>Independent simulation. Not affiliated with or endorsed by any retailer; demand figures are synthetic.</p>
          <AccessKey />
          <label className="field">
            Theme
            <select value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
              <option value="system">Match system</option>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </label>
        </div>
      </aside>
      <main id="content">{children}</main>
    </div>
  );
}

export function PageHeader({ title, lede, children }: { title: string; lede?: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {lede ? <p className="lede">{lede}</p> : null}
      </div>
      {children}
    </header>
  );
}

export function LoadMore({
  hasNextPage,
  isFetchingNextPage,
  fetchNextPage,
  shown,
}: {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => unknown;
  shown: number;
}) {
  return (
    <div className="more actions">
      <span className="cell-sub num">{shown.toLocaleString("en-US")} shown</span>
      {hasNextPage ? (
        <button type="button" className="btn" disabled={isFetchingNextPage} onClick={() => void fetchNextPage()}>
          {isFetchingNextPage ? "Loading…" : "Load more"}
        </button>
      ) : null}
    </div>
  );
}
