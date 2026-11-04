package io.catalogsentinel.api.security;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticationFilterTest {

    @Test
    void parsesHashedKeysWithPipeSeparatedRoles() {
        String hash = HexFormat.of().formatHex(ApiKeyAuthenticationFilter.sha256("s3cret"));
        List<ApiKeyAuthenticationFilter.ApiKey> keys =
                ApiKeyAuthenticationFilter.parse(List.of(hash + ":reader|REVIEWER", ""));
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).roles()).containsExactly("READER", "REVIEWER");
        assertThat(keys.get(0).principal()).isEqualTo("api-key:" + hash.substring(0, 12));
        assertThat(keys.get(0).hash()).isEqualTo(ApiKeyAuthenticationFilter.sha256("s3cret"));
    }

    @Test
    void rejectsPlaintextKeysInConfiguration() {
        assertThatThrownBy(() -> ApiKeyAuthenticationFilter.parse(List.of("plaintext:ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
