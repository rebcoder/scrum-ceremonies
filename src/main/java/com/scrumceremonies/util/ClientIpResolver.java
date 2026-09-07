package com.scrumceremonies.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP from requests, including when behind a proxy
 * (a container platform ingress or a load balancer) that sets X-Forwarded-For.
 * <p>
 * Used for rate limiting and session/room limits so that different clients
 * are correctly distinguished. Uses the rightmost (last) IP in X-Forwarded-For
 * because that is the one appended by the most trusted proxy closest to the server.
 * The leftmost IP can be spoofed by the client.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private static final String FALLBACK_UNKNOWN = "unknown";

    /**
     * Resolves the client IP from the request.
     * <ul>
     *   <li>If X-Forwarded-For is present: use the last (rightmost) IP in the comma-separated list (trimmed).</li>
     *   <li>Otherwise: use {@link HttpServletRequest#getRemoteAddr()}.</li>
     *   <li>If the resolved value is null or empty: fall back to remoteAddr, then "unknown".</li>
     * </ul>
     * Logs the resolved IP at DEBUG level only (not INFO) to avoid PII in production logs.
     *
     * @param request the HTTP request (may be null)
     * @return the resolved client IP, or "unknown" if not determinable
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return FALLBACK_UNKNOWN;
        }
        String remoteAddr = request.getRemoteAddr();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        String resolved;
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            String[] ips = xForwardedFor.split(",");
            // This case is unreachable today but is guarded anyway, since the only thing
            // preventing it is external configuration rather than anything in this method.
            //
            // String.split(",") removes trailing empty strings, so a header of exactly "," or
            // ",," produces a zero-length array, and ips[ips.length - 1] would evaluate ips[-1]
            // and throw ArrayIndexOutOfBoundsException. For example:
            //     ","    -> length=0        "1.2.3.4, unknown" -> length=2
            //     ",,"   -> length=0        "  , 10.1.1.1"     -> length=2
            //
            // This does not happen in practice because `server.forward-headers-strategy=framework`
            // (see application.properties) puts Spring's ForwardedHeaderFilter ahead of this code,
            // and that filter rejects a malformed X-Forwarded-For with a 400 before this method
            // is ever called.
            //
            // That protection rests entirely on that one property, though: set
            // forward-headers-strategy to `none`, or move this resolver ahead of the filter, and a
            // two-character header becomes a 500 on every route that resolves a client IP.
            String last = ips.length == 0 ? "" : ips[ips.length - 1].trim();
            if (!last.isEmpty()) {
                resolved = last;
            } else {
                resolved = remoteAddr != null && !remoteAddr.isEmpty() ? remoteAddr : FALLBACK_UNKNOWN;
            }
        } else {
            resolved = remoteAddr != null && !remoteAddr.isEmpty() ? remoteAddr : FALLBACK_UNKNOWN;
        }
        if (resolved == null || resolved.isEmpty()) {
            resolved = FALLBACK_UNKNOWN;
        }
        if (log.isDebugEnabled()) {
            log.debug("Resolved client IP: {}", resolved);
        }
        return resolved;
    }
}
