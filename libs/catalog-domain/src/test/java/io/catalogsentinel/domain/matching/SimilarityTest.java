package io.catalogsentinel.domain.matching;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimilarityTest {

    @Test
    void jaroWinklerMatchesReferenceValues() {
        // Classic reference values from Winkler (1990).
        assertThat(Similarity.jaroWinkler("martha", "marhta")).isCloseTo(0.9611, within(0.0001));
        assertThat(Similarity.jaroWinkler("dwayne", "duane")).isCloseTo(0.84, within(0.0001));
        assertThat(Similarity.jaroWinkler("dixon", "dicksonx")).isCloseTo(0.8133, within(0.0001));
        assertThat(Similarity.jaroWinkler("abc", "abc")).isEqualTo(1.0);
        assertThat(Similarity.jaroWinkler("abc", "xyz")).isEqualTo(0.0);
    }

    @Test
    void trigramsFollowPgTrgmPadding() {
        assertThat(Similarity.trigrams("cat")).containsExactlyInAnyOrder("  c", " ca", "cat", "at ");
        assertThat(Similarity.trigrams("")).isEmpty();
    }

    @Test
    void jaccardAndOverlap() {
        assertThat(Similarity.jaccard(Set.of("a", "b"), Set.of("b", "c"))).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(Similarity.overlap(Set.of("a", "b"), Set.of("b"))).isEqualTo(1.0);
        assertThat(Similarity.jaccard(Set.of(), Set.of())).isEqualTo(0.0);
    }
}
