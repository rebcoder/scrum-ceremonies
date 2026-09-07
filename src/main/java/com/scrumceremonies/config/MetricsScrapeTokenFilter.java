package com.scrumceremonies.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The only thing standing between a Prometheus scrape endpoint and the public internet.
 *
 * <h2>Why a filter and not {@code .httpBasic()}</h2>
 *
 * {@code .httpBasic()} was tested end-to-end and does not do what it looks like it does here.
 * Because {@code SecurityConfig} declares a {@code BCryptPasswordEncoder} bean, Boot stores its
 * auto-generated password <i>without</i> the {@code {noop\}} prefix and hands
 * {@code DaoAuthenticationProvider} that same BCrypt encoder, so the credential can never validate:
 * observed {@code 401} with the correct password and
 * {@code WARN BCryptPasswordEncoder - Encoded password does not look like BCrypt}. It installs a
 * lock with no key. It also cannot be scoped to just these two paths — the security chain is a
 * single {@code permitAll}-by-default chain (see {@code SecurityConfig}), so basic auth there would
 * put {@code WWW-Authenticate} on every path and prompt a browser auth dialog on ordinary page loads.
 *
 * <h2>Two design properties that are load-bearing, not incidental</h2>
 *
 * <b>1. Removing this filter makes the endpoint REACHABLE, not merely still-closed.</b> The
 * SecurityConfig rule for these two paths is {@code permitAll}, so this filter is the whole control.
 * That is what makes the gate provable: a test can assert {@code delete the filter -> 200}, where a
 * rule that was already {@code denyAll} could only ever assert "still denied", which the pre-existing
 * state already satisfies and proves nothing. A check that can't be demonstrated to fail is not
 * worth much more than no check at all, so the tests here are built to prove this one actually does
 * something.
 *
 * <p><b>2. This filter answers 401; every SecurityConfig rule answers 403.</b> Spring falls back to
 * {@code Http403ForbiddenEntryPoint} with no {@code .exceptionHandling()} configured, so a plain
 * {@code denyAll()} path (e.g. {@code /actuator/env}) returns 403. A <b>401</b> arriving at a test
 * therefore proves this filter, specifically, ran — not just that something on the chain refused the
 * request.
 *
 * <h2>The cost, stated plainly</h2>
 *
 * This opens {@code permitAll} on two paths, with a filter as the only thing closing them. The
 * failure direction is therefore permissive: if this filter fails to register, the endpoints are
 * public and nothing else objects. That is why {@link MetricsScrapeFilterRegistrationCheck} fails
 * the application's startup if it is not in the chain: the app refuses to boot rather than silently
 * serving metrics to the world.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after RequestTracingFilter (+10), still well before Security
public class MetricsScrapeTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Metrics-Token";

    /** The paths this guards. Exact matches plus one prefix — deliberately narrow; see below. */
    static final String PROMETHEUS = "/actuator/prometheus";
    static final String METRICS = "/actuator/metrics";
    static final String METRICS_PREFIX = "/actuator/metrics/";

    private final String token;

    /**
     * NO DEFAULT, deliberately. {@code ${app.metrics.scrape-token:}} resolves to empty when unset,
     * and empty means DENY (below) — a defaulted secret would be a shared, published credential and
     * is strictly worse than no filter, because it looks like a control while being none.
     */
    public MetricsScrapeTokenFilter(@Value("${app.metrics.scrape-token:}") String token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getServletPath() is correct here, with one trap: MockMvc leaves it EMPTY, and an empty
        // path means shouldNotFilter returns TRUE and the filter skips, so a MockMvc test would see
        // the permitAll endpoint wide open. That is precisely why this filter's tests are real-HTTP
        // (RANDOM_PORT + TestRestTemplate) and never MockMvc.
        String path = request.getServletPath();
        return !(PROMETHEUS.equals(path) || METRICS.equals(path) || path.startsWith(METRICS_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // FAIL CLOSED on an unset secret. An unset
        // secret must shut the endpoint, never open it: the alternative is that a missing
        // environment variable silently publishes metrics.
        if (token == null || token.isBlank()) {
            deny(response, "metrics scrape token is not configured");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(presented, token)) {
            deny(response, "missing or invalid metrics scrape token");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Constant-time, and length-safe. {@code MessageDigest.isEqual} short-circuits on differing
     * lengths, which leaks length — acceptable here (the length of a scrape token is not the secret)
     * and noted so nobody "fixes" it into a {@code String.equals}.
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 401, NOT 403 — see the class javadoc. This is the only thing on these paths that can produce
     * a 401, so the status code itself distinguishes "this filter refused you" from "this filter
     * never ran". Do not "align" it with the 403s elsewhere.
     */
    private void deny(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        // No WWW-Authenticate: this is a machine scrape endpoint, and the header would prompt a
        // browser basic-auth dialog.
        response.getWriter().write("{\"error\":\"unauthorized\",\"detail\":\"" + reason + "\"}");
    }
}
