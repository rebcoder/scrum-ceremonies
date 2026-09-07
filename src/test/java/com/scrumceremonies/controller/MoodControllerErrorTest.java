package com.scrumceremonies.controller;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Error-path tests for MoodController.
 * Tests invalid inputs, error responses, and exception handling.
 * 
 * REUSES: Same testing patterns as RoomControllerErrorTest
 */
@WebMvcTest(
    controllers = MoodController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class MoodControllerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MoodService moodService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private RoomLimitService roomLimitService;

    @MockitoBean
    private RedisRoomPresenceService redisRoomPresenceService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @org.junit.jupiter.api.BeforeEach
    void setUpClientIpResolver() {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    // ==================== Create Room Tests ====================

    @Test
    void testCreateRoom_RateLimitExceeded() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Too many rooms created")));

        verify(rateLimitService).canCreateRoom(anyString());
        verify(moodService, never()).createRoom(anyString());
    }

    @Test
    void testCreateRoom_RoomLimitExceeded() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("maximum number of active rooms")));

        verify(rateLimitService).canCreateRoom(anyString());
        verify(roomLimitService).canCreateRoom(anyString());
        verify(moodService, never()).createRoom(anyString());
    }

    @Test
    void testCreateRoom_InvalidMode() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"INVALID_MODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid mode")));

        verify(moodService, never()).createRoom(anyString());
    }

    @Test
    void testCreateRoom_QuickPulseMode_Success() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(moodService.createRoom("QUICK_PULSE")).thenReturn("test1234");

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"QUICK_PULSE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("test1234"));

        verify(moodService).createRoom("QUICK_PULSE");
        verify(roomLimitService).registerRoomCreation(anyString(), eq("test1234"));
    }

    @Test
    void testCreateRoom_ScrumPulseMode_Success() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(moodService.createRoom("SCRUM_PULSE")).thenReturn("test5678");

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"SCRUM_PULSE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("test5678"));

        verify(moodService).createRoom("SCRUM_PULSE");
    }

    @Test
    void testCreateRoom_NoModeProvided_DefaultsToQuickPulse() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(moodService.createRoom(null)).thenReturn("test1234");

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());

        verify(moodService).createRoom(null);
    }

    @Test
    void testCreateRoom_NoBody_Success() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(moodService.createRoom(null)).thenReturn("test1234");

        // When & Then
        mockMvc.perform(post("/api/mood/create"))
                .andExpect(status().isOk());

        verify(moodService).createRoom(null);
    }

    @Test
    void testCreateRoom_RedisUnavailable() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(moodService.createRoom(any())).thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        // When & Then
        mockMvc.perform(post("/api/mood/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Failed to create room")));
    }

    // ==================== Join Room Tests ====================

    @Test
    void testJoinRoom_InvalidRoomIdFormat() throws Exception {
        // Given
        String invalidRoomId = "invalid";

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", invalidRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));

        verify(moodService, never()).getRoom(anyString());
    }

    @Test
    void testJoinRoom_RoomIdTooShort() throws Exception {
        // Given
        String shortRoomId = "abc";

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", shortRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testJoinRoom_RoomIdTooLong() throws Exception {
        // Given
        String longRoomId = "abcdefghij"; // 10 chars, should be 8

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", longRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testJoinRoom_RoomIdWithSpecialChars() throws Exception {
        // Given
        String invalidRoomId = "test!@#$";

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", invalidRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testJoinRoom_RoomNotFound() throws Exception {
        // Given
        String roomId = "test1234";
        when(moodService.getRoom(roomId)).thenReturn(null);

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", roomId))
                .andExpect(status().isNotFound());

        verify(moodService).getRoom(roomId);
    }

    @Test
    void testJoinRoom_RoomFull() throws Exception {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        when(moodService.getRoom(roomId)).thenReturn(room);
        when(redisRoomPresenceService.getUserCount(roomId)).thenReturn(10); // Full

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", roomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("room is full")));
    }

    @Test
    void testJoinRoom_Success() throws Exception {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        when(moodService.getRoom(roomId)).thenReturn(room);
        when(redisRoomPresenceService.getUserCount(roomId)).thenReturn(5); // Not full

        // When & Then
        mockMvc.perform(post("/api/mood/join")
                .param("roomId", roomId))
                .andExpect(status().isOk());
    }

    // ==================== Get State Tests ====================

    @Test
    void testGetState_InvalidRoomId() throws Exception {
        // Given
        String invalidRoomId = "invalid";

        // When & Then - Controller returns empty state without calling service for invalid IDs
        mockMvc.perform(get("/api/mood/state")
                .param("roomId", invalidRoomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasHost").value(false))
                .andExpect(jsonPath("$.totalUsers").value(0))
                .andExpect(jsonPath("$.submittedCount").value(0))
                .andExpect(jsonPath("$.resultsRevealed").value(false));

        // Controller doesn't call service for invalid IDs
        verify(moodService, never()).getFullState(anyString());
    }

    @Test
    void testGetState_ValidRoomId() throws Exception {
        // Given
        String roomId = "test1234";
        when(moodService.getFullState(roomId)).thenReturn(Map.of(
                "mode", "QUICK_PULSE",
                "names", Map.of("user1", "User1"),
                "hostName", "User1",
                "hasHost", true,
                "totalUsers", 1,
                "submittedCount", 0,
                "resultsRevealed", false,
                "allSubmitted", false
        ));

        // When & Then
        mockMvc.perform(get("/api/mood/state")
                .param("roomId", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("QUICK_PULSE"))
                .andExpect(jsonPath("$.hostName").value("User1"))
                .andExpect(jsonPath("$.hasHost").value(true))
                .andExpect(jsonPath("$.totalUsers").value(1));

        verify(moodService).getFullState(roomId);
    }

    @Test
    void testGetState_RoomNotFound_ReturnsEmptyState() throws Exception {
        // Given
        String roomId = "notfound";
        // Map.of doesn't accept null values, so use HashMap
        java.util.HashMap<String, Object> emptyState = new java.util.HashMap<>();
        emptyState.put("mode", null);
        emptyState.put("names", Map.of());
        emptyState.put("hostName", null);
        emptyState.put("hasHost", false);
        emptyState.put("totalUsers", 0);
        emptyState.put("submittedCount", 0);
        emptyState.put("resultsRevealed", false);
        emptyState.put("allSubmitted", false);
        when(moodService.getFullState(roomId)).thenReturn(emptyState);

        // When & Then
        mockMvc.perform(get("/api/mood/state")
                .param("roomId", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasHost").value(false))
                .andExpect(jsonPath("$.totalUsers").value(0));
    }
}

