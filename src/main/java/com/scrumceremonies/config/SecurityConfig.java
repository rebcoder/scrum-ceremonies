package com.scrumceremonies.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Single security chain for the free tools (anonymous product — no authentication).
 *
 * <p>One chain covers everything: actuator exposure rules, the
 * {@code MetricsScrapeTokenFilter}-guarded metrics endpoints, CSP/CORS headers, and CSRF
 * ignored for {@code /ws/**} + the legacy verb-based {@code /api/**}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost,https://yourdomain.com}")
    private String allowedOrigins;

    @Value("${app.csp.connect-src:self http://localhost:8080 ws://localhost:8080 wss://localhost:8080}")
    private String cspConnectSrc;

    @Bean
    @org.springframework.context.annotation.Profile("!test-websocket")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                        "/actuator/info").permitAll()
                        // permitAll here, guarded instead by MetricsScrapeTokenFilter, which runs
                        // earlier in the chain (before Spring Security) and is the actual gate on
                        // these two paths.
                        //
                        // This chain has no authentication mechanism at all — no httpBasic, no
                        // formLogin, nothing that reads a credential — so an .authenticated() rule
                        // here would deny every caller unconditionally, including one presenting
                        // valid credentials, since nothing in the chain ever examines them. That
                        // would only succeed in exporting metrics to an endpoint nobody could reach.
                        //
                        // permitAll is what makes the filter the whole control, and that is what
                        // makes the control provable: removing the filter makes the endpoint return
                        // 200. A denyAll rule could only ever demonstrate "still denied", which
                        // proves nothing about whether the filter itself does anything.
                        //
                        // Because the failure direction here is permissive, it is backed by
                        // MetricsScrapeFilterRegistrationCheck, which refuses to start the
                        // application if that filter is not registered in the servlet chain.
                        //
                        // /actuator/** denyAll below is untouched: env, beans, heapdump, loggers stay
                        // 403. /actuator/health stays permitAll above, so the Docker HEALTHCHECK and
                        // container probes are unaffected.
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**",
                                        "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll()
                )

                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/ws/**", "/api/**", "/actuator/**")
                )

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives(buildCspPolicy())
                        )
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .permissionsPolicy(permissions -> permissions
                                .policy("camera=(), microphone=(), geolocation=()")
                        )
                )

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

    /**
     * The Content-Security-Policy sent with API responses.
     *
     * <p><b>No third-party script origin is permitted.</b> The tools serve their JavaScript from
     * their own origin ({@code frontend/v1/vendor/}), so nothing needs to load from a CDN.
     * Allow-listing a CDN origin means trusting every file that origin will ever serve, including
     * anything it serves unpinned — the polyfill.io supply-chain takeover is the canonical
     * demonstration of why that trust is misplaced.
     *
     * <p><b>This header only reaches API responses.</b> In production the backend is API-only
     * ({@code spring.web.resources.add-mappings=false}) — the HTML documents that actually load
     * scripts are served as static files, so a CSP on a JSON response constrains nothing for them.
     * The equivalent policy for those documents lives in
     * {@code frontend/v1/staticwebapp.config.json}, and the two must be changed together.
     */
    private String buildCspPolicy() {
        return "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "connect-src 'self' " + cspConnectSrc + "; "
                + "frame-src 'self'; "
                + "frame-ancestors 'self';";
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse allowed origins from comma-separated string, trim whitespace
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "Accept", "X-Requested-With", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // CORS only applies to browser requests, not crawlers
        // Crawlers like Googlebot don't send CORS headers, so they're not affected
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
