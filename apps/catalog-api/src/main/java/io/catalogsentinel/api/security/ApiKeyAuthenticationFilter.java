package io.catalogsentinel.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Authenticates {@code X-Api-Key} headers against configured SHA-256 hashes. Only hashes are configured
 * (from Secrets Manager in AWS), so a leaked configuration does not leak usable keys. Comparison is
 * constant-time over every configured key. The principal name is a non-reversible key fingerprint, which is
 * what review decisions record as the reviewer when authentication is enabled.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final List<ApiKey> keys;

    public ApiKeyAuthenticationFilter(List<String> configuredKeys) {
        this.keys = parse(configuredKeys);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && !presented.isBlank()) {
            byte[] presentedHash = sha256(presented.strip());
            ApiKey match = null;
            for (ApiKey key : keys) {
                if (MessageDigest.isEqual(presentedHash, key.hash()) && match == null) {
                    match = key;
                }
            }
            if (match != null) {
                List<SimpleGrantedAuthority> authorities = match.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(match.principal(), null, authorities));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    static List<ApiKey> parse(List<String> configured) {
        List<ApiKey> parsed = new ArrayList<>();
        if (configured == null) {
            return parsed;
        }
        for (String entry : configured) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int sep = entry.indexOf(':');
            if (sep != 64) {
                throw new IllegalArgumentException("API key entries must be '<64-hex-sha256>:<ROLE>[|<ROLE>]'");
            }
            String hashHex = entry.substring(0, 64).toLowerCase(Locale.ROOT);
            List<String> roles = List.of(entry.substring(65).toUpperCase(Locale.ROOT).split("\\|"));
            parsed.add(new ApiKey(HexFormat.of().parseHex(hashHex), roles, "api-key:" + hashHex.substring(0, 12)));
        }
        return parsed;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record ApiKey(byte[] hash, List<String> roles, String principal) {
    }
}
