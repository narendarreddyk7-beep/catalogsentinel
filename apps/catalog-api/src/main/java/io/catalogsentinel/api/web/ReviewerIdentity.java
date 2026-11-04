package io.catalogsentinel.api.web;

import io.catalogsentinel.domain.common.DomainValidationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who made a decision. With authentication enabled the authenticated principal is recorded and any
 * client-supplied name is ignored, so the audit trail cannot be spoofed; in local mode (no authentication) the
 * request's {@code reviewer} field is used.
 */
final class ReviewerIdentity {

    private ReviewerIdentity() {
    }

    static String resolve(String requested) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        if (requested == null || requested.isBlank()) {
            throw new DomainValidationException("reviewer", "is required");
        }
        return "local:" + requested.strip();
    }
}
