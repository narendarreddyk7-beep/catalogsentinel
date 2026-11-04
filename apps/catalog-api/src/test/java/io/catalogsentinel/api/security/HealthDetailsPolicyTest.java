package io.catalogsentinel.api.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthDetailsPolicyTest {

    @Test
    void publicHealthDetailsAreRefusedOnlyWhenSecurityIsEnabled() {
        for (String always : new String[] {"always", "ALWAYS", " Always "}) {
            assertThatThrownBy(() -> HealthDetailsPolicy.check(true, always))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HEALTH_SHOW_DETAILS=when-authorized");
        }
        assertThatCode(() -> HealthDetailsPolicy.check(true, "when-authorized")).doesNotThrowAnyException();
        assertThatCode(() -> HealthDetailsPolicy.check(true, "WHEN_AUTHORIZED")).doesNotThrowAnyException();
        assertThatCode(() -> HealthDetailsPolicy.check(true, "never")).doesNotThrowAnyException();
        assertThatCode(() -> HealthDetailsPolicy.check(true, null)).doesNotThrowAnyException();
        assertThatCode(() -> HealthDetailsPolicy.check(false, "always")).doesNotThrowAnyException();
    }
}
