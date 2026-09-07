package com.scrumceremonies.config;

import com.scrumceremonies.service.VisitorAnalyticsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP filter to track visits for analytics.
 * 
 * Privacy-safe: NO cookies, NO personal data stored.
 * Tracks visits at the request level for basic analytics.
 * 
 * Filters out:
 * - Static resources (CSS, JS, images)
 * - Actuator endpoints (to avoid polluting analytics)
 * - Health checks
 */
@Component
@Order(1) // Execute early in filter chain
public class VisitorTrackingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(VisitorTrackingFilter.class);
    
    private final VisitorAnalyticsService analyticsService;
    
    @Autowired
    public VisitorTrackingFilter(VisitorAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // Skip tracking for:
        // - Static resources
        // - Actuator endpoints (monitoring)
        // - Favicon
        // - WebSocket endpoint (tracked separately)
        if (shouldSkipTracking(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Track visit (privacy-safe, no personal data)
        try {
            analyticsService.recordVisit();
        } catch (Exception e) {
            // Fail silently - don't break request flow
            log.debug("Failed to record visit: {}", e.getMessage());
        }
        
        filterChain.doFilter(request, response);
    }
    
    private boolean shouldSkipTracking(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        
        // Skip static resources
        if (path.endsWith(".css") || path.endsWith(".js") || 
            path.endsWith(".png") || path.endsWith(".jpg") || 
            path.endsWith(".jpeg") || path.endsWith(".gif") || 
            path.endsWith(".ico") || path.endsWith(".svg") ||
            path.endsWith(".woff") || path.endsWith(".woff2") ||
            path.endsWith(".ttf") || path.endsWith(".eot")) {
            return true;
        }
        
        // Skip actuator endpoints
        if (path.startsWith("/actuator/")) {
            return true;
        }
        
        // Skip WebSocket endpoint (tracked separately via event listener)
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            return true;
        }
        
        // Skip favicon
        if (path.equals("/favicon.ico")) {
            return true;
        }
        
        return false;
    }
}

