package com.scrumceremonies.api;

import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST API integration tests for room operations.
 * Tests room creation, joining, validation, and error handling.
 */
@SpringBootTest
@AutoConfigureWebMvc
@TestPropertySource(properties = {
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60"
})
class RoomControllerApiTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    private MockMvc mockMvc;
    private String testRoomId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        // Create test room for join/leave tests only
        // Note: testCreateRoom doesn't need this, so it will create its own
        testRoomId = roomService.createRoom();
    }

    @Test
    void testCreateRoom() throws Exception {
        // Use a unique IP address to avoid room limits from other tests
        // The room limit is based on IP address (session identifier), so we need a unique IP
        // Use a different IP range to ensure no conflicts with other tests
        String uniqueIp = "10.0.0." + System.currentTimeMillis() % 255;
        mockMvc.perform(post("/api/create-room")
                .header("X-Forwarded-For", uniqueIp)
                .cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "test-session-" + System.currentTimeMillis())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8"));
    }

    @Test
    void testJoinRoom_ValidRoom() throws Exception {
        mockMvc.perform(post("/api/join-room")
                .param("roomId", testRoomId)
                .param("userId", "user1")
                .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk());
    }

    @Test
    void testJoinRoom_InvalidRoomCode() throws Exception {
        mockMvc.perform(post("/api/join-room")
                .param("roomId", "invalid")
                .param("userId", "user1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid room code format")));
    }

    @Test
    void testJoinRoom_RoomFull() throws Exception {
        // Fill room with 10 users
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(testRoomId, "user" + i);
        }
        
        mockMvc.perform(post("/api/join-room")
                .param("roomId", testRoomId)
                .param("userId", "user11")
                .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("full")));
    }

    @Test
    void testJoinRoom_NonexistentRoom() throws Exception {
        String nonexistentRoom = "00000000";
        
        mockMvc.perform(post("/api/join-room")
                .param("roomId", nonexistentRoom)
                .param("userId", "user1")
                .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testLeaveRoom() throws Exception {
        // Add user first
        redisRoomPresenceService.tryJoinRoom(testRoomId, "user1");
        
        mockMvc.perform(post("/api/leave-room")
                .param("roomId", testRoomId)
                .param("userId", "user1"))
                .andExpect(status().isOk());
        
        assertFalse(redisRoomPresenceService.isUserInRoom(testRoomId, "user1"));
    }

    @Test
    void testGetRoomState() throws Exception {
        mockMvc.perform(get("/api/room-state")
                .param("roomId", testRoomId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testGetVotes() throws Exception {
        mockMvc.perform(get("/api/votes")
                .param("roomId", testRoomId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}

