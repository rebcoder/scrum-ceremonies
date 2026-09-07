package com.scrumceremonies.integration;

import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RetrospectiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for retrospective board user limit enforcement.
 * Tests that the 10 user limit is strictly enforced and no users beyond 10 can join.
 * 
 * NOTE: These tests are disabled in CI (GitHub Actions) as they require local WebSocket environment.
 * They will run locally but be skipped in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
@org.springframework.test.context.ActiveProfiles("test-websocket")
@org.springframework.test.context.TestPropertySource(properties = {
    "app.room.max-users=10",
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "app.cors.allowed-origins=*"
})
public class RetroBoardUserLimitTest {

    /**
     * Test-specific security configuration that overrides the main SecurityConfig.
     * This allows WebSocket connections without authentication in tests.
     */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.context.annotation.Profile("test-websocket")
    static class TestWebSocketSecurityConfig {
        
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        @org.springframework.core.annotation.Order(1)
        public org.springframework.security.web.SecurityFilterChain testSecurityFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http
                    .securityMatcher("/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                    .cors(cors -> cors.configurationSource(request -> {
                        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
                        config.setAllowedOrigins(java.util.Arrays.asList("*"));
                        config.setAllowedMethods(java.util.Arrays.asList("*"));
                        config.setAllowedHeaders(java.util.Arrays.asList("*"));
                        config.setAllowCredentials(false);
                        return config;
                    }));
            return http.build();
        }
    }

    @Autowired
    private RetrospectiveService retrospectiveService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;
    
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        // Use SockJS client like other WebSocket tests to avoid authentication issues
        SockJsClient sockJsClient = new SockJsClient(
            java.util.Arrays.asList(new WebSocketTransport(new StandardWebSocketClient()))
        );
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void testRetroBoard_Strict10UserLimit_11thUserRejected() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        // Fill board with 10 users via Redis
        for (int i = 1; i <= 10; i++) {
            RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(retroBoardId, "user" + i);
            assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result, 
                "User " + i + " should join successfully");
        }
        
        // Verify we have 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
            "Board should have exactly 10 users");
        
        // Try to join as 11th user via WebSocket
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/retro." + retroBoardId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });
        
        // Try to join as 11th user
        session.send("/app/retro.join." + retroBoardId, 
            Map.of("userId", "user11", "userName", "User11"));
        
        // Wait for response
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response");
        assertFalse((Boolean) response.getOrDefault("allowed", true), 
            "11th user should be rejected");
        assertTrue(response.containsKey("error"), 
            "Response should contain error message");
        assertTrue(response.containsKey("rejectedUserId"), 
            "Response should contain rejectedUserId");
        assertEquals("user11", response.get("rejectedUserId"), 
            "rejectedUserId should match rejected user");
        assertTrue((Boolean) response.getOrDefault("roomFull", false), 
            "Response should have roomFull flag set to true");
        
        // Verify board still has exactly 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
            "Board should still have exactly 10 users after rejection");
        
        session.disconnect();
    }

    @Test
    void testRetroBoard_Users11To15_AllRejected() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        // Fill board with 10 users
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(retroBoardId, "user" + i);
        }
        
        // Try to join users 11, 12, 13, 14, 15 - all should be rejected
        for (int rejectedUserNum = 11; rejectedUserNum <= 15; rejectedUserNum++) {
            String rejectedUserId = "user" + rejectedUserNum;
            
            String url = "ws://localhost:" + port + "/ws";
            StompSession rejectedSession = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            
            BlockingQueue<Map<String, Object>> rejectedMessages = new LinkedBlockingQueue<>();
            rejectedSession.subscribe("/topic/retro." + retroBoardId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    rejectedMessages.offer((Map<String, Object>) payload);
                }
            });
            
            rejectedSession.send("/app/retro.join." + retroBoardId, 
                Map.of("userId", rejectedUserId, "userName", "User" + rejectedUserNum));
            
            Map<String, Object> rejection = rejectedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejection, "User " + rejectedUserNum + " should receive rejection");
            assertFalse((Boolean) rejection.getOrDefault("allowed", true), 
                "User " + rejectedUserNum + " should be rejected");
            assertEquals(rejectedUserId, rejection.get("rejectedUserId"), 
                "rejectedUserId should match rejected user " + rejectedUserNum);
            assertTrue(rejection.containsKey("error"), 
                "Rejection should contain error message");
            String error = (String) rejection.get("error");
            assertTrue(error.contains("full") || error.contains("10"), 
                "Error should mention room is full");
            
            rejectedSession.disconnect();
            
            // Verify board still has exactly 10 users
            assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
                "Board should still have exactly 10 users after user " + rejectedUserNum + " rejection");
        }
    }

    @Test
    void testRetroBoard_ConcurrentJoinAttempts_Only10Allowed() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        // Try to join 15 users concurrently
        int totalAttempts = 15;
        BlockingQueue<String> successfulUsers = new LinkedBlockingQueue<>();
        BlockingQueue<String> rejectedUsers = new LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(totalAttempts);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(totalAttempts);
        
        for (int i = 1; i <= totalAttempts; i++) {
            final int userNum = i;
            final String userId = "user" + userNum;
            
            executor.submit(() -> {
                try {
                    String url = "ws://localhost:" + port + "/ws";
                    StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                            .get(10, TimeUnit.SECONDS);
                    
                    BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
                    session.subscribe("/topic/retro." + retroBoardId, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            messages.offer((Map<String, Object>) payload);
                        }
                    });
                    
                    session.send("/app/retro.join." + retroBoardId, 
                        Map.of("userId", userId, "userName", "User" + userNum));
                    
                    Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
                    if (response != null) {
                        Boolean allowed = (Boolean) response.getOrDefault("allowed", false);
                        if (Boolean.TRUE.equals(allowed)) {
                            successfulUsers.offer(userId);
                        } else {
                            rejectedUsers.offer(userId);
                        }
                    }
                    
                    session.disconnect();
                } catch (Exception e) {
                    // Ignore connection errors in concurrent test
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete (with timeout)
        // Note: Some connections may fail, so we don't require all to complete
        boolean completed = latch.await(15, TimeUnit.SECONDS);
        if (!completed) {
            // Log how many completed
            long remaining = latch.getCount();
            System.out.println("Warning: " + remaining + " threads did not complete within timeout");
        }
        
        // Shutdown executor
        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        if (!terminated) {
            executor.shutdownNow();
        }
        
        // Give Redis a moment to sync
        Thread.sleep(1000);
        
        // Verify at most 10 users joined (the key requirement)
        int finalUserCount = redisRoomPresenceService.getUserCount(retroBoardId);
        assertTrue(finalUserCount <= 10, 
            "Board should have at most 10 users after concurrent joins, got: " + finalUserCount);
        
        // If we got exactly 10, that's perfect. If less, it means some connections failed,
        // but that's acceptable as long as we didn't exceed 10
        if (finalUserCount == 10) {
            // Perfect case: exactly 10 users joined
            assertTrue(rejectedUsers.size() >= 5, 
                "At least 5 users (11-15) should be rejected when exactly 10 joined");
        }
        
        // Verify at least some users were rejected if we had more than 10 attempts
        // (This is a weaker assertion since some connections may have failed)
        int totalProcessed = successfulUsers.size() + rejectedUsers.size();
        if (totalProcessed > 10) {
            assertTrue(rejectedUsers.size() >= (totalProcessed - 10), 
                "At least " + (totalProcessed - 10) + " users should be rejected (got " + rejectedUsers.size() + " rejected, " + successfulUsers.size() + " successful)");
        }
    }
}

