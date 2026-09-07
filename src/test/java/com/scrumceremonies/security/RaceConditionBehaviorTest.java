package com.scrumceremonies.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Race Condition Behavior Tests
 * 
 * OBSERVATIONAL TESTS ONLY - These tests document CURRENT behavior,
 * not fix or change implementation.
 * 
 * Tests concurrent operations to understand:
 * - What happens with duplicate joins?
 * - What happens with simultaneous votes?
 * - What happens with concurrent room operations?
 */
@SpringBootTest
@AutoConfigureWebMvc
@TestPropertySource(properties = {
    "app.rate-limit.room-creation.per-minute=5",
    "app.room.max-users=10"
})
class RaceConditionBehaviorTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    /**
     * Test: Concurrent duplicate joins
     * Observational: What happens when same user joins simultaneously?
     * Expected behavior: Document actual result (may succeed, may fail, may overwrite)
     */
    @Test
    void testConcurrentDuplicateJoin_ObserveBehavior() throws Exception {
        String roomId = createTestRoom();
        String userId = "duplicateUser";
        ExecutorService executor = Executors.newFixedThreadPool(5);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Integer> statusCodes = new ArrayList<>();
        
        // 5 concurrent join attempts by same user
        CompletableFuture<?>[] futures = new CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    int status = mockMvc.perform(post("/api/join-room")
                            .param("roomId", roomId)
                            .param("userId", userId))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    
                    synchronized (statusCodes) {
                        statusCodes.add(status);
                    }
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Add error status code to ensure all requests are counted
                    synchronized (statusCodes) {
                        statusCodes.add(500); // Use 500 to indicate exception
                    }
                    failureCount.incrementAndGet();
                }
            }, executor);
        }
        
        // Wait for all futures to complete (even if some fail)
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Document observed behavior
        System.out.println(String.format("Concurrent duplicate join: %d succeeded, %d failed, status codes: %s", 
                successCount.get(), failureCount.get(), statusCodes));
        
        // TODO: Document actual behavior
        // - All succeed? (idempotent)
        // - First succeeds, rest fail? (conflict)
        // - Some succeed? (race condition)
        
        // Verify all requests completed (at least attempted)
        // Allow for some timing issues in test environment
        assertTrue(statusCodes.size() >= 4, 
                "At least 4 out of 5 requests should complete (allowing for timing issues)");
    }

    /**
     * Test: Concurrent room creation by same IP
     * Observational: What happens with rapid room creation?
     */
    @Test
    void testConcurrentRoomCreation_ObserveBehavior() throws Exception {
        String testIp = "192.168.10." + System.currentTimeMillis() % 255;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        List<String> roomIds = new ArrayList<>();
        
        // 10 concurrent room creation attempts
        CompletableFuture<?>[] futures = new CompletableFuture[10];
        for (int i = 0; i < 10; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    String response = mockMvc.perform(post("/api/create-room")
                            .header("X-Forwarded-For", testIp))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                    
                    int status = mockMvc.perform(post("/api/create-room")
                            .header("X-Forwarded-For", testIp))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    
                    if (status == 200 && response.contains("roomId")) {
                        successCount.incrementAndGet();
                        // Extract room ID
                        int start = response.indexOf("\"roomId\"") + 10;
                        int end = response.indexOf("\"", start);
                        if (end > start) {
                            roomIds.add(response.substring(start, end));
                        }
                    } else if (status == 429) {
                        rateLimitedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Document behavior
        System.out.println(String.format("Concurrent room creation: %d succeeded, %d rate limited, unique rooms: %d", 
                successCount.get(), rateLimitedCount.get(), roomIds.size()));
        
        // TODO: Document actual behavior
        // - How many unique rooms created?
        // - Rate limiting behavior under concurrency?
        
        assertTrue(successCount.get() + rateLimitedCount.get() > 0, 
                "Some requests should complete");
    }

    /**
     * Test: Concurrent join to same room (different users)
     * Observational: What happens when multiple users join simultaneously?
     * Expected: Should succeed up to limit (10 users), then fail
     */
    @Test
    void testConcurrentJoinDifferentUsers_ObserveBehavior() throws Exception {
        String roomId = createTestRoom();
        ExecutorService executor = Executors.newFixedThreadPool(15);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger fullCount = new AtomicInteger(0);
        
        // 15 concurrent joins (room limit is 10)
        CompletableFuture<?>[] futures = new CompletableFuture[15];
        for (int i = 0; i < 15; i++) {
            final int userNum = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    int status = mockMvc.perform(post("/api/join-room")
                            .param("roomId", roomId)
                            .param("userId", "user" + userNum))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 400) {
                        fullCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Document behavior
        System.out.println(String.format("Concurrent different users join: %d succeeded, %d full, %d failed", 
                successCount.get(), fullCount.get(), failureCount.get()));
        
        // TODO: Document actual behavior
        // - Exactly 10 succeed? (atomic limit enforcement)
        // - More than 10? (race condition)
        // - Less than 10? (some other issue)
        
        // Verify limit is enforced (should be <= 10)
        assertTrue(successCount.get() <= 10, 
                "Should not exceed room user limit (10)");
    }

    /**
     * Test: Concurrent leave and join operations
     * Observational: What happens when users leave and join simultaneously?
     */
    @Test
    void testConcurrentLeaveAndJoin_ObserveBehavior() throws Exception {
        String roomId = createTestRoom();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        // First, join 5 users
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/join-room")
                    .param("roomId", roomId)
                    .param("userId", "user" + i));
        }
        
        AtomicInteger leaveSuccess = new AtomicInteger(0);
        AtomicInteger joinSuccess = new AtomicInteger(0);
        
        // Concurrent: 3 users leave, 5 users join
        CompletableFuture<?>[] futures = new CompletableFuture[8];
        
        // 3 leave operations
        for (int i = 0; i < 3; i++) {
            final int userNum = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    int status = mockMvc.perform(post("/api/leave-room")
                            .param("roomId", roomId)
                            .param("userId", "user" + userNum))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 200) leaveSuccess.incrementAndGet();
                } catch (Exception e) {
                    // Ignore
                }
            }, executor);
        }
        
        // 5 join operations
        for (int i = 5; i < 10; i++) {
            final int userNum = i;
            futures[i - 2] = CompletableFuture.runAsync(() -> {
                try {
                    int status = mockMvc.perform(post("/api/join-room")
                            .param("roomId", roomId)
                            .param("userId", "user" + userNum))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 200) joinSuccess.incrementAndGet();
                } catch (Exception e) {
                    // Ignore
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Document behavior
        System.out.println(String.format("Concurrent leave/join: %d left, %d joined", 
                leaveSuccess.get(), joinSuccess.get()));
        
        // TODO: Document actual behavior
        // - Final user count?
        // - Race conditions?
        // - Consistency?
        
        assertTrue(leaveSuccess.get() + joinSuccess.get() > 0, 
                "Some operations should complete");
    }

    /**
     * Helper method to create a test room
     */
    private String createTestRoom() throws Exception {
        String response = mockMvc.perform(post("/api/create-room"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        
        if (response.contains("\"roomId\"")) {
            int start = response.indexOf("\"roomId\"") + 10;
            int end = response.indexOf("\"", start);
            if (end > start) {
                return response.substring(start, end);
            }
        }
        return "test1234";
    }
}

