package com.scrumceremonies.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to add X-Robots-Tag header to actuator endpoints to prevent search engine indexing.
 * 
 * This filter adds "X-Robots-Tag: noindex" header to all actuator endpoints,
 * which tells search engines not to index these pages.
 * 
 * Combined with robots.txt Disallow: /actuator/, this provides double protection
 * against search engines accessing internal monitoring endpoints.
 */
@Component
@Order(0) // Execute early, before other filters
public class ActuatorRobotsFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // Add X-Robots-Tag header for actuator endpoints
        if (path != null && path.startsWith("/actuator/")) {
            response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet");
        }
        
        filterChain.doFilter(request, response);
    }
}
