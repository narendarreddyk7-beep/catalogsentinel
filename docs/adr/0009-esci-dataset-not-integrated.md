# ADR 0009 — Amazon Science ESCI dataset: evaluated, not integrated

- Status: accepted
- Date: 2026-11-03

## Context

The brief suggests the Shopping Queries Dataset (ESCI, published by Amazon Science) as a possible source of
realistic product data, and requires verifying its format, permissions and limitations first.

What could be established in this environment:

- The dataset and its paper ("Shopping Queries Dataset: A Large-Scale ESCI Benchmark for Improving Product
  Search", arXiv 2206.06588) describe **query–product relevance** judgments: for a search query, each product is
  labeled Exact, Substitute, Complement or Irrelevant. It covers English, Spanish and Japanese locales.
- From the repository README as remembered (not re-read in this session, see below): the data ships as Parquet
  files of examples (`query`, `product_id`, `esci_label`, `split`, ...) and products (`product_title`,
  `product_brand`, `product_color`, `product_description`, `product_bullet_point`, `product_locale`), under an
  Apache-2.0 licence, distributed through GitHub with Git LFS.
- **Not verified here:** the live licence text, current file names and sizes. GitHub downloads are blocked by this
  sandbox's network policy and the web-fetch budget was exhausted when this decision was made. Anyone
  integrating the data must read the repository's LICENSE and README first.

## Decision

Do not integrate ESCI. Evaluate matching on the hand-labeled golden sets plus the synthetic generator
(`scripts/generate-synthetic-data.py`), with their limitations stated next to every result.

## Reasons

1. **Wrong label semantics.** CatalogSentinel needs listing-to-listing identity (are these two offers the same
   sellable variant?). ESCI labels relate a *query* to a product. "Exact" means the product satisfies the query,
   not that two listings describe the same item; two different variants can both be Exact for a broad query.
   Deriving duplicate pairs from it would invent labels.
2. **One catalog, no sources.** Each product appears once with one ASIN; there are no competing supplier or
   marketplace listings of the same item, so there is nothing to deduplicate and no selection gap to find.
3. **No demand data.** It contains no sales, demand or inventory signals; using it would not remove the need
   for clearly labeled synthetic demand.
4. **Not reachable here** (see Context), and integration without reading the licence would violate the brief.

## Consequences

- Matching results are reported only on our own labeled data and synthetic data, never as real-world accuracy.
- A future adapter could still use ESCI product titles/brands as *raw text* to make synthetic listings more
  realistic (titles in, our own perturbations and truth on top), subject to the licence's attribution terms.
  This would improve realism of the text, not the validity of the labels.
