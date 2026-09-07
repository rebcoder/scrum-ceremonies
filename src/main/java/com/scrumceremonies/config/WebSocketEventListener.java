package com.scrumceremonies.config;

import com.scrumceremonies.service.VisitorAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocket event listener to track connection/disconnection events
 * for visitor analytics (privacy-safe, no personal data collected)
 */
@Component
public class WebSocketEventListener {
    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    
    private final VisitorAnalyticsService analyticsService;
    
    @Autowired
    public WebSocketEventListener(VisitorAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }
    
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WebSocket connection established: sessionId={}", headerAccessor.getSessionId());
        analyticsService.incrementWebSocketConnection();
    }
    
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WebSocket connection closed: sessionId={}", headerAccessor.getSessionId());
        analyticsService.decrementWebSocketConnection();
    }
}

