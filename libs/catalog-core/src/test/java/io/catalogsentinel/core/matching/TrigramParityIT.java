package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.matching.Similarity;
import io.catalogsentinel.domain.normalize.TextNormalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Java trigram similarity used for scoring must agree with PostgreSQL's pg_trgm similarity() used for
 * retrieval; otherwise offline evaluation would not predict production retrieval.
 */
class TrigramParityIT {

    private final CoreTestContext ctx = new CoreTestContext();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Sony WH-1000XM5 Wireless Headphones|SONY WH1000XM5 Noise Cancelling Headphones Black",
            "Apple iPhone 15 Pro 128GB|Apple iPhone 15 128GB",
            "Starbucks Pike Place 12 oz|Starbucks Veranda Blend 12 oz",
            "LEGO Icons Concorde 10318|Lego 10318 Concorde",
            "a|b"})
    void javaMatchesPgTrgm(String left, String right) {
        String a = TextNormalizer.normalizeTitle(left);
        String b = TextNormalizer.normalizeTitle(right);
        Double sql = ctx.jdbcTemplate.queryForObject("SELECT similarity(?, ?)::float8", Double.class, a, b);
        double java = Similarity.trigramSimilarity(Similarity.trigrams(a), Similarity.trigrams(b));
        assertThat(java).isCloseTo(sql, within(1e-6));
    }
}
