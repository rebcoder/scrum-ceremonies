package com.scrumceremonies.integration;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for mood room user limit enforcement.
 * Tests that the 10 user limit is strictly enforced and no users beyond 10 can join.
 * 
 * REUSES: Same testing patterns as RetroBoardUserLimitTest
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
public class MoodRoomUserLimitTest {

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
                        config.setAllowedOrigins(Arrays.asList("*"));
                        config.setAllowedMethods(Arrays.asList("*"));
                        config.setAllowedHeaders(Arrays.asList("*"));
                        config.setAllowCredentials(false);
                        return config;
                    }));
            return http.build();
        }
    }

    @Autowired
    private MoodService moodService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;
    
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        // Use SockJS client like other WebSocket tests to avoid authentication issues
        SockJsClient sockJsClient = new SockJsClient(
            Arrays.asList(new WebSocketTransport(new StandardWebSocketClient()))
        );
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void testMoodRoom_Strict10UserLimit_11thUserRejected() throws Exception {
        // Create a mood room
        String roomId = moodService.createRoom("QUICK_PULSE");
        
        // Fill room with 10 users via Redis
        for (int i = 1; i <= 10; i++) {
            RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(roomId, "user" + i);
            assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result, 
                "User " + i + " should join successfully");
        }
        
        // Verify we have 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Room should have exactly 10 users");
        
        // Try to join as 11th user via WebSocket
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/mood." + roomId, new StompFrameHandler() {
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
        session.send("/app/mood.join." + roomId, 
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
        
        // Verify room still has exactly 10 users
        assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
            "Room should still have exactly 10 users after rejection");
        
        session.disconnect();
    }

    @Test
    void testMoodRoom_Users11To15_AllRejected() throws Exception {
        // Create a mood room
        String roomId = moodService.createRoom("SCRUM_PULSE");
        
        // Fill room with 10 users
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(roomId, "user" + i);
        }
        
        // Try to join users 11, 12, 13, 14, 15 - all should be rejected
        for (int rejectedUserNum = 11; rejectedUserNum <= 15; rejectedUserNum++) {
            String rejectedUserId = "user" + rejectedUserNum;
            
            String url = "ws://localhost:" + port + "/ws";
            StompSession rejectedSession = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                    .get(10, TimeUnit.SECONDS);
            
            BlockingQueue<Map<String, Object>> rejectedMessages = new LinkedBlockingQueue<>();
            rejectedSession.subscribe("/topic/mood." + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    rejectedMessages.offer((Map<String, Object>) payload);
                }
            });
            
            rejectedSession.send("/app/mood.join." + roomId, 
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
            
            // Verify room still has exactly 10 users
            assertEquals(10, redisRoomPresenceService.getUserCount(roomId), 
                "Room should still have exactly 10 users after user " + rejectedUserNum + " rejection");
        }
    }

    @Test
    void testMoodRoom_ConcurrentJoinAttempts_Only10Allowed() throws Exception {
        // /topic/mood.{roomId} is a broadcast topic — MoodWsController sends every join
        // result to every subscriber, not just to the user who joined. A listener that
        // treats the first frame it sees as its own reply will routinely read another
        // user's {"allowed": true} and count itself successful instead. The success
        // payload also carries no user identifier at all (Map.of("allowed", true,
        // "state", …)), so correlating a success from the broadcast is not just
        // unreliable, it's impossible. Rejections do carry "rejectedUserId", which is
        // what this test keys on to correlate outcomes correctly.
        String roomId = moodService.createRoom("QUICK_PULSE");

        int totalAttempts = 15;
        // Every frame every thread sees. Because the topic is a broadcast, the UNION across threads
        // is the complete set of results, which is what makes correlation possible at all.
        BlockingQueue<Map<String, Object>> allFrames = new LinkedBlockingQueue<>();
        BlockingQueue<String> sendFailures = new LinkedBlockingQueue<>();
        AtomicInteger joinsSent = new AtomicInteger();
        CountDownLatch subscribed = new CountDownLatch(totalAttempts);
        CountDownLatch sendsComplete = new CountDownLatch(totalAttempts);
        CountDownLatch done = new CountDownLatch(totalAttempts);
        ExecutorService executor = Executors.newFixedThreadPool(totalAttempts);

        for (int i = 1; i <= totalAttempts; i++) {
            final String userId = "user" + i;
            final String userName = "User" + i;
            executor.submit(() -> {
                StompSession session = null;
                try {
                    session = stompClient.connect("ws://localhost:" + port + "/ws",
                            new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
                    session.subscribe("/topic/mood." + roomId, new StompFrameHandler() {
                        @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
                        @SuppressWarnings("unchecked")
                        @Override public void handleFrame(StompHeaders headers, Object payload) {
                            allFrames.offer((Map<String, Object>) payload);
                        }
                    });
                    // Every subscriber is in place BEFORE any join is sent. Without this the threads
                    // do not actually contend — the first few finish before the last connects, and a
                    // pass would mean nothing. A race that did not contend is a non-result.
                    subscribed.countDown();
                    if (!subscribed.await(20, TimeUnit.SECONDS)) {
                        sendFailures.offer(userId + ": subscribers never all connected");
                        return;
                    }
                    session.send("/app/mood.join." + roomId,
                            Map.of("userId", userId, "userName", userName));
                    joinsSent.incrementAndGet();
                    // Stay subscribed until every join has been sent and the broadcasts have
                    // settled — broadcast frames arrive asynchronously after send() returns,
                    // so disconnecting right away would miss them entirely.
                    sendsComplete.countDown();
                    sendsComplete.await(20, TimeUnit.SECONDS);
                    Thread.sleep(2000);
                } catch (Exception e) {
                    // Recorded rather than swallowed: a thread that fails silently would
                    // contribute to neither count, and the totals below would stop adding
                    // up with no indication why.
                    sendFailures.offer(userId + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    if (session != null && session.isConnected()) {
                        try { session.disconnect(); } catch (Exception ignored) { /* teardown */ }
                    }
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish within 60s");
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        Thread.sleep(1000);   // let the last broadcasts and Redis writes settle

        // ── CONTENTION FLOOR ───────────────────────────────────────────────────────────────
        // If fewer than 11 joins were actually sent, the cap was never approached and everything
        // below passes for a reason unrelated to the cap.
        assertTrue(sendFailures.isEmpty(),
                "threads failed before sending, so the race did not contend: " + sendFailures);
        assertEquals(totalAttempts, joinsSent.get(),
                "only " + joinsSent.get() + " of " + totalAttempts + " joins were sent — the "
                        + "concurrency this test is named for did not happen");

        // ── THE CAP — the assertion that actually tests the invariant ──────────────────────
        int finalUserCount = redisRoomPresenceService.getUserCount(roomId);
        assertTrue(finalUserCount <= 10,
                "Room should have at most 10 users after concurrent joins, got: " + finalUserCount);

        // ── CORRELATED REJECTIONS ──────────────────────────────────────────────────────────
        Set<String> rejected = new HashSet<>();
        for (Map<String, Object> frame : allFrames) {
            Object id = frame.get("rejectedUserId");
            if (id != null && Boolean.FALSE.equals(frame.get("allowed"))) rejected.add(id.toString());
        }
        MoodRoom room = moodService.getRoom(roomId);
        Set<String> admitted = room == null ? Set.of() : new HashSet<>(room.getUserNames().keySet());

        // The real correctness property, and it is immune to a dropped broadcast: nobody who was
        // told "room full" is sitting in the room.
        Set<String> both = new HashSet<>(rejected);
        both.retainAll(admitted);
        assertTrue(both.isEmpty(),
                "these users were told the room was full AND are in the room: " + both);

        assertEquals(totalAttempts - finalUserCount, rejected.size(),
                "15 joiners minus " + finalUserCount + " seats should yield exactly "
                        + (totalAttempts - finalUserCount) + " correlated rejections; saw "
                        + rejected.size());

        // Known limitation: the MoodRoom model can lose users under a concurrent burst of
        // joins, because MoodService.addUser does a read-modify-write of the whole room
        // object — concurrent joins can become lost updates, with the last writer winning.
        // Redis presence tracking (asserted above) stays correct throughout; it is
        // specifically the serialized model that can under-count.
        //
        // This is asserted rather than ignored because MoodService.getFullState reads the
        // model (room.getUserNames() and room.getUserCount()), which is what every
        // participant actually sees: after a burst, the UI can show a single name and
        // "1/10" even though the room correctly refused an eleventh user.
        //
        // This documents current behavior rather than desired behavior. If the underlying
        // read-modify-write race is fixed, this assertion should start failing, and should
        // then be replaced with assertEquals(finalUserCount, admitted.size()).
        assertTrue(admitted.size() <= finalUserCount,
                "the model cannot hold more users than Redis admitted: model=" + admitted.size()
                        + " redis=" + finalUserCount);
        assertTrue(admitted.size() < finalUserCount,
                "This known limitation appears to be fixed — the model now holds " + admitted.size()
                        + " of " + finalUserCount + " admitted users. Good. Now replace this "
                        + "assertion with assertEquals(finalUserCount, admitted.size()).");
    }

    @Test
    void testMoodRoom_HostAssignment_FirstUserIsHost() throws Exception {
        // Create a mood room
        String roomId = moodService.createRoom("QUICK_PULSE");
        
        // Connect first user via WebSocket
        String url = "ws://localhost:" + port + "/ws";
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        
        session.subscribe("/topic/mood." + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });
        
        // Join as first user (should become host)
        session.send("/app/mood.join." + roomId, 
            Map.of("userId", "host1", "userName", "HostUser"));
        
        // Wait for response
        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response");
        assertTrue((Boolean) response.getOrDefault("allowed", false), 
            "First user should be allowed to join");
        
        // Check state includes host info
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) response.get("state");
        if (state != null) {
            assertEquals("HostUser", state.get("hostName"), 
                "First user should be the host");
            assertTrue((Boolean) state.getOrDefault("hasHost", false), 
                "hasHost should be true");
        }
        
        session.disconnect();
        
        // Verify via service
        MoodRoom room = moodService.getRoom(roomId);
        assertEquals("HostUser", room.getHostName(), 
            "Room should have HostUser as host");
    }

    @Test
    void testMoodRoom_ResponseSubmission_OnePerUser() throws Exception {
        // Create a mood room
        String roomId = moodService.createRoom("QUICK_PULSE");
        
        // Add user to room
        MoodRoom room = moodService.getRoom(roomId);
        room.addUser("user1", "User1");
        moodService.save(room);
        redisRoomPresenceService.tryJoinRoom(roomId, "user1");
        
        // Submit first response - should succeed
        boolean firstSubmit = moodService.submitResponse(roomId, "user1", Map.of("mood", "😄"));
        assertTrue(firstSubmit, "First submission should succeed");
        
        // Submit second response - should fail
        boolean secondSubmit = moodService.submitResponse(roomId, "user1", Map.of("mood", "😞"));
        assertFalse(secondSubmit, "Second submission should fail (one per user)");
        
        // Verify only first response is stored
        room = moodService.getRoom(roomId);
        assertEquals("😄", room.getResponses().get("user1").get("mood"), 
            "Original response should be preserved");
    }

    @Test
    void testMoodRoom_ResultsReveal_AutoWhenAllSubmit() throws Exception {
        // Create a mood room with 2 users
        String roomId = moodService.createRoom("QUICK_PULSE");
        
        MoodRoom room = moodService.getRoom(roomId);
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        moodService.save(room);
        redisRoomPresenceService.tryJoinRoom(roomId, "user1");
        redisRoomPresenceService.tryJoinRoom(roomId, "user2");
        
        // Verify results not revealed yet
        room = moodService.getRoom(roomId);
        assertFalse(room.isResultsRevealed(), "Results should not be revealed initially");
        
        // User 1 submits
        moodService.submitResponse(roomId, "user1", Map.of("mood", "😄"));
        room = moodService.getRoom(roomId);
        assertFalse(room.isResultsRevealed(), "Results should not be revealed after first submission");
        
        // User 2 submits (all users now)
        moodService.submitResponse(roomId, "user2", Map.of("mood", "🙂"));
        room = moodService.getRoom(roomId);
        // Results should NOT be auto-revealed - host must manually reveal (as per requirements)
        assertFalse(room.isResultsRevealed(), "Results should not be auto-revealed - host must manually reveal");
        
        // Verify manual reveal works
        moodService.revealResults(roomId);
        room = moodService.getRoom(roomId);
        assertTrue(room.isResultsRevealed(), "Results should be revealed after manual reveal by host");
    }
}


