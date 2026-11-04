import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useCanonicalProduct, useListing, useListings, useSources } from "../api/queries";
import type { Listing } from "../api/types";
import { LoadMore, PageHeader } from "../components/Shell";
import { Empty, QueryState } from "../components/States";
import { ReviewStatusBadge, StatusBadge } from "../components/Status";
import { ago, full, label, millis, score, when } from "../lib/format";

const FILTER_KEYS = ["q", "sourceCode", "status", "brand", "category", "gtin", "linked"] as const;

function Filters() {
  const [params, setParams] = useSearchParams();
  const sources = useSources();
  const [q, setQ] = useState(params.get("q") ?? "");
  const set = (key: string, value: string) =>
    setParams((p) => {
      if (value) {
        p.set(key, value);
      } else {
        p.delete(key);
      }
      return p;
    });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    set("q", q.trim());
  };
  return (
    <form className="filters" role="search" onSubmit={submit}>
      <label className="field">
        Search titles
        <input type="search" value={q} placeholder="e.g. cordless drill 20v" onChange={(e) => setQ(e.target.value)} />
      </label>
      <label className="field">
        Source
        <select value={params.get("sourceCode") ?? ""} onChange={(e) => set("sourceCode", e.target.value)}>
          <option value="">All sources</option>
          {sources.data?.items.map((s) => (
            <option key={s.sourceId} value={s.code}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        Status
        <select value={params.get("status") ?? ""} onChange={(e) => set("status", e.target.value)}>
          <option value="">Any status</option>
          <option value="ACTIVE">Active</option>
          <option value="WITHDRAWN">Withdrawn</option>
        </select>
      </label>
      <label className="field">
        Matched
        <select value={params.get("linked") ?? ""} onChange={(e) => set("linked", e.target.value)}>
          <option value="">Matched or not</option>
          <option value="true">Linked to a product</option>
          <option value="false">Not linked yet</option>
        </select>
      </label>
      <button type="submit" className="btn">
        Search
      </button>
    </form>
  );
}

function ListingTable({ selectedId }: { selectedId: number | undefined }) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const filters = Object.fromEntries(FILTER_KEYS.map((k) => [k, params.get(k) ?? undefined]));
  const listings = useListings({ ...filters, limit: 50 });
  const query = { ...listings, data: listings.data };
  return (
    <QueryState
      query={query}
      what="listings"
      isEmpty={(d) => d.pages[0]?.items.length === 0}
      empty={<Empty title="No listings match these filters">Clear a filter, or ingest listings from a source first.</Empty>}
    >
      {(d) => {
        const rows = d.pages.flatMap((p) => p.items);
        return (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Listing</th>
                    <th>Source</th>
                    <th>Status</th>
                    <th className="n">Version</th>
                    <th>Matched product</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((l: Listing) => (
                    <tr
                      key={l.listingId}
                      className="clickable"
                      aria-selected={l.listingId === selectedId}
                      onClick={() => navigate({ pathname: `/catalog/listings/${l.listingId}`, search: params.toString() })}
                    >
                      <td>
                        <Link
                          className="cell-title"
                          to={{ pathname: `/catalog/listings/${l.listingId}`, search: params.toString() }}
                          onClick={(e) => e.stopPropagation()}
                        >
                          {l.title ?? l.externalProductId}
                        </Link>
                        <span className="cell-sub">
                          {[l.brand, l.model, l.gtin].filter(Boolean).join(", ") || "no brand, model or GTIN"}
                        </span>
                      </td>
                      <td>
                        {l.sourceCode}
                        <span className="cell-sub">{label(l.sourceRole)}</span>
                      </td>
                      <td>
                        <StatusBadge tone={l.status === "ACTIVE" ? "carried" : "dismissed"}>{label(l.status)}</StatusBadge>
                      </td>
                      <td className="n">{full(l.sourceVersion)}</td>
                      <td>
                        {l.canonicalProductId ? (
                          <>
                            #{l.canonicalProductId}
                            <span className="cell-sub">
                              {label(l.linkMethod)}, {score(l.linkConfidence)}
                            </span>
                          </>
                        ) : (
                          <span className="cell-sub">not linked</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <LoadMore
              hasNextPage={listings.hasNextPage}
              isFetchingNextPage={listings.isFetchingNextPage}
              fetchNextPage={listings.fetchNextPage}
              shown={rows.length}
            />
          </>
        );
      }}
    </QueryState>
  );
}

function Attributes({ values }: { values: Record<string, string> }) {
  const entries = Object.entries(values);
  if (entries.length === 0) {
    return <span className="cell-sub">none</span>;
  }
  return (
    <dl className="kv">
      {entries.map(([k, v]) => (
        <div key={k} style={{ display: "contents" }}>
          <dt>{k}</dt>
          <dd>{k === "synthetic_demand_index" ? <>{v} <span className="synthetic-note">synthetic</span></> : v}</dd>
        </div>
      ))}
    </dl>
  );
}

function SameProduct({ productId, listingId }: { productId: number; listingId: number }) {
  const product = useCanonicalProduct(productId);
  return (
    <QueryState query={product} what="matched product">
      {(p) => (
        <>
          <p>
            <Link to={`/matching/products/${p.product.canonicalProductId}`}>{p.product.title}</Link>
            <span className="cell-sub">
              {full(p.product.activeListings)} active listings, {full(p.product.marketplaceListings)} on marketplaces
            </span>
          </p>
          <ul className="timeline" style={{ marginTop: 10 }}>
            {p.listings
              .filter((l) => l.listingId !== listingId)
              .map((l) => (
                <li key={l.listingId}>
                  <Link to={`/catalog/listings/${l.listingId}`}>{l.title ?? l.externalProductId}</Link>
                  <span className="cell-sub">
                    {l.sourceCode}, {label(l.matchMethod)} {score(l.confidence)}{" "}
                    <ReviewStatusBadge status={l.reviewStatus === "CONFIRMED" ? "APPROVED" : "AUTO_ACCEPTED"} />
                  </span>
                </li>
              ))}
          </ul>
        </>
      )}
    </QueryState>
  );
}

function ListingPanel({ listingId }: { listingId: number }) {
  const detail = useListing(listingId);
  return (
    <QueryState query={detail} what="listing">
      {({ listing: l, history }) => (
        <div className="detail">
          <div>
            <h2>{l.title ?? l.externalProductId}</h2>
            <p className="cell-sub">
              {l.sourceCode} listing {l.externalProductId}, version {full(l.sourceVersion)}, updated {ago(l.updatedAt)}
            </p>
          </div>
          <dl className="kv">
            <dt>Status</dt>
            <dd>
              {label(l.status)}
              {l.withdrawnAt ? ` since ${when(l.withdrawnAt)}` : ""}
            </dd>
            <dt>Brand</dt>
            <dd>{l.brand ?? "–"}</dd>
            <dt>Model</dt>
            <dd>{l.model ?? "–"}</dd>
            <dt>GTIN</dt>
            <dd className="num">{l.gtin ?? "–"}</dd>
            <dt>Category</dt>
            <dd>{l.category ?? "–"}</dd>
          </dl>
          <section>
            <h3>As the matcher sees it</h3>
            <dl className="kv" style={{ marginTop: 8 }}>
              <dt>Title</dt>
              <dd>{l.normalizedTitle ?? "–"}</dd>
              <dt>Brand</dt>
              <dd>{l.normalizedBrand ?? "–"}</dd>
              <dt>Model</dt>
              <dd>{l.normalizedModel ?? "–"}</dd>
              <dt>Category</dt>
              <dd>{l.categoryLeaf ?? "–"}</dd>
            </dl>
          </section>
          <section>
            <h3>Attributes</h3>
            <div style={{ marginTop: 8 }}>
              <Attributes values={l.attributes} />
            </div>
            <h3 style={{ marginTop: 14 }}>Variant attributes extracted</h3>
            <div style={{ marginTop: 8 }}>
              <Attributes values={l.extractedAttributes} />
            </div>
          </section>
          <section>
            <h3>Matched product</h3>
            <div style={{ marginTop: 8 }}>
              {l.canonicalProductId ? (
                <SameProduct productId={l.canonicalProductId} listingId={l.listingId} />
              ) : (
                <p className="cell-sub">Not linked yet: the change consumer links listings shortly after ingestion.</p>
              )}
            </div>
          </section>
          <section>
            <h3>Event history</h3>
            <div className="table-wrap" style={{ marginTop: 8 }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>Outcome</th>
                    <th className="n">Version</th>
                    <th>Path</th>
                    <th className="n">Latency</th>
                    <th>Processed</th>
                  </tr>
                </thead>
                <tbody>
                  {history.map((e) => (
                    <tr key={e.eventId}>
                      <td>
                        {label(e.outcome)}
                        {e.reason ? <span className="cell-sub">{e.reason}</span> : null}
                      </td>
                      <td className="n">{full(e.requestedVersion)}</td>
                      <td>
                        {label(e.ingestionPath)}
                        {e.ingestionJobId ? (
                          <span className="cell-sub">
                            <Link to={`/jobs/ingestion/${e.ingestionJobId}`}>job {e.ingestionJobId}</Link>
                          </span>
                        ) : null}
                      </td>
                      <td className="n">{millis(new Date(e.processedAt).getTime() - new Date(e.receivedAt).getTime())}</td>
                      <td>{when(e.processedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}
    </QueryState>
  );
}

export function CatalogExplorerPage() {
  const { listingId } = useParams();
  const selected = listingId ? Number(listingId) : undefined;
  return (
    <>
      <PageHeader
        title="Catalog explorer"
        lede="Every listing from every source, with what the matcher made of it and the events that shaped it."
      />
      <Filters />
      <div className={selected ? "master-detail" : undefined}>
        <section aria-label="Listings">
          <ListingTable selectedId={selected} />
        </section>
        {selected ? (
          <aside aria-label="Listing detail">
            <ListingPanel listingId={selected} />
          </aside>
        ) : null}
      </div>
    </>
  );
}
