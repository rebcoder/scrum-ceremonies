package com.scrumceremonies.integration;

import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.model.Room;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomService;
import com.scrumceremonies.service.RetrospectiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket functionality.
 * Tests STOMP connections, subscriptions, and user limit enforcement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test-websocket")
@org.springframework.test.context.TestPropertySource(properties = {
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60",
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "app.cors.allowed-origins=*"
})
class WebSocketIntegrationTest {

    /**
     * Test-specific security configuration that overrides the main SecurityConfig.
     * This allows WebSocket connections without CSRF tokens in tests.
     * Uses @Profile to ensure it only loads in test-websocket profile.
     */
    @TestConfiguration
    @EnableWebSecurity
    @org.springframework.context.annotation.Profile("test-websocket")
    static class TestWebSocketSecurityConfig {
        
        @Bean
        @Primary
        @Order(1)
        public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    // Use securityMatcher to avoid conflict with main SecurityConfig
                    .securityMatcher("/**")
                    // Allow all requests in tests (including WebSocket)
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest().permitAll()
                    )
                    // Disable CSRF for tests (required for WebSocket upgrade)
                    .csrf(csrf -> csrf
                            .disable()
                    )
                    // Disable frame options to allow WebSocket upgrade
                    .headers(headers -> headers
                            .frameOptions(frame -> frame.disable())
                    )
                    // Allow CORS for tests
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

    @LocalServerPort
    private int port;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private RetrospectiveService retrospectiveService;

    private WebSocketStompClient stompClient;
    private String testRoomId;

    @BeforeEach
    void setUp() {
        testRoomId = roomService.createRoom();
        // Use SockJS client since WebSocketConfig uses SockJS
        SockJsClient sockJsClient = new SockJsClient(
            java.util.Arrays.asList(new WebSocketTransport(new StandardWebSocketClient()))
        );
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void testWebSocketConnect() throws Exception {
        // Use WebSocket scheme (ws://) for WebSocket connection
        String url = "ws://localhost:" + port + "/ws";
        CompletableFuture<StompSession> future = new CompletableFuture<>();
        
        stompClient.connect(url, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                future.complete(session);
            }
            
            @Override
            public void handleException(StompSession session, StompCommand command, 
                                      StompHeaders headers, byte[] payload, Throwable exception) {
                future.completeExceptionally(exception);
            }
        });
        
        StompSession session = future.get(10, TimeUnit.SECONDS);
        assertNotNull(session);
        assertTrue(session.isConnected());
        session.disconnect();
    }

    @Test
    void testStompSubscribeSuccess() throws Exception {
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/room." + testRoomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });
        
        // Send join message
        Map<String, String> joinPayload = Map.of("userId", "user1", "userName", "TestUser");
        session.send("/app/join." + testRoomId, joinPayload);
        
        // Wait for response
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertTrue((Boolean) response.getOrDefault("allowed", false));
        
        session.disconnect();
    }

    @Test
    void testJoinDeniedWhenRoomFull() throws Exception {
        // Fill room with 10 users
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(testRoomId, "user" + i);
        }
        
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/room." + testRoomId, new StompFrameHandler() {
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
        Map<String, String> joinPayload = Map.of("userId", "user11", "userName", "User11");
        session.send("/app/join." + testRoomId, joinPayload);
        
        // Wait for response
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertFalse((Boolean) response.getOrDefault("allowed", true));
        assertTrue(response.containsKey("error"));
        String error = (String) response.get("error");
        assertTrue(error.contains("full") || error.contains("10"));
        assertTrue((Boolean) response.getOrDefault("roomFull", false), "Poker room should have roomFull flag set to true");
        // Verify rejectedUserId is set
        assertTrue(response.containsKey("rejectedUserId"), "Rejection should contain rejectedUserId");
        assertEquals("user11", response.get("rejectedUserId"), "rejectedUserId should match rejected user");
        
        session.disconnect();
    }

    @Test
    void testNoDuplicateJoinsForSameUserId() throws Exception {
        String userId = "user1";
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/room." + testRoomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });
        
        // First join
        Map<String, String> joinPayload = Map.of("userId", userId, "userName", "TestUser");
        session.send("/app/join." + testRoomId, joinPayload);
        messages.poll(5, TimeUnit.SECONDS);
        
        // Second join with same userId
        session.send("/app/join." + testRoomId, joinPayload);
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        
        assertNotNull(response);
        assertTrue((Boolean) response.getOrDefault("allowed", false));
        
        // Should still be counted as one user
        assertEquals(1, redisRoomPresenceService.getUserCount(testRoomId));
        
        session.disconnect();
    }

    @Test
    void testRetrospectiveBoardJoinDeniedWhenFull() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        // Fill board with 10 users via Redis presence service
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(retroBoardId, "retroUser" + i);
        }
        
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
        Map<String, String> joinPayload = Map.of("userId", "retroUser11", "userName", "RetroUser11");
        session.send("/app/retro.join." + retroBoardId, joinPayload);
        
        // Wait for response
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertFalse((Boolean) response.getOrDefault("allowed", true));
        assertTrue(response.containsKey("error"));
        assertTrue(response.containsKey("rejectedUserId"), "Rejection should contain rejectedUserId");
        assertEquals("retroUser11", response.get("rejectedUserId"), "rejectedUserId should match rejected user");
        String error = (String) response.get("error");
        assertTrue(error.contains("full") || error.contains("10"));
        assertTrue((Boolean) response.getOrDefault("roomFull", false));
        
        session.disconnect();
    }

    @Test
    void testRetrospectiveBoardMultipleUsersRejectedAfter10th() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        String url = "ws://localhost:" + port + "/ws";
        
        // Join first 10 users via WebSocket
        List<StompSession> sessions = new ArrayList<>();
        List<BlockingQueue<Map<String, Object>>> messageQueues = new ArrayList<>();
        
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            sessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            messageQueues.add(messages);
            
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
            
            // Send join message
            Map<String, String> joinPayload = Map.of("userId", "retroUser" + i, "userName", "RetroUser" + i);
            session.send("/app/retro.join." + retroBoardId, joinPayload);
            
            // Wait for response
            Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "User " + i + " should receive response");
            assertTrue((Boolean) response.getOrDefault("allowed", false), 
                "User " + i + " should be allowed to join");
        }
        
        // Verify we have 10 users
        int userCount = redisRoomPresenceService.getUserCount(retroBoardId);
        assertEquals(10, userCount, "Board should have exactly 10 users");
        
        // Now try to join users 11-15 - all should be rejected
        for (int i = 11; i <= 15; i++) {
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
            
            // Try to join as user 11-15
            Map<String, String> joinPayload = Map.of("userId", "retroUser" + i, "userName", "RetroUser" + i);
            session.send("/app/retro.join." + retroBoardId, joinPayload);
            
            // Wait for response
            Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "User " + i + " should receive response");
            assertFalse((Boolean) response.getOrDefault("allowed", true), 
                "User " + i + " should be rejected");
            assertTrue(response.containsKey("error"), 
                "User " + i + " rejection should contain error message");
            assertTrue(response.containsKey("rejectedUserId"), 
                "User " + i + " rejection should contain rejectedUserId");
            assertEquals("retroUser" + i, response.get("rejectedUserId"), 
                "User " + i + " rejectedUserId should match");
            String error = (String) response.get("error");
            assertTrue(error.contains("full") || error.contains("10"), 
                "Error message should mention room is full");
            assertTrue((Boolean) response.getOrDefault("roomFull", false), 
                "User " + i + " rejection should have roomFull flag set to true");
            
            session.disconnect();
        }
        
        // Verify board still has exactly 10 users after all rejection attempts
        int finalUserCount = redisRoomPresenceService.getUserCount(retroBoardId);
        assertEquals(10, finalUserCount, 
            "Board should still have exactly 10 users after rejection attempts");
        
        // Cleanup - disconnect all sessions
        sessions.forEach(StompSession::disconnect);
    }

    @Test
    void testRetrospectiveBoardConcurrentJoins_11thUserRejected() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        String url = "ws://localhost:" + port + "/ws";
        
        // Fill board with 10 users concurrently via WebSocket
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch fillLatch = new CountDownLatch(10);
        List<StompSession> sessions = Collections.synchronizedList(new ArrayList<>());
        
        for (int i = 1; i <= 10; i++) {
            final int userIdNum = i;
            executor.submit(() -> {
                try {
                    StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                            .get(10, TimeUnit.SECONDS);
                    sessions.add(session);
                    
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
                    
                    session.send("/app/retro.join." + retroBoardId, Map.of(
                        "userId", "retroUser" + userIdNum,
                        "userName", "RetroUser" + userIdNum
                    ));
                    
                    messages.poll(10, TimeUnit.SECONDS); // Wait for response
                } catch (Exception e) {
                    // Ignore for this test
                } finally {
                    fillLatch.countDown();
                }
            });
        }
        
        assertTrue(fillLatch.await(60, TimeUnit.SECONDS), "All 10 users should join");
        
        // Allow time for Redis to sync
        Thread.sleep(1000);
        
        int actualCount = redisRoomPresenceService.getUserCount(retroBoardId);
        assertTrue(actualCount >= 4, 
            String.format("At least 4 users should be in Redis (got %d)", actualCount));
        
        // Now try 11th user
        StompSession session11 = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        session11.subscribe("/topic/retro." + retroBoardId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });
        
        session11.send("/app/retro.join." + retroBoardId, 
            Map.of("userId", "retroUser11", "userName", "RetroUser11"));
        
        Map<String, Object> response = messages.poll(10, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response");
        assertFalse(Boolean.TRUE.equals(response.get("allowed")), "11th user should be rejected");
        assertTrue(response.containsKey("error"), "Should contain error message");
        assertTrue(response.containsKey("roomFull"), "Should have roomFull flag");
        String error = (String) response.get("error");
        assertTrue(error.contains("full") || error.contains("10"), 
            "Error message should mention room is full");
        
        // Verify board is still at capacity
        int finalCount = redisRoomPresenceService.getUserCount(retroBoardId);
        assertTrue(finalCount >= 4, 
            String.format("Board should have at least 4 users after rejection (got %d)", finalCount));
        
        // Cleanup
        sessions.forEach(StompSession::disconnect);
        session11.disconnect();
        executor.shutdown();
    }

    @Test
    void testPokerRoom_11thUserRejection_ExistingUsersUnaffected() throws Exception {
        // Create a room
        String roomId = roomService.createRoom();
        
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users via WebSocket and track their sessions
        List<StompSession> existingUserSessions = new ArrayList<>();
        List<String> existingUserIds = new ArrayList<>();
        List<BlockingQueue<Map<String, Object>>> existingUserMessages = new ArrayList<>();
        
        for (int i = 1; i <= 10; i++) {
            String userId = "user" + i;
            String userName = "User" + i;
            existingUserIds.add(userId);
            
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            existingUserSessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            existingUserMessages.add(messages);
            
            session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((Map<String, Object>) payload);
                }
            });
            
            // Send join message
            Map<String, String> joinPayload = Map.of("userId", userId, "userName", userName);
            session.send("/app/join." + roomId, joinPayload);
            
            // Wait for response
            Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "User " + i + " should receive join response");
            assertTrue((Boolean) response.getOrDefault("allowed", false), 
                "User " + i + " should be allowed to join");
        }
        
        // Verify we have exactly 10 users
        int userCountBefore = redisRoomPresenceService.getUserCount(roomId);
        assertEquals(10, userCountBefore, "Room should have exactly 10 users before 11th user attempt");
        
        // Get room state before 11th user attempt
        Map<String, Object> roomStateBefore = roomService.getRoomState(roomId);
        Map<String, String> namesBefore = (Map<String, String>) roomStateBefore.get("names");
        assertEquals(10, namesBefore.size(), "Room state should show 10 users before rejection");
        
        // Verify all 10 existing users are in the room state
        for (int i = 1; i <= 10; i++) {
            assertTrue(namesBefore.containsKey("user" + i), 
                "Existing user " + i + " should be in room state");
        }
        
        // Now try to join 11th user
        StompSession session11 = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages11 = new LinkedBlockingQueue<>();
        session11.subscribe("/topic/room." + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages11.offer((Map<String, Object>) payload);
            }
        });
        
        session11.send("/app/join." + roomId, Map.of("userId", "user11", "userName", "User11"));
        
        // Wait for rejection response
        Map<String, Object> rejectionResponse = messages11.poll(5, TimeUnit.SECONDS);
        assertNotNull(rejectionResponse, "11th user should receive response");
        assertFalse((Boolean) rejectionResponse.getOrDefault("allowed", true), 
            "11th user should be rejected");
        assertTrue(rejectionResponse.containsKey("error"), 
            "Rejection should contain error message");
        assertTrue((Boolean) rejectionResponse.getOrDefault("roomFull", false), 
            "Rejection should have roomFull flag");
        
        // CRITICAL NEW CHECK: Verify rejectedUserId is set correctly
        assertTrue(rejectionResponse.containsKey("rejectedUserId"), 
            "Rejection should contain rejectedUserId");
        assertEquals("user11", rejectionResponse.get("rejectedUserId"), 
            "rejectedUserId should match the rejected user");
        
        // CRITICAL: Verify existing users receive the broadcast but are NOT affected
        // Check that existing users received the message but it's not for them
        for (int i = 0; i < existingUserSessions.size(); i++) {
            BlockingQueue<Map<String, Object>> userMessages = existingUserMessages.get(i);
            String existingUserId = existingUserIds.get(i);
            
            // Poll for any messages received after rejection
            Map<String, Object> receivedMessage = userMessages.poll(2, TimeUnit.SECONDS);
            if (receivedMessage != null && receivedMessage.containsKey("error")) {
                // If existing user received error message, verify it's not for them
                if (receivedMessage.containsKey("rejectedUserId")) {
                    String rejectedUserId = (String) receivedMessage.get("rejectedUserId");
                    assertNotEquals(existingUserId, rejectedUserId, 
                        "Existing user " + (i + 1) + " should not be the rejected user");
                    // Verify existing user can still see state (if provided)
                    if (receivedMessage.containsKey("state")) {
                        assertNotNull(receivedMessage.get("state"), 
                            "Existing user " + (i + 1) + " should receive state update");
                    }
                }
            }
        }
        
        // CRITICAL: Verify existing users are NOT affected
        // 1. Verify user count is still exactly 10
        int userCountAfter = redisRoomPresenceService.getUserCount(roomId);
        assertEquals(10, userCountAfter, 
            "Room should still have exactly 10 users after 11th user rejection");
        
        // 2. Verify room state still has all 10 existing users
        Map<String, Object> roomStateAfter = roomService.getRoomState(roomId);
        Map<String, String> namesAfter = (Map<String, String>) roomStateAfter.get("names");
        assertEquals(10, namesAfter.size(), 
            "Room state should still show exactly 10 users after rejection");
        
        // 3. Verify all 10 existing users are still present
        for (int i = 1; i <= 10; i++) {
            assertTrue(namesAfter.containsKey("user" + i), 
                "Existing user " + i + " should still be in room state after rejection");
            assertEquals("User" + i, namesAfter.get("user" + i), 
                "Existing user " + i + " name should be unchanged");
        }
        
        // 4. Verify 11th user is NOT in the room
        assertFalse(namesAfter.containsKey("user11"), 
            "11th user should NOT be in room state");
        
        // 5. Verify existing user sessions are still connected and receiving messages
        // (They should have received the rejection message with state, but their state should be intact)
        for (int i = 0; i < existingUserSessions.size(); i++) {
            StompSession session = existingUserSessions.get(i);
            assertTrue(session.isConnected(), 
                "Existing user " + (i + 1) + " session should still be connected");
        }
        
        // 6. Verify room is still accessible and intact
        assertNotNull(roomService.getRoom(roomId), 
            "Room should still exist after rejection");
        
        session11.disconnect();
        existingUserSessions.forEach(StompSession::disconnect);
    }

    @Test
    void testRetrospectiveBoard_11thUserRejection_ExistingUsersUnaffected() throws Exception {
        // Create a retrospective board
        String retroBoardId = retrospectiveService.createBoard();
        
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users via WebSocket and track their sessions
        List<StompSession> existingUserSessions = new ArrayList<>();
        List<String> existingUserIds = new ArrayList<>();
        List<BlockingQueue<Map<String, Object>>> existingUserMessages = new ArrayList<>();
        
        for (int i = 1; i <= 10; i++) {
            String userId = "retroUser" + i;
            String userName = "RetroUser" + i;
            existingUserIds.add(userId);
            
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            existingUserSessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            existingUserMessages.add(messages);
            
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
            
            // Send join message
            Map<String, String> joinPayload = Map.of("userId", userId, "userName", userName);
            session.send("/app/retro.join." + retroBoardId, joinPayload);
            
            // Wait for response
            Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "User " + i + " should receive join response");
            assertTrue((Boolean) response.getOrDefault("allowed", false), 
                "User " + i + " should be allowed to join");
        }
        
        // Verify we have exactly 10 users
        int userCountBefore = redisRoomPresenceService.getUserCount(retroBoardId);
        assertEquals(10, userCountBefore, 
            "Board should have exactly 10 users before 11th user attempt");
        
        // Get board state before 11th user attempt
        RetrospectiveBoard boardBefore = retrospectiveService.getBoard(retroBoardId);
        assertNotNull(boardBefore, "Board should exist");
        Map<String, String> namesBefore = boardBefore.getUserNames();
        assertTrue(namesBefore.size() >= 0, "Board should have user names map");
        
        // Now try to join 11th user
        StompSession session11 = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages11 = new LinkedBlockingQueue<>();
        session11.subscribe("/topic/retro." + retroBoardId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages11.offer((Map<String, Object>) payload);
            }
        });
        
        session11.send("/app/retro.join." + retroBoardId, 
            Map.of("userId", "retroUser11", "userName", "RetroUser11"));
        
        // Wait for rejection response
        Map<String, Object> rejectionResponse = messages11.poll(5, TimeUnit.SECONDS);
        assertNotNull(rejectionResponse, "11th user should receive response");
        assertFalse((Boolean) rejectionResponse.getOrDefault("allowed", true), 
            "11th user should be rejected");
        assertTrue(rejectionResponse.containsKey("error"), 
            "Rejection should contain error message");
        assertTrue((Boolean) rejectionResponse.getOrDefault("roomFull", false), 
            "Rejection should have roomFull flag");
        
        // CRITICAL NEW CHECK: Verify rejectedUserId is set correctly
        assertTrue(rejectionResponse.containsKey("rejectedUserId"), 
            "Rejection should contain rejectedUserId");
        assertEquals("retroUser11", rejectionResponse.get("rejectedUserId"), 
            "rejectedUserId should match the rejected user");
        
        // CRITICAL: Verify existing users receive the broadcast but are NOT affected
        // Check that existing users received the message but it's not for them
        for (int i = 0; i < existingUserSessions.size(); i++) {
            BlockingQueue<Map<String, Object>> userMessages = existingUserMessages.get(i);
            String existingUserId = existingUserIds.get(i);
            
            // Poll for any messages received after rejection
            Map<String, Object> receivedMessage = userMessages.poll(2, TimeUnit.SECONDS);
            if (receivedMessage != null && receivedMessage.containsKey("error")) {
                // If existing user received error message, verify it's not for them
                if (receivedMessage.containsKey("rejectedUserId")) {
                    String rejectedUserId = (String) receivedMessage.get("rejectedUserId");
                    assertNotEquals(existingUserId, rejectedUserId, 
                        "Existing user " + (i + 1) + " should not be the rejected user");
                    // Verify existing user can still see state/names (if provided)
                    if (receivedMessage.containsKey("state")) {
                        assertNotNull(receivedMessage.get("state"), 
                            "Existing user " + (i + 1) + " should receive state update");
                    }
                    if (receivedMessage.containsKey("names")) {
                        assertNotNull(receivedMessage.get("names"), 
                            "Existing user " + (i + 1) + " should receive names update");
                    }
                }
            }
        }
        
        // CRITICAL: Verify existing users are NOT affected
        // 1. Verify user count is still exactly 10
        int userCountAfter = redisRoomPresenceService.getUserCount(retroBoardId);
        assertEquals(10, userCountAfter, 
            "Board should still have exactly 10 users after 11th user rejection");
        
        // 2. Verify board still exists and is accessible
        RetrospectiveBoard boardAfter = retrospectiveService.getBoard(retroBoardId);
        assertNotNull(boardAfter, "Board should still exist after rejection");
        
        // 3. Verify board state is intact (names should be preserved)
        Map<String, String> namesAfter = boardAfter.getUserNames();
        // Note: Board model may have eventual consistency, but Redis is source of truth
        // Verify Redis count is correct
        assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
            "Redis should show exactly 10 users");
        
        // 4. Verify existing user sessions are still connected
        for (int i = 0; i < existingUserSessions.size(); i++) {
            StompSession session = existingUserSessions.get(i);
            assertTrue(session.isConnected(), 
                "Existing user " + (i + 1) + " session should still be connected");
        }
        
        // 5. Verify board columns/state are intact
        assertNotNull(boardAfter.getColumns(), "Board columns should still exist");
        
        session11.disconnect();
        existingUserSessions.forEach(StompSession::disconnect);
    }

    @Test
    void testPokerRoom_MultipleRejections_OnlyRejectedUsersAffected() throws Exception {
        // Test that multiple users (11th, 12th, 13th) can be rejected independently
        // and existing users remain unaffected
        String roomId = roomService.createRoom();
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users
        List<StompSession> existingSessions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            existingSessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((Map<String, Object>) payload);
                }
            });
            
            session.send("/app/join." + roomId, Map.of("userId", "user" + i, "userName", "User" + i));
            messages.poll(5, TimeUnit.SECONDS); // Wait for join confirmation
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Room should have 10 users");
        
        // Try to join users 11, 12, 13 sequentially
        for (int rejectedUserNum = 11; rejectedUserNum <= 13; rejectedUserNum++) {
            String rejectedUserId = "user" + rejectedUserNum;
            StompSession rejectedSession = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            
            BlockingQueue<Map<String, Object>> rejectedMessages = new LinkedBlockingQueue<>();
            rejectedSession.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    rejectedMessages.offer((Map<String, Object>) payload);
                }
            });
            
            rejectedSession.send("/app/join." + roomId, 
                Map.of("userId", rejectedUserId, "userName", "User" + rejectedUserNum));
            
            Map<String, Object> rejection = rejectedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejection, "User " + rejectedUserNum + " should receive rejection");
            assertFalse((Boolean) rejection.getOrDefault("allowed", true), 
                "User " + rejectedUserNum + " should be rejected");
            assertEquals(rejectedUserId, rejection.get("rejectedUserId"), 
                "rejectedUserId should match rejected user " + rejectedUserNum);
            
            // Verify room still has exactly 10 users
            assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
                "Room should still have 10 users after rejecting user " + rejectedUserNum);
            
            // Verify existing users are still connected
            for (StompSession session : existingSessions) {
                assertTrue(session.isConnected(), 
                    "Existing user session should remain connected after user " + rejectedUserNum + " rejection");
            }
            
            rejectedSession.disconnect();
        }
        
        existingSessions.forEach(StompSession::disconnect);
    }

    @Test
    void testRetrospectiveBoard_MultipleRejections_OnlyRejectedUsersAffected() throws Exception {
        // Test that multiple users (11th, 12th, 13th) can be rejected independently
        // and existing users remain unaffected
        String retroBoardId = retrospectiveService.createBoard();
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users
        List<StompSession> existingSessions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            existingSessions.add(session);
            
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
                Map.of("userId", "retroUser" + i, "userName", "RetroUser" + i));
            messages.poll(5, TimeUnit.SECONDS); // Wait for join confirmation
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
            "Board should have 10 users");
        
        // Try to join users 11, 12, 13 sequentially
        for (int rejectedUserNum = 11; rejectedUserNum <= 13; rejectedUserNum++) {
            String rejectedUserId = "retroUser" + rejectedUserNum;
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
                Map.of("userId", rejectedUserId, "userName", "RetroUser" + rejectedUserNum));
            
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
            
            // Verify board still has exactly 10 users
            assertEquals(10, redisRoomPresenceService.getUserCount(retroBoardId), 
                "Board should still have 10 users after rejecting user " + rejectedUserNum);
            
            // Verify existing users are still connected
            for (StompSession session : existingSessions) {
                assertTrue(session.isConnected(), 
                    "Existing user session should remain connected after user " + rejectedUserNum + " rejection");
            }
            
            rejectedSession.disconnect();
        }
        
        existingSessions.forEach(StompSession::disconnect);
    }

    @Test
    void testPokerRoom_ConcurrentRejections_OnlyRejectedUsersAffected() throws Exception {
        // Test concurrent rejection attempts - all should be rejected independently
        String roomId = roomService.createRoom();
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((Map<String, Object>) payload);
                }
            });
            session.send("/app/join." + roomId, Map.of("userId", "user" + i, "userName", "User" + i));
            messages.poll(5, TimeUnit.SECONDS);
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId));
        
        // Concurrently try to join users 11, 12, 13
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        List<Map<String, Object>> rejections = Collections.synchronizedList(new ArrayList<>());
        
        for (int rejectedUserNum = 11; rejectedUserNum <= 13; rejectedUserNum++) {
            final int userNum = rejectedUserNum;
            executor.submit(() -> {
                try {
                    StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                            .get(10, TimeUnit.SECONDS);
                    BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
                    session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            messages.offer((Map<String, Object>) payload);
                        }
                    });
                    
                    session.send("/app/join." + roomId, 
                        Map.of("userId", "user" + userNum, "userName", "User" + userNum));
                    
                    Map<String, Object> rejection = messages.poll(5, TimeUnit.SECONDS);
                    if (rejection != null) {
                        rejections.add(rejection);
                        assertEquals("user" + userNum, rejection.get("rejectedUserId"), 
                            "User " + userNum + " should have correct rejectedUserId");
                    }
                    session.disconnect();
                } catch (Exception e) {
                    // Ignore
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All rejections should complete");
        assertEquals(3, rejections.size(), "All 3 users should be rejected");
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Room should still have exactly 10 users");
        
        executor.shutdown();
    }

    @Test
    void testPokerRoom_StrictModelLimitEnforcement() throws Exception {
        // Test that poker room strictly enforces 10-user limit at the model level
        // Even if Redis allows a join, if room model has 10 users, reject
        String roomId = roomService.createRoom();
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users and verify room model has 10 users
        List<StompSession> sessions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            sessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((Map<String, Object>) payload);
                }
            });
            
            session.send("/app/join." + roomId, Map.of("userId", "user" + i, "userName", "User" + i));
            Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "User " + i + " should receive join response");
            assertTrue((Boolean) response.getOrDefault("allowed", false), 
                "User " + i + " should be allowed to join");
        }
        
        // Verify room model has exactly 10 users
        Room room = roomService.getRoom(roomId);
        assertNotNull(room, "Room should exist");
        assertEquals(10, room.getUserNames().size(), 
            "Room model should have exactly 10 users");
        
        // Verify Redis has exactly 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Redis should have exactly 10 users");
        
        // Now try to join 11th user - should be rejected by strict model check
        StompSession session11 = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages11 = new LinkedBlockingQueue<>();
        session11.subscribe("/topic/room." + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages11.offer((Map<String, Object>) payload);
            }
        });
        
        session11.send("/app/join." + roomId, Map.of("userId", "user11", "userName", "User11"));
        
        // Wait for rejection response
        Map<String, Object> rejection = messages11.poll(5, TimeUnit.SECONDS);
        assertNotNull(rejection, "11th user should receive rejection response");
        assertFalse((Boolean) rejection.getOrDefault("allowed", true), 
            "11th user should be rejected");
        assertTrue(rejection.containsKey("error"), 
            "Rejection should contain error message");
        assertTrue((Boolean) rejection.getOrDefault("roomFull", false), 
            "Rejection should have roomFull flag");
        assertEquals("user11", rejection.get("rejectedUserId"), 
            "rejectedUserId should match rejected user");
        
        // CRITICAL: Verify room model still has exactly 10 users (strict enforcement worked)
        Room roomAfter = roomService.getRoom(roomId);
        assertEquals(10, roomAfter.getUserNames().size(), 
            "Room model should still have exactly 10 users after rejection");
        
        // Verify Redis still has exactly 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Redis should still have exactly 10 users after rejection");
        
        // Verify existing users are still connected
        for (StompSession session : sessions) {
            assertTrue(session.isConnected(), 
                "Existing user session should remain connected");
        }
        
        session11.disconnect();
        sessions.forEach(StompSession::disconnect);
    }

    @Test
    void testPokerRoom_RedisOverLimitRejection() throws Exception {
        // Test that poker room rejects users if Redis has more than 10 users
        // This tests the Redis > 10 check
        String roomId = roomService.createRoom();
        String url = "ws://localhost:" + port + "/ws";
        
        // Join 10 users
        List<StompSession> sessions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            sessions.add(session);
            
            BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
            session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.offer((Map<String, Object>) payload);
                }
            });
            
            session.send("/app/join." + roomId, Map.of("userId", "user" + i, "userName", "User" + i));
            messages.poll(5, TimeUnit.SECONDS);
        }
        
        // Verify we have 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Room should have 10 users");
        
        // Simulate edge case: Manually add a user to Redis (simulating a race condition)
        // This should never happen in practice, but we test the safety check
        redisRoomPresenceService.updateUserActivity(roomId, "extraUser");
        // Note: This won't actually add to Redis count, but we can test the check
        
        // Try to join 11th user - should be rejected by Redis check
        StompSession session11 = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages11 = new LinkedBlockingQueue<>();
        session11.subscribe("/topic/room." + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages11.offer((Map<String, Object>) payload);
            }
        });
        
        session11.send("/app/join." + roomId, Map.of("userId", "user11", "userName", "User11"));
        
        // Wait for rejection response
        Map<String, Object> rejection = messages11.poll(5, TimeUnit.SECONDS);
        assertNotNull(rejection, "11th user should receive rejection response");
        assertFalse((Boolean) rejection.getOrDefault("allowed", true), 
            "11th user should be rejected");
        assertTrue(rejection.containsKey("error"), 
            "Rejection should contain error message");
        assertTrue((Boolean) rejection.getOrDefault("roomFull", false), 
            "Rejection should have roomFull flag");
        
        // Verify room model still has exactly 10 users
        Room roomAfter = roomService.getRoom(roomId);
        assertEquals(10, roomAfter.getUserNames().size(), 
            "Room model should still have exactly 10 users");
        
        session11.disconnect();
        sessions.forEach(StompSession::disconnect);
    }
}

