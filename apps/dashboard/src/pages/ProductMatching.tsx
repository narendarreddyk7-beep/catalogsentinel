import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import { useCandidate, useCandidates, useCanonicalProduct, useCanonicalProducts, useMatchDecision } from "../api/queries";
import type { Candidate, Evidence, ListingSide } from "../api/types";
import { BarList } from "../charts/BarList";
import { LoadMore, PageHeader } from "../components/Shell";
import { Empty, ErrorState, QueryState } from "../components/States";
import { ReviewStatusBadge, StatusBadge, type Tone } from "../components/Status";
import { ago, full, label, score } from "../lib/format";

const EFFECT: Record<Evidence["effect"], { tone: Tone; text: string }> = {
  SUPPORTS: { tone: "good", text: "Supports a match" },
  CONTRADICTS: { tone: "warning", text: "Argues against" },
  BLOCKS_AUTO_MATCH: { tone: "warning", text: "Needs a human" },
  FORCES_NO_MATCH: { tone: "critical", text: "Rules it out" },
};

function Tabs() {
  const products = useLocation().pathname.startsWith("/matching/products");
  return (
    <nav className="tabs" aria-label="Matching views">
      <Link to="/matching" aria-current={products ? undefined : "page"}>
        Review queue
      </Link>
      <Link to="/matching/products" aria-current={products ? "page" : undefined}>
        Canonical products
      </Link>
    </nav>
  );
}

function side(l: ListingSide) {
  return `${l.sourceCode}: ${l.title ?? l.externalProductId}`;
}

function Queue({ selectedId }: { selectedId: number | undefined }) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const reviewStatus = params.get("reviewStatus") ?? "PENDING";
  const candidates = useCandidates({ reviewStatus, minScore: params.get("minScore") ?? undefined, limit: 50 });
  return (
    <>
      <div className="filters">
        <label className="field">
          Review status
          <select
            value={reviewStatus}
            onChange={(e) =>
              setParams((p) => {
                p.set("reviewStatus", e.target.value);
                return p;
              })
            }
          >
            <option value="PENDING">Waiting for review</option>
            <option value="AUTO_ACCEPTED">Auto-accepted</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
          </select>
        </label>
      </div>
      <QueryState
        query={candidates}
        what="candidates"
        isEmpty={(d) => d.pages[0]?.items.length === 0}
        empty={<Empty title="Nothing waiting for review">The matcher decided every pair it found on its own.</Empty>}
      >
        {(d) => {
          const rows = d.pages.flatMap((p) => p.items);
          return (
            <>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th className="n">Score</th>
                      <th>Listing A</th>
                      <th>Listing B</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((c: Candidate) => (
                      <tr
                        key={c.candidateId}
                        className="clickable"
                        aria-selected={c.candidateId === selectedId}
                        onClick={() => navigate({ pathname: `/matching/${c.candidateId}`, search: params.toString() })}
                      >
                        <td className="n">
                          <Link to={{ pathname: `/matching/${c.candidateId}`, search: params.toString() }} onClick={(e) => e.stopPropagation()}>
                            {score(c.score)}
                          </Link>
                        </td>
                        <td>{side(c.left)}</td>
                        <td>{side(c.right)}</td>
                        <td>
                          <ReviewStatusBadge status={c.reviewStatus} />
                          <span className="cell-sub">{label(c.retrievalMethod)}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <LoadMore
                hasNextPage={candidates.hasNextPage}
                isFetchingNextPage={candidates.isFetchingNextPage}
                fetchNextPage={candidates.fetchNextPage}
                shown={rows.length}
              />
            </>
          );
        }}
      </QueryState>
    </>
  );
}

const FIELDS: { key: keyof ListingSide; label: string }[] = [
  { key: "title", label: "Title" },
  { key: "brand", label: "Brand" },
  { key: "model", label: "Model" },
  { key: "gtin", label: "GTIN" },
  { key: "category", label: "Category" },
];

function Comparison({ c }: { c: Candidate }) {
  const norm = (v: unknown) => String(v ?? "").trim().toLowerCase();
  return (
    <div className="table-wrap">
      <table className="compare">
        <thead>
          <tr>
            <td />
            <th scope="col">
              <Link to={`/catalog/listings/${c.left.listingId}`}>{c.left.sourceCode}</Link>
            </th>
            <th scope="col">
              <Link to={`/catalog/listings/${c.right.listingId}`}>{c.right.sourceCode}</Link>
            </th>
          </tr>
        </thead>
        <tbody>
          {FIELDS.map((f) => {
            const a = c.left[f.key] as string | null;
            const b = c.right[f.key] as string | null;
            const differs = Boolean(a && b && norm(a) !== norm(b));
            return (
              <tr key={f.key}>
                <th scope="row">{f.label}</th>
                <td className={differs ? "differs" : undefined}>
                  {a ?? "–"}
                  {differs ? <span className="visually-hidden"> (differs)</span> : null}
                </td>
                <td className={differs ? "differs" : undefined}>{b ?? "–"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function CandidatePanel({ candidateId }: { candidateId: number }) {
  const candidate = useCandidate(candidateId);
  const decide = useMatchDecision(candidateId);
  const [reason, setReason] = useState("");
  const [done, setDone] = useState<string | null>(null);
  return (
    <QueryState query={candidate} what="candidate">
      {(c) => {
        const scores = Object.entries(c.featureScores).filter(([, v]) => v !== null) as [string, number][];
        const reviewable = ["PENDING", "AUTO_ACCEPTED", "APPROVED", "REJECTED"].includes(c.reviewStatus);
        return (
          <div className="detail">
            <div>
              <p className="actions">
                <ReviewStatusBadge status={c.reviewStatus} />
                <span className="cell-sub">
                  {c.matcherVersion}, found by {label(c.retrievalMethod).toLowerCase()}, {ago(c.createdAt)}
                </span>
              </p>
              <h2 style={{ marginTop: 8 }}>
                Score {score(c.score)}: {label(c.decision).toLowerCase()}
              </h2>
            </div>
            <Comparison c={c} />
            <section>
              <h3>Evidence</h3>
              <ul className="evidence" style={{ marginTop: 10 }}>
                {c.evidence.map((e, i) => (
                  <li key={i}>
                    <StatusBadge tone={EFFECT[e.effect]?.tone ?? "neutral"}>
                      <span className="visually-hidden">{EFFECT[e.effect]?.text ?? e.effect}</span>
                    </StatusBadge>
                    <span>
                      <code>{label(e.code)}</code>
                      {e.detail ? <span className="cell-sub">{e.detail}</span> : null}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
            {reviewable ? (
              <section>
                <h3>Decision</h3>
                {done ? (
                  <p className="notice" role="status" style={{ marginTop: 8 }}>
                    {done}
                  </p>
                ) : null}
                {decide.error ? <ErrorState error={decide.error} /> : null}
                <form
                  className="detail"
                  style={{ gap: 12, marginTop: 8 }}
                  onSubmit={(e: FormEvent) => e.preventDefault()}
                >
                  <label className="field">
                    Note for the audit trail (optional)
                    <textarea value={reason} onChange={(e) => setReason(e.target.value)} />
                  </label>
                  <div className="actions">
                    <button
                      type="button"
                      className="btn btn-primary"
                      disabled={decide.isPending}
                      onClick={() =>
                        decide.mutate(
                          { decision: "APPROVE", reason: reason || undefined },
                          { onSuccess: (r) => setDone(r.result.changed ? "Approved: both listings now share one product." : "Already approved.") },
                        )
                      }
                    >
                      Approve match
                    </button>
                    <button
                      type="button"
                      className="btn"
                      disabled={decide.isPending}
                      onClick={() =>
                        decide.mutate(
                          { decision: "REJECT", reason: reason || undefined },
                          { onSuccess: (r) => setDone(r.result.changed ? "Rejected: the listings stay separate products." : "Already rejected.") },
                        )
                      }
                    >
                      Reject match
                    </button>
                  </div>
                </form>
              </section>
            ) : (
              <p className="cell-sub">This pair was superseded by a newer comparison and can no longer be reviewed.</p>
            )}
            <section>
              <h3>Feature scores</h3>
              <div style={{ marginTop: 10 }}>
                <BarList
                  label="Feature scores, 0 to 1"
                  max={1}
                  bars={scores.map(([k, v]) => ({ key: k, label: label(k), value: v, display: score(v) }))}
                />
              </div>
            </section>
          </div>
        );
      }}
    </QueryState>
  );
}

function Products({ selectedId }: { selectedId: number | undefined }) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [q, setQ] = useState(params.get("q") ?? "");
  const products = useCanonicalProducts({ q: params.get("q") ?? undefined, limit: 50 });
  return (
    <>
      <form
        className="filters"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setParams((p) => {
            if (q.trim()) {
              p.set("q", q.trim());
            } else {
              p.delete("q");
            }
            return p;
          });
        }}
      >
        <label className="field">
          Search products
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <button type="submit" className="btn">
          Search
        </button>
      </form>
      <QueryState query={products} what="products" isEmpty={(d) => d.pages[0]?.items.length === 0} empty={<Empty title="No canonical products match" />}>
        {(d) => {
          const rows = d.pages.flatMap((p) => p.items);
          return (
            <>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Product</th>
                      <th className="n">Active listings</th>
                      <th className="n">On marketplaces</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((p) => (
                      <tr
                        key={p.canonicalProductId}
                        className="clickable"
                        aria-selected={p.canonicalProductId === selectedId}
                        onClick={() => navigate(`/matching/products/${p.canonicalProductId}`)}
                      >
                        <td>
                          <Link className="cell-title" to={`/matching/products/${p.canonicalProductId}`} onClick={(e) => e.stopPropagation()}>
                            {p.title}
                          </Link>
                          <span className="cell-sub">{[p.brand, p.model, p.gtin].filter(Boolean).join(", ")}</span>
                        </td>
                        <td className="n">{full(p.activeListings)}</td>
                        <td className="n">{full(p.marketplaceListings)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <LoadMore hasNextPage={products.hasNextPage} isFetchingNextPage={products.isFetchingNextPage} fetchNextPage={products.fetchNextPage} shown={rows.length} />
            </>
          );
        }}
      </QueryState>
    </>
  );
}

function ProductPanel({ productId }: { productId: number }) {
  const product = useCanonicalProduct(productId);
  return (
    <QueryState query={product} what="product">
      {({ product: p, listings }) => (
        <div className="detail">
          <div>
            <h2>{p.title}</h2>
            <p className="cell-sub">
              Product #{p.canonicalProductId}
              {p.status === "MERGED" && p.mergedIntoId ? `, merged into #${p.mergedIntoId}` : ""}
            </p>
          </div>
          <dl className="kv">
            <dt>Brand</dt>
            <dd>{p.brand ?? "–"}</dd>
            <dt>Model</dt>
            <dd>{p.model ?? "–"}</dd>
            <dt>GTIN</dt>
            <dd className="num">{p.gtin ?? "–"}</dd>
            <dt>Category</dt>
            <dd>{p.category ?? "–"}</dd>
          </dl>
          <section>
            <h3>Listings of this product</h3>
            <div className="table-wrap" style={{ marginTop: 8 }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>Listing</th>
                    <th>Linked by</th>
                    <th className="n">Confidence</th>
                  </tr>
                </thead>
                <tbody>
                  {listings.map((l) => (
                    <tr key={l.listingId}>
                      <td>
                        <Link to={`/catalog/listings/${l.listingId}`}>{l.title ?? l.externalProductId}</Link>
                        <span className="cell-sub">
                          {l.sourceCode} ({label(l.sourceRole).toLowerCase()}), {label(l.status).toLowerCase()}
                        </span>
                      </td>
                      <td>
                        {label(l.matchMethod)}
                        {l.viaCandidateId ? (
                          <span className="cell-sub">
                            <Link to={`/matching/${l.viaCandidateId}`}>candidate {l.viaCandidateId}</Link>
                          </span>
                        ) : null}
                      </td>
                      <td className="n">{score(l.confidence)}</td>
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

export function ProductMatchingPage({ view }: { view: "queue" | "products" }) {
  const { candidateId, productId } = useParams();
  const selected = view === "queue" ? (candidateId ? Number(candidateId) : undefined) : productId ? Number(productId) : undefined;
  return (
    <>
      <PageHeader
        title="Product matching"
        lede="Pairs of listings the matcher could not settle alone, with the evidence it used. Approving links both listings to one canonical product."
      />
      <Tabs />
      <div className={selected ? "master-detail" : undefined}>
        <section aria-label={view === "queue" ? "Review queue" : "Canonical products"}>
          {view === "queue" ? <Queue selectedId={selected} /> : <Products selectedId={selected} />}
        </section>
        {selected ? (
          <aside aria-label="Detail">{view === "queue" ? <CandidatePanel candidateId={selected} /> : <ProductPanel productId={selected} />}</aside>
        ) : null}
      </div>
      {candidateId && Number.isNaN(Number(candidateId)) ? <ErrorState error={new Error("Not a candidate id")} /> : null}
    </>
  );
}
