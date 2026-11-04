# ADR 0013 — Match evidence must be current: supersede, re-justify and split instead of trusting old pairs

- Status: accepted
- Date: 2026-11-03
- Supersedes the linking rules of [ADR 0004](0004-explainable-rule-based-matching.md) where they conflict

## Context

A match candidate is a judgement about two listings' content at the moment it was scored; the row stores both
content hashes. The original implementation trusted candidates after that content changed:

- `linkableFor` returned auto-accepted or approved pairs without checking that both listings were still ACTIVE
  or still had the scored content. Re-matching a changed listing only re-scored the pairs retrieval found again,
  so a listing whose brand and model changed stayed linked to its old product through the old pair.
- Withdrawal superseded only `PENDING` candidates; auto-accepted and approved pairs to a withdrawn listing stayed
  linkable.
- `findMergeablePairs` and `merge` used the same stale pairs, so a merge pass could re-join products that had been
  separated.
- Gap evidence (`BEST_CANDIDATE_SQL`) and the review API accepted stale candidates, so a gap could cite, and a
  reviewer could approve, a comparison of content that no longer existed.
- When a product's representative listing changed and kept the product, the product's description followed the
  new content while other listings were still linked to it.

`StaleMatchEvidenceIT` reproduces each case (5 of 6 scenarios failed before this change).

## Decision

**Currency rule.** A candidate is *current* while both listings are ACTIVE and both content hashes equal the
stored hashes. Only current candidates may justify a link, a merge, gap evidence or a new review decision. The
rule lives in one place (`MatchCandidateRepository.CURRENT`) and is used by linking, merge discovery, the merge
re-check, gap evidence and review.

**Supersession.** When a listing is re-matched (every change and withdrawal produces a change event), every
candidate involving it that is no longer current becomes `SUPERSEDED`, whatever its status. Re-scoring a pair
later overwrites the row with a fresh machine decision.

**Human decisions (explicit policy).** A human approval or rejection applies to the content it was made on.
When either listing changes or is withdrawn, the decision is superseded automatically, and the supersession is
appended to the immutable audit trail as a `SUPERSEDE` decision by `system:matching`, with the previous status,
the reviewer whose decision it was, and which listing changed. The original human decision row is never altered.
Re-approval is required for the new content. Before the change event is processed, the review API refuses new
decisions on the stale candidate with `409 CANDIDATE_STALE`, and while a decision is being recorded both listings
are share-locked so their content cannot change underneath it.

**Linking and splitting.** A listing joins the product of its best current evidence only if the other listing is
linked on its own current content. If an unchanged listing gains evidence joining it to another product, the two
products are merged (both groups remain justified). A changed listing with no current evidence leaves a product
other listings share and gets its own identity; a sole member keeps its product, which then describes its current
content (identity stays stable across ordinary edits). Whenever a member changes, leaves, is withdrawn or loses
evidence, its previous product is **regrouped**: members linked on their current content are partitioned into
connected components of current linkable evidence; the component holding the representative (else the largest,
then the lowest listing id) keeps the product id, every other component gets a new product, and every link is
re-justified by current in-group evidence or marked as the product's own identity. Members whose own change has
not been re-matched yet are left for their own re-match. Other members are never dropped, and links are moved,
not deleted, so history (including withdrawn listings' last link) stays queryable.

**Merges** use current evidence between current links only, and `merge` re-checks that evidence under the
product locks before relinking.

**Concurrency.** Product rows are locked in id order (re-link, regroup and merge all use
`lockActiveProducts`); after locking, a re-link checks the listing and its partner were not moved meanwhile and
otherwise retries the whole matching transaction (at most three attempts, then the queue's retry policy).

## Consequences

- A content change can no longer leave a listing linked, merged, cited as gap evidence or reviewable on the basis
  of content that no longer exists; withdrawal and reactivation rebuild evidence from current content.
- Product identity is deterministic: the representative's group keeps the id, so gaps (keyed by product) stay
  attached to the group that still describes the product.
- A change can create new products (splits). The golden-set results are unchanged: retrieval recall 1.0, cluster
  precision 1.0 and recall 0.456 in `MatchingServiceIT`; the gap evaluation in `GapReconciliationIT` is identical.
- Re-matching a changed listing does more work (supersede, regroup), bounded by the size of its product.
- Products can be left without active members (all listings moved or withdrawn). They stay `ACTIVE` and carry no
  gap (no active reference listing), but still count in the dashboard's canonical-product total; retiring them is
  future work.

## Alternatives considered

- **Re-score every pair involving a changed listing instead of superseding**: requires loading pairs retrieval
  no longer finds, and still needs the currency rule for pairs whose *other* side changed.
- **Delete stale candidates**: loses the record that a reviewer once decided, which the audit trail references.
- **Never undo human approvals automatically**: would keep linking listings whose content no longer matches what
  the reviewer saw. The policy above makes the supersession explicit and audited instead.
