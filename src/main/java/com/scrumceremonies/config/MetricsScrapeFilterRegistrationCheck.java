package com.scrumceremonies.config;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.FilterRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The application refuses to start if {@link MetricsScrapeTokenFilter} is not actually
 * registered in the servlet chain.
 *
 * <h2>Why this is not optional</h2>
 *
 * The {@code /actuator/prometheus} and {@code /actuator/metrics} endpoints are {@code permitAll}
 * in {@code SecurityConfig}, so this filter is the <b>whole</b> control on them. That is what
 * makes the control provable — removing the filter makes the endpoint return 200, which is the
 * only way to demonstrate that it guards anything — but it comes at the cost of a failure
 * direction that is <b>permissive</b>.
 *
 * <p>That tradeoff is worth spelling out plainly: if this filter silently fails to register — a
 * component scan that stops covering {@code config}, a profile that excludes it, a conditional
 * added later — then <b>metrics become public and nothing anywhere reports it</b>. Most broken
 * checks fail by wrongly reporting success; this one fails by leaving an open door. A green
 * build, a passing test suite, and a running application would all be consistent with an
 * unauthenticated metrics endpoint sitting on the public internet.
 *
 * <p>So this check does not log a warning and move on — it throws, and the application does not
 * start. Loud and unmissable is the right tradeoff when the quiet alternative is an open door.
 *
 * <h2>What it actually verifies</h2>
 *
 * That a registration for the filter's class exists in the {@link ServletContext}. This
 * deliberately checks the running container's registry rather than the Spring bean: a bean can
 * exist without being wired into the chain, and it's the chain that serves requests. Asking the
 * servlet context is asking the thing that actually decides.
 *
 * <p>It does not verify the filter's order, or that the token is set. Order is covered by the
 * real-HTTP tests (a mis-ordered filter would let a request through, and the test would see 200
 * without a token); an unset token is handled by the filter itself, which fails closed. Stating
 * that boundary here so this check isn't read as broader than it is.
 */
@Component
public class MetricsScrapeFilterRegistrationCheck {

    private static final Logger log = LoggerFactory.getLogger(MetricsScrapeFilterRegistrationCheck.class);

    private final ServletContext servletContext;

    public MetricsScrapeFilterRegistrationCheck(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assertMetricsFilterIsRegistered() {
        String target = MetricsScrapeTokenFilter.class.getName();

        Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();

        // This check must not confuse "our filter is missing" with "there is no filter registry
        // at all".
        //
        // A MockMvc / mock-servlet context registers no filters at all — not Spring Security's,
        // not the character-encoding one, none — so `getFilterRegistrations()` comes back empty
        // there, and an absence check against an empty registry is vacuously true no matter what
        // it's looking for.
        //
        // An empty registry is therefore not evidence of anything and must not be treated as a
        // failure. But it must not be silent either, or the check quietly stops running in some
        // context and nobody notices — so the skip is logged at WARN, naming why.
        if (registrations.isEmpty()) {
            log.warn("Metrics filter registration check SKIPPED: the servlet context has no filter "
                    + "registrations at all, which means this is a mock servlet environment rather "
                    + "than a running container. This check is only meaningful against a real "
                    + "container; it has NOT verified anything here.");
            return;
        }

        boolean present = registrations.values().stream()
                .anyMatch(r -> target.equals(r.getClassName()));

        if (!present) {
            // The message names the consequence, not just the fact. Someone reading a boot failure
            // at 3am needs to know why the application would rather die than start.
            throw new IllegalStateException(
                    "FATAL: " + MetricsScrapeTokenFilter.class.getSimpleName() + " is NOT registered in the "
                            + "servlet filter chain. /actuator/prometheus and /actuator/metrics are permitAll in "
                            + "SecurityConfig and this filter is the ONLY control on them — without it they are "
                            + "PUBLIC. Refusing to start rather than serve metrics unauthenticated. "
                            + "Registered filters: " + registrations.keySet());
        }

        log.info("Metrics scrape filter registered — /actuator/prometheus and /actuator/metrics are token-gated");
    }

    /** Exposed for the test that proves this check can actually fail. */
    static boolean isRegistered(Map<String, ? extends FilterRegistration> registrations, Class<? extends Filter> type) {
        return registrations.values().stream().anyMatch(r -> type.getName().equals(r.getClassName()));
    }
}
