package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.matching.CanonicalProductRepository.Link;
import io.catalogsentinel.core.matching.CanonicalProductRepository.Member;
import io.catalogsentinel.core.matching.MatchCandidateRepository.StoredCandidate;
import io.catalogsentinel.core.matching.MatchCandidateRepository.Superseded;
import io.catalogsentinel.core.review.ReviewDecisionRepository;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.matching.MatchDecision;
import io.catalogsentinel.domain.matching.MatchResult;
import io.catalogsentinel.domain.matching.ProductMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Matches one listing against retrieved candidates, persists scored candidate pairs with evidence, and keeps
 * canonical products consistent with <em>current</em> evidence (docs/adr/0013-match-evidence-currency.md).
 *
 * <p>Evidence currency: a candidate describes both listings' content when it was scored. Once either listing
 * changes or is withdrawn, every candidate built on the old content is SUPERSEDED, human decisions included
 * (recorded in the audit trail as a {@code SUPERSEDE} by {@value #SYSTEM_REVIEWER}). Only current candidates
 * justify links and merges.
 *
 * <p>Linking policy (deterministic, explainable):
 * <ol>
 *   <li>A human-confirmed link stands while the listing is unchanged and the approval behind it is current.</li>
 *   <li>Otherwise the listing goes with its best current linkable candidate (approvals first, then the highest
 *       {@code AUTO_MATCH}) whose other listing is itself linked on its current content. If this listing is
 *       unchanged and already belongs to another product, the two products are merged (both groups are still
 *       justified); a changed listing moves on its own.</li>
 *   <li>Without such a candidate the listing has its own identity: a sole member keeps its product (identity is
 *       stable across edits); a changed listing leaves a product that others still share.</li>
 * </ol>
 * Whenever a member leaves a product, changes, or loses evidence, the product is regrouped: its members that are
 * connected by current linkable candidates stay together; the group holding the representative (or else the
 * largest) keeps the product and every other group gets a new one. Pairs that only reached REVIEW never join
 * identities. Listings first seen concurrently may each create an identity; {@link #mergeDuplicateIdentities}
 * converges them using current evidence only.
 *
 * <p>Idempotent: re-matching an unchanged listing rewrites identical candidate rows and changes no link, and a
 * human decision is superseded (and audited) once.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    public static final int TRIGRAM_TOP_K = 10;
    /** Recorded as the reviewer when matching supersedes a human decision whose evidence went stale. */
    public static final String SYSTEM_REVIEWER = "system:matching";
    static final int MAX_ATTEMPTS = 3;

    private final MatchingRepository matching;
    private final MatchCandidateRepository candidates;
    private final CanonicalProductRepository canonical;
    private final ProductMatcher matcher;
    private final ReviewDecisionRepository audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchingService(MatchingRepository matching, MatchCandidateRepository candidates,
                           CanonicalProductRepository canonical, ProductMatcher matcher, ReviewDecisionRepository audit,
                           TransactionTemplate tx, Clock clock) {
        this.matching = matching;
        this.candidates = candidates;
        this.canonical = canonical;
        this.matcher = matcher;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** Re-evaluates one listing. Retried when a concurrent re-link, split or merge moved a product under it. */
    public MatchingOutcome matchListing(long listingId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> matchInTransaction(listingId));
            } catch (MembershipChangedException | PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("membership of listing {} changed concurrently; retrying ({})", listingId, e.getMessage());
            }
        }
    }

    private MatchingOutcome matchInTransaction(long listingId) {
        matching.lockListingForMatching(listingId);
        Optional<MatchableListing> loaded = matching.find(listingId);
        if (loaded.isEmpty()) {
            return MatchingOutcome.skipped(listingId, "listing not found");
        }
        MatchableListing listing = loaded.get();
        List<Superseded> superseded = candidates.supersedeStaleFor(listingId);
        auditSupersededDecisions(superseded);

        if (listing.status() == ListingStatus.WITHDRAWN || listing.features() == null) {
            detach(listing);
            return MatchingOutcome.skipped(listingId, "withdrawn; superseded " + superseded.size() + " candidate(s)");
        }

        Map<Long, String> retrieved = matching.retrieveCandidates(listing, TRIGRAM_TOP_K);
        Map<MatchDecision, Integer> decisions = new EnumMap<>(MatchDecision.class);
        for (MatchableListing other : matching.findAll(retrieved.keySet())) {
            if (other.features() == null) {
                continue;
            }
            MatchResult result = matcher.compare(listing.features(), other.features());
            candidates.upsert(listing, other, result, retrieved.get(other.listingId()));
            decisions.merge(result.decision(), 1, Integer::sum);
        }

        LinkResult link = link(listing, !superseded.isEmpty());
        log.debug("matched listingId={} candidates={} decisions={} superseded={} canonicalProductId={} linkMethod={}",
                listingId, retrieved.size(), decisions, superseded.size(), link.canonicalProductId(), link.method());
        return new MatchingOutcome(listingId, retrieved.size(), decisions, link.canonicalProductId(), link.method(),
                link.changed(), null);
    }

    /** A human decision on content that no longer exists is superseded, never silently: one audit row each. */
    private void auditSupersededDecisions(List<Superseded> superseded) {
        Instant now = clock.instant();
        for (Superseded s : superseded) {
            if (s.wasHumanDecision()) {
                audit.append(s.candidateId(), null, "SUPERSEDE", s.previousStatus(), "SUPERSEDED", SYSTEM_REVIEWER,
                        "evidence is stale: " + s.reason() + " since " + s.previousReviewer() + " decided; the decision "
                                + "applied to the earlier content. Review the re-scored candidate.", null, now);
            }
        }
    }

    private LinkResult link(MatchableListing listing, boolean lostEvidence) {
        long id = listing.listingId();
        Optional<Link> existing = canonical.findLink(id);
        Long previous = existing.map(e -> canonical.resolveActive(e.canonicalProductId())).orElse(null);
        boolean unchanged = existing.isPresent() && listing.contentHash().equals(existing.get().linkedContentHash());
        List<StoredCandidate> linkable = candidates.linkableFor(id);

        // 1. A human-confirmed link stands while the listing is unchanged and its approval is still current.
        if (unchanged && "CONFIRMED".equals(existing.get().reviewStatus()) && linkable.stream()
                .anyMatch(c -> c.approved() && Objects.equals(c.candidateId(), existing.get().viaCandidateId()))) {
            return new LinkResult(previous, "KEPT_CONFIRMED", false);
        }

        // 2. Go with the best current evidence whose other listing is linked on its current content.
        for (StoredCandidate c : linkable) {
            long otherId = c.other(id);
            Optional<Long> otherProduct = currentProductOf(otherId);
            if (otherProduct.isEmpty()) {
                continue; // not linked yet, or changed and not re-matched yet: it links to us when it is
            }
            long target = otherProduct.get();
            lockMembership(id, previous, target);
            if (!otherProduct.equals(currentProductOf(otherId))) {
                throw new MembershipChangedException(otherId);
            }
            String method = c.approved() ? "REVIEW_APPROVED" : c.gtinExact() ? "GTIN_EXACT" : "RULES_AUTO";
            String review = c.approved() ? "CONFIRMED" : "AUTO";
            if (previous != null && previous != target && unchanged && canonical.merge(Math.min(previous, target),
                    Math.max(previous, target)) > 0) {
                // Both groups are still justified and new evidence joins them: one product, both groups intact.
                long kept = Math.min(previous, target);
                log.info("evidence {} joins canonical products {} and {}; merged into {}", c.candidateId(), previous,
                        target, kept);
                canonical.upsertLink(id, kept, c.score(), method, review, matcher.version(), c.candidateId(),
                        listing.contentHash());
                return new LinkResult(kept, method, true);
            }
            canonical.upsertLink(id, target, c.score(), method, review, matcher.version(), c.candidateId(),
                    listing.contentHash());
            if (previous != null && (previous != target || lostEvidence || !unchanged)) {
                regroup(previous);
            }
            return new LinkResult(target, method, previous == null || previous != target);
        }

        // 3. No current evidence for any other product: the listing has its own identity.
        if (previous == null) {
            long created = canonical.createFrom(listing);
            canonical.upsertLink(id, created, 1.0, "NEW_IDENTITY", "AUTO", matcher.version(), null, listing.contentHash());
            return new LinkResult(created, "NEW_IDENTITY", true);
        }
        lockMembership(id, previous, null);
        boolean shared = canonical.activeMembers(previous).stream().anyMatch(m -> m.listingId() != id);
        if (!shared) {
            // Sole member: keeps its product, which now describes its current content.
            becomeRepresentative(previous, listing);
            canonical.upsertLink(id, previous, 1.0, "NEW_IDENTITY", "AUTO", matcher.version(), null, listing.contentHash());
            return new LinkResult(previous, "NEW_IDENTITY", false);
        }
        if (unchanged) {
            // Unchanged, but a partner changed: regroup the product by what is still true.
            regroup(previous);
            long now = currentProductOf(id).orElse(previous);
            return new LinkResult(now, "REGROUPED", now != previous);
        }
        // Changed listing leaves a product that other listings still share; they keep it.
        long created = canonical.createFrom(listing);
        canonical.upsertLink(id, created, 1.0, "NEW_IDENTITY", "AUTO", matcher.version(), null, listing.contentHash());
        regroup(previous);
        return new LinkResult(created, "NEW_IDENTITY", true);
    }

    /** A withdrawn listing stops holding membership; its product is regrouped without it. Its link is history. */
    private void detach(MatchableListing listing) {
        Optional<Link> existing = canonical.findLink(listing.listingId());
        if (existing.isEmpty()) {
            return;
        }
        long previous = canonical.resolveActive(existing.get().canonicalProductId());
        lockMembership(listing.listingId(), previous, null);
        regroup(previous);
    }

    /**
     * Regroups a product's members by current evidence. Only members linked on their current content take part;
     * members whose change has not been re-matched yet are left for their own re-match. Connected groups stay
     * together; the group holding the representative (else the largest, then the lowest listing id) keeps the
     * product and every other group gets a new product. Every moved or unjustified link is re-justified.
     * Caller holds the product's lock.
     */
    private void regroup(long productId) {
        List<Member> members = canonical.activeMembers(productId).stream()
                .filter(m -> Objects.equals(m.linkedContentHash(), m.contentHash())).toList();
        if (members.isEmpty()) {
            return;
        }
        Map<Long, Member> byId = new LinkedHashMap<>();
        members.forEach(m -> byId.put(m.listingId(), m));
        List<StoredCandidate> edges = candidates.currentLinkableAmong(byId.keySet());
        List<List<Long>> groups = components(new ArrayList<>(byId.keySet()), edges);

        Long representative = canonical.representativeOf(productId).orElse(null);
        List<Long> anchor = groups.stream().filter(g -> g.contains(representative)).findFirst()
                .orElseGet(() -> groups.stream().max(Comparator.<List<Long>>comparingInt(List::size)
                        .thenComparing(g -> -g.get(0))).orElseThrow());
        if (representative == null || !anchor.contains(representative)) {
            boolean representativeStillMember = representative != null
                    && canonical.activeMembers(productId).stream().anyMatch(m -> m.listingId() == representative);
            if (!representativeStillMember) {
                matching.find(anchor.get(0)).ifPresent(l -> becomeRepresentative(productId, l));
            }
        }
        justify(productId, anchor, byId, edges);
        for (List<Long> group : groups) {
            if (group == anchor) {
                continue;
            }
            MatchableListing first = matching.find(group.get(0)).orElseThrow();
            long created = canonical.createFrom(first);
            log.info("canonical product {} split: listings {} lost their evidence and form product {}", productId,
                    group, created);
            justify(created, group, byId, edges);
        }
    }

    /**
     * Links each listing of a group to the product, keeping links that current in-group evidence still justifies
     * and re-justifying the rest with the strongest in-group evidence (or as the product's own identity).
     */
    private void justify(long productId, List<Long> group, Map<Long, Member> byId, List<StoredCandidate> edges) {
        long representative = canonical.representativeOf(productId).orElse(group.get(0));
        for (long listingId : group) {
            Member m = byId.get(listingId);
            List<StoredCandidate> inGroup = edges.stream()
                    .filter(e -> (e.leftListingId() == listingId && group.contains(e.rightListingId()))
                            || (e.rightListingId() == listingId && group.contains(e.leftListingId())))
                    .toList(); // approvals first, then score
            boolean linkedHere = canonical.findLink(listingId)
                    .map(l -> canonical.resolveActive(l.canonicalProductId()) == productId).orElse(false);
            boolean viaCurrentEvidence = inGroup.stream().anyMatch(e -> Objects.equals(e.candidateId(), m.viaCandidateId()));
            boolean ownIdentity = listingId == representative && "NEW_IDENTITY".equals(m.matchMethod());
            if (linkedHere && (viaCurrentEvidence || ownIdentity)) {
                continue;
            }
            if (listingId == representative || inGroup.isEmpty()) {
                canonical.upsertLink(listingId, productId, 1.0, "NEW_IDENTITY", "AUTO", matcher.version(), null,
                        m.contentHash());
            } else {
                StoredCandidate b = inGroup.get(0);
                canonical.upsertLink(listingId, productId, b.score(),
                        b.approved() ? "REVIEW_APPROVED" : b.gtinExact() ? "GTIN_EXACT" : "RULES_AUTO",
                        b.approved() ? "CONFIRMED" : "AUTO", matcher.version(), b.candidateId(), m.contentHash());
            }
        }
    }

    private void becomeRepresentative(long productId, MatchableListing listing) {
        canonical.setRepresentative(productId, listing.listingId());
        canonical.refreshFromRepresentative(productId, listing);
    }

    /** The active product the listing is linked to, if its link was decided on its current content. */
    private Optional<Long> currentProductOf(long listingId) {
        Optional<Link> link = canonical.findLink(listingId);
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Optional<MatchableListing> listing = matching.find(listingId);
        if (listing.isEmpty() || listing.get().status() != ListingStatus.ACTIVE
                || !Objects.equals(listing.get().contentHash(), link.get().linkedContentHash())) {
            return Optional.empty();
        }
        return Optional.of(canonical.resolveActive(link.get().canonicalProductId()));
    }

    /**
     * Locks the products involved in global id order, then checks the listing was not moved meanwhile by a
     * concurrent regroup or merge (in which case the whole matching transaction is retried).
     */
    private void lockMembership(long listingId, Long previous, Long target) {
        List<Long> ids = Stream.of(previous, target).filter(Objects::nonNull).distinct().toList();
        if (canonical.lockActiveProducts(ids).size() != ids.size()) {
            throw new MembershipChangedException(listingId);
        }
        if (previous != null) {
            Long now = canonical.findLink(listingId).map(l -> canonical.resolveActive(l.canonicalProductId())).orElse(null);
            if (!previous.equals(now)) {
                throw new MembershipChangedException(listingId);
            }
        }
    }

    /** Connected components of the evidence graph, each sorted by listing id, ordered by their first id. */
    static List<List<Long>> components(List<Long> nodes, List<StoredCandidate> edges) {
        Map<Long, Long> parent = new HashMap<>();
        nodes.forEach(n -> parent.put(n, n));
        for (StoredCandidate e : edges) {
            if (parent.containsKey(e.leftListingId()) && parent.containsKey(e.rightListingId())) {
                long a = root(parent, e.leftListingId());
                long b = root(parent, e.rightListingId());
                if (a != b) {
                    parent.put(Math.max(a, b), Math.min(a, b));
                }
            }
        }
        Map<Long, List<Long>> groups = new java.util.TreeMap<>();
        nodes.stream().sorted().forEach(n -> groups.computeIfAbsent(root(parent, n), k -> new ArrayList<>()).add(n));
        return new ArrayList<>(groups.values());
    }

    private static long root(Map<Long, Long> parent, long n) {
        while (parent.get(n) != n) {
            n = parent.get(n);
        }
        return n;
    }

    /**
     * Merges canonical products that current evidence shows to be the same product. Each merge runs in its own
     * short transaction and re-checks the evidence under the product locks. Returns the number of merges.
     */
    public int mergeDuplicateIdentities(int maxMerges) {
        int merges = 0;
        while (merges < maxMerges) {
            List<long[]> pairs = tx.execute(s -> canonical.findMergeablePairs(50));
            if (pairs == null || pairs.isEmpty()) {
                break;
            }
            int before = merges;
            for (long[] pair : pairs) {
                if (merges >= maxMerges) {
                    break;
                }
                Integer relinked = tx.execute(s -> canonical.merge(pair[0], pair[1]));
                if (relinked != null && relinked > 0) {
                    merges++;
                    log.info("merged canonical product {} into {} ({} links moved)", pair[1], pair[0], relinked);
                }
            }
            if (merges == before) {
                break; // only stale pairs left (merged or invalidated concurrently); avoid spinning
            }
        }
        return merges;
    }

    /** Matches every active listing (optionally of one source) that is unlinked or whose link is stale. */
    public int matchPending(Long sourceId, int batchSize, int maxListings) {
        return matchPending(sourceId, batchSize, maxListings, () -> { });
    }

    /** As {@link #matchPending(Long, int, int)}, calling {@code afterBatch} after each batch (lease renewal). */
    public int matchPending(Long sourceId, int batchSize, int maxListings, Runnable afterBatch) {
        long after = 0;
        int processed = 0;
        while (processed < maxListings) {
            List<Long> ids = matching.activeListingIds(sourceId, after, Math.min(batchSize, maxListings - processed), true);
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                matchListing(id);
                processed++;
            }
            after = ids.get(ids.size() - 1);
            afterBatch.run();
        }
        return processed;
    }

    /** A concurrent re-link, split or merge moved a product involved in this matching run. */
    static final class MembershipChangedException extends RuntimeException {
        MembershipChangedException(long listingId) {
            super("canonical membership around listing " + listingId + " changed concurrently");
        }
    }

    private record LinkResult(long canonicalProductId, String method, boolean changed) {
    }

    /** Summary of one matching run for a listing. */
    public record MatchingOutcome(long listingId, int candidatesRetrieved, Map<MatchDecision, Integer> decisions,
                                  Long canonicalProductId, String linkMethod, boolean linkChanged, String skippedReason) {
        static MatchingOutcome skipped(long listingId, String reason) {
            return new MatchingOutcome(listingId, 0, Map.of(), null, null, false, reason);
        }
    }
}
