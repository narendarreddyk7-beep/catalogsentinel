package io.catalogsentinel.api.security;

import io.catalogsentinel.api.config.CatalogApiProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;

/**
 * Two modes:
 * <ul>
 *   <li>{@code catalogsentinel.security.enabled=false} (local development): everything is permitted.</li>
 *   <li>{@code enabled=true} (the {@code aws} profile): stateless API-key authentication with roles
 *       READER, REVIEWER, INGEST and ADMIN; health status and API docs stay public (health component details only
 *       for ADMIN, see {@link HealthDetailsPolicy}); everything else is denied.</li>
 * </ul>
 * TLS terminates at the load balancer (see infra/terraform).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CatalogApiProperties props, Environment env)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        CatalogApiProperties.Security security = props.security();
        if (security == null || !security.enabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        HealthDetailsPolicy.check(true, env.getProperty(HealthDetailsPolicy.PROPERTY));
        http.addFilterBefore(new ApiKeyAuthenticationFilter(security.keys()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Scraped over localhost by the in-task ADOT collector, which holds no API key. Not
                        // reachable from outside: the ALB answers /actuator/* with 403 and the task security
                        // group admits only the ALB (docs/security.md). Metrics carry no secrets.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Operator endpoints expose raw message bodies and change delivery state: ADMIN for every method.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/listing-events", "/api/v1/ingestion/**")
                        .hasAnyRole("INGEST", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/ingestion/**").hasAnyRole("INGEST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/match-candidates/*/decisions", "/api/v1/gaps/*/decisions")
                        .hasAnyRole("REVIEWER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/sources", "/api/v1/reconciliation/jobs").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/sources/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("READER", "REVIEWER", "INGEST", "ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, 401, "UNAUTHENTICATED", "A valid X-Api-Key header is required"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, 403, "FORBIDDEN", "The API key lacks the role for this operation")));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"https://catalogsentinel.io/problems/" + code.toLowerCase().replace('_', '-')
                + "\",\"title\":\"" + (status == 401 ? "Unauthorized" : "Forbidden") + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
