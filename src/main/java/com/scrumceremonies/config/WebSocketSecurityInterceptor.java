package com.scrumceremonies.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket security interceptor that enforces:
 * - Payload size limits (64KB max per SEND message)
 * - Per-session message rate limiting (100 messages per 10 seconds)
 */
@Component
public class WebSocketSecurityInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSecurityInterceptor.class);

    private static final int MAX_PAYLOAD_BYTES = 65536; // 64KB
    private static final int MAX_MESSAGES_PER_WINDOW = 100;

    private final ConcurrentHashMap<String, AtomicInteger> sessionMessageCounts = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.SEND.equals(accessor.getCommand())) {
            checkPayloadSize(message);
            checkRateLimit(accessor);
        }

        if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            String sessionId = accessor.getSessionId();
            if (sessionId != null) {
                sessionMessageCounts.remove(sessionId);
            }
        }

        return message;
    }

    private void checkPayloadSize(Message<?> message) {
        Object payload = message.getPayload();
        int size;
        if (payload instanceof byte[]) {
            size = ((byte[]) payload).length;
        } else if (payload instanceof String) {
            size = ((String) payload).length();
        } else {
            return;
        }

        if (size > MAX_PAYLOAD_BYTES) {
            log.warn("WebSocket payload exceeds maximum size: {} bytes (limit: {})", size, MAX_PAYLOAD_BYTES);
            throw new MessageDeliveryException(
                    "Payload size " + size + " bytes exceeds maximum of " + MAX_PAYLOAD_BYTES + " bytes");
        }
    }

    private void checkRateLimit(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        AtomicInteger count = sessionMessageCounts.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();

        if (current > MAX_MESSAGES_PER_WINDOW) {
            log.warn("WebSocket rate limit exceeded for session {}: {} messages in window", sessionId, current);
            throw new MessageDeliveryException("Rate limit exceeded: too many messages");
        }
    }

    /**
     * Resets all per-session message counters every 10 seconds.
     */
    @Scheduled(fixedRate = 10_000)
    public void resetRateLimitCounters() {
        sessionMessageCounts.clear();
    }
}
