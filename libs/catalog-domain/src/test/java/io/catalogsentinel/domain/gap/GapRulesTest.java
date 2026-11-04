package io.catalogsentinel.domain.gap;

import io.catalogsentinel.domain.gap.GapAssessor.Assessment;
import io.catalogsentinel.domain.gap.GapAssessor.BestCandidate;
import io.catalogsentinel.domain.gap.GapAssessor.Input;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GapRulesTest {

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
            "OPEN,CONFIRM,CONFIRMED", "OPEN,DISMISS,DISMISSED", "OPEN,RESOLVE,RESOLVED",
            "CONFIRMED,DISMISS,DISMISSED", "CONFIRMED,RESOLVE,RESOLVED",
            "DISMISSED,REOPEN,OPEN"})
    void allowedTransitions(GapStatus from, GapDecision decision, GapStatus to) {
        assertThat(GapLifecycle.next(from, decision)).contains(to);
    }

    @ParameterizedTest(name = "{0} --{1}--> rejected")
    @CsvSource({"OPEN,REOPEN", "CONFIRMED,REOPEN", "DISMISSED,CONFIRM", "DISMISSED,RESOLVE", "RESOLVED,REOPEN",
            "RESOLVED,CONFIRM", "RESOLVED,DISMISS"})
    void forbiddenTransitions(GapStatus from, GapDecision decision) {
        assertThat(GapLifecycle.next(from, decision)).isEmpty();
    }

    @Test
    void repeatingTheCurrentStateIsANoOp() {
        assertThat(GapLifecycle.isNoOp(GapStatus.CONFIRMED, GapDecision.CONFIRM)).isTrue();
        assertThat(GapLifecycle.isNoOp(GapStatus.OPEN, GapDecision.CONFIRM)).isFalse();
    }

    @Test
    void noCandidateIsTheMostConfidentGap() {
        Assessment a = GapAssessor.assess(new Input(null, 2, 2, 0.9));
        assertThat(a.reason()).isEqualTo(GapReason.NO_CANDIDATE);
        assertThat(a.confidence()).isEqualTo(0.95);
        assertThat(a.priority()).isEqualTo("P1");
        assertThat(a.priorityScore()).isCloseTo(100 * (0.5 * 0.95 + 0.3 * 0.9 + 0.2 * 1.0), within(0.01));
        assertThat(a.explanation()).anyMatch(s -> s.contains("Synthetic demand"));
    }

    @Test
    void siblingVariantIsReportedAsVariantNotCarried() {
        Assessment a = GapAssessor.assess(new Input(
                new BestCandidate(42, 0.45, "NO_MATCH", "NOT_APPLICABLE", Set.of("BRAND_MATCH", "VARIANT_CONFLICT")),
                1, 3, null));
        assertThat(a.reason()).isEqualTo(GapReason.VARIANT_NOT_CARRIED);
        assertThat(a.confidence()).isEqualTo(0.90);
        assertThat(a.demandKnown()).isFalse();
        assertThat(a.explanation()).anyMatch(s -> s.contains("listing 42") && s.contains("VARIANT_CONFLICT"));
    }

    @Test
    void pendingReviewLowersConfidenceAndPriority() {
        Assessment pending = GapAssessor.assess(new Input(
                new BestCandidate(7, 0.85, "REVIEW", "PENDING", Set.of()), 1, 1, null));
        Assessment rejected = GapAssessor.assess(new Input(
                new BestCandidate(7, 0.85, "REVIEW", "REJECTED", Set.of()), 1, 1, null));
        assertThat(pending.reason()).isEqualTo(GapReason.UNCONFIRMED_CANDIDATE);
        assertThat(pending.confidence()).isCloseTo(0.30, within(1e-9));
        assertThat(pending.confidence()).as("an unreviewed plausible match keeps a gap below 50%% confidence").isLessThan(0.5);
        assertThat(rejected.reason()).isEqualTo(GapReason.CANDIDATE_REJECTED);
        assertThat(rejected.priorityScore()).isGreaterThan(pending.priorityScore());
    }

    @Test
    void nonMatchingCandidateConfidenceDecreasesWithScore() {
        double low = GapAssessor.assess(new Input(new BestCandidate(1, 0.2, "NO_MATCH", "NOT_APPLICABLE", Set.of()), 1, 1, null)).confidence();
        double high = GapAssessor.assess(new Input(new BestCandidate(1, 0.68, "NO_MATCH", "NOT_APPLICABLE", Set.of()), 1, 1, null)).confidence();
        assertThat(low).isGreaterThan(high);
        assertThat(high).isGreaterThanOrEqualTo(0.5);
    }
}
