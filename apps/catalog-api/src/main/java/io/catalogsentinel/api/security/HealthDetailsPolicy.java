package io.catalogsentinel.api.security;

import java.util.Locale;

/**
 * Health component details (database, disk) must not be public while API-key security is enabled
 * (docs/security.md#health-endpoint). The default is {@code when-authorized} with role ADMIN; the local stack, which
 * runs without authentication, opts in to {@code always}. Turning security on without turning that off is refused at
 * start-up instead of silently publishing the details.
 */
public final class HealthDetailsPolicy {

    static final String PROPERTY = "management.endpoint.health.show-details";

    private HealthDetailsPolicy() {
    }

    /** @throws IllegalStateException if security is enabled and health details would be shown to every caller */
    public static void check(boolean securityEnabled, String showDetails) {
        if (securityEnabled && showDetails != null
                && showDetails.strip().replace('_', '-').toLowerCase(Locale.ROOT).equals("always")) {
            throw new IllegalStateException(PROPERTY + "=" + showDetails.strip() + " would show health details "
                    + "(database, disk) to unauthenticated callers while API-key security is enabled; set "
                    + "HEALTH_SHOW_DETAILS=when-authorized (details for ADMIN keys only)");
        }
    }
}
