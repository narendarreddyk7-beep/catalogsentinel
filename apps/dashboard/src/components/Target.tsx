import { useSearchParams } from "react-router";
import { useSources } from "../api/queries";
import type { Source } from "../api/types";

/** The marketplace being analysed: `?target=<sourceId>` in the URL, defaulting to the first marketplace. */
export function useTarget(): { target: Source | undefined; marketplaces: Source[]; loading: boolean; error: unknown; setTarget: (id: number) => void } {
  const [params, setParams] = useSearchParams();
  const sources = useSources("MARKETPLACE");
  const marketplaces = sources.data?.items ?? [];
  const wanted = Number(params.get("target"));
  const target = marketplaces.find((s) => s.sourceId === wanted) ?? marketplaces[0];
  return {
    target,
    marketplaces,
    loading: sources.isPending,
    error: sources.error,
    setTarget: (id) =>
      setParams(
        (p) => {
          p.set("target", String(id));
          p.delete("cursor");
          return p;
        },
        { replace: true },
      ),
  };
}

export function TargetPicker({ marketplaces, target, onChange }: { marketplaces: Source[]; target: Source | undefined; onChange: (id: number) => void }) {
  if (marketplaces.length <= 1) {
    return target ? <p className="cell-sub">Marketplace: {target.name}</p> : null;
  }
  return (
    <label className="field">
      Marketplace
      <select value={target?.sourceId ?? ""} onChange={(e) => onChange(Number(e.target.value))}>
        {marketplaces.map((m) => (
          <option key={m.sourceId} value={m.sourceId}>
            {m.name} ({m.code})
          </option>
        ))}
      </select>
    </label>
  );
}
