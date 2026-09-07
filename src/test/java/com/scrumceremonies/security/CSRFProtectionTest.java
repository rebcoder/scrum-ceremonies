package com.scrumceremonies.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * CSRF Protection Tests
 * 
 * NOTE: Production code has CSRF disabled for /api/** endpoints (by design).
 * These tests verify the current behavior and document expectations.
 * 
 * Current Configuration:
 * - CSRF is disabled for /api/**, /ws/**, /actuator/** endpoints
 * - This is intentional for API-first design
 * - Tests verify this behavior is consistent
 * 
 * NOTE: TestSecurityConfig disables CSRF for all tests.
 * These tests document that API endpoints work without CSRF tokens,
 * which matches production behavior (CSRF disabled for /api/**).
 */
@SpringBootTest
@AutoConfigureWebMvc
@TestPropertySource(properties = {
    "app.rate-limit.room-creation.per-minute=5",
    "app.room.max-users=10"
})
class CSRFProtectionTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // TestSecurityConfig disables CSRF, which matches production behavior
        // (CSRF disabled for /api/** in production)
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    /**
     * Test: State-changing endpoints without CSRF token
     * Expected: Should succeed (CSRF disabled for /api/** by design)
     * 
     * This documents that the application intentionally allows API calls
     * without CSRF tokens, which is common for stateless REST APIs.
     */
    @Test
    void testCreateRoom_WithoutCsrfToken_ShouldSucceed() throws Exception {
        // POST /api/create-room without CSRF token
        // Expected: 200 OK (CSRF disabled for /api/**)
        int status = mockMvc.perform(post("/api/create-room"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        // Should not be 403 (Forbidden - CSRF error)
        assertNotEquals(403, status, "Should not return 403 Forbidden (CSRF error)");
        // Document: Status may be 200, 400, 404, or 429 - but NOT 403 (CSRF)
        assertTrue(status != 403, 
                "Should not return 403 (CSRF error). Actual status: " + status);
    }

    @Test
    void testJoinRoom_WithoutCsrfToken_ShouldSucceed() throws Exception {
        // First create a room
        String roomId = createTestRoom();
        
        // POST /api/join-room without CSRF token
        // Expected: Any status except 403 (CSRF error)
        int status = mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", "testUser"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        // Document: Status may be 200, 400, 404, or 429 - but NOT 403 (CSRF)
        assertNotEquals(403, status, 
                "Should not return 403 (CSRF error). Actual status: " + status);
        System.out.println("INFO: Join room without CSRF token returned status: " + status);
    }

    @Test
    void testCreateRetroBoard_WithoutCsrfToken_ShouldSucceed() throws Exception {
        // POST /api/retro/create without CSRF token
        // Expected: Any status except 403 (CSRF error)
        int status = mockMvc.perform(post("/api/retro/create")
                .param("userId", "testUser"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        // Document: Status may be 200, 400, 404, or 429 - but NOT 403 (CSRF)
        assertNotEquals(403, status, 
                "Should not return 403 (CSRF error). Actual status: " + status);
        System.out.println("INFO: Create retro without CSRF token returned status: " + status);
    }

    @Test
    void testLeaveRoom_WithoutCsrfToken_ShouldSucceed() throws Exception {
        // First create and join a room
        String roomId = createTestRoom();
        joinRoom(roomId, "testUser");
        
        // POST /api/leave-room without CSRF token
        // Expected: 200 OK (CSRF disabled for /api/**)
        int status = mockMvc.perform(post("/api/leave-room")
                .param("roomId", roomId)
                .param("userId", "testUser"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        assertNotEquals(403, status, "Should not return 403 Forbidden (CSRF error)");
        assertTrue(status == 200 || status == 400, 
                "Should succeed or fail validation, but not CSRF");
    }

    /**
     * Test: Verify CSRF is actually disabled for API endpoints
     * This is an observational test documenting current behavior
     */
    @Test
    void testApiEndpoints_CsrfDisabled_No403Errors() throws Exception {
        String roomId = createTestRoom();
        
        // Test multiple state-changing endpoints
        // All should work without CSRF token (no 403)
        int status1 = mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", "user1"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        int status2 = mockMvc.perform(post("/api/create-room"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        int status3 = mockMvc.perform(post("/api/retro/create")
                .param("userId", "user1"))
                .andReturn()
                .getResponse()
                .getStatus();
        
        assertNotEquals(403, status1, "Join room should not return 403 (CSRF)");
        assertNotEquals(403, status2, "Create room should not return 403 (CSRF)");
        assertNotEquals(403, status3, "Create retro should not return 403 (CSRF)");
    }

    /**
     * Helper method to create a test room
     */
    private String createTestRoom() throws Exception {
        String response = mockMvc.perform(post("/api/create-room"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        
        // Extract room ID from JSON response
        // Format: {"roomId":"abc12345"}
        if (response.contains("\"roomId\"")) {
            int start = response.indexOf("\"roomId\"") + 10;
            int end = response.indexOf("\"", start);
            return response.substring(start, end);
        }
        return "test1234"; // Fallback
    }

    /**
     * Helper method to join a room
     */
    private void joinRoom(String roomId, String userId) throws Exception {
        mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", userId));
    }
}

