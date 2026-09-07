package com.scrumceremonies.controller;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.model.Room;
import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RetrospectiveService;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.scrumceremonies.service.VisitorAnalyticsService;
import com.scrumceremonies.config.TestSecurityConfig;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PublicRoomController.
 * Tests public room info API (/api/rooms/{type}/{id}/public).
 * Join page is served by the static frontend, not the backend.
 */
@WebMvcTest(
    controllers = PublicRoomController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicRoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private RetrospectiveService retrospectiveService;

    @MockitoBean
    private MoodService moodService;

    @MockitoBean
    private SimpMessagingTemplate simpMessagingTemplate;

    @MockitoBean
    private VisitorAnalyticsService visitorAnalyticsService;

    @Test
    void testGetPublicRoomInfoPoker() throws Exception {
        // Given
        String roomId = "test1234";
        Room room = new Room();
        room.setRoomId(roomId);
        room.setHostName("Host"); // Set hostName directly
        ConcurrentHashMap<String, String> userNames = new ConcurrentHashMap<>();
        userNames.put("user1", "Host");
        room.setUserNames(userNames);
        when(roomService.getRoom(roomId)).thenReturn(room);

        // When & Then
        mockMvc.perform(get("/api/rooms/poker/{roomId}/public", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.roomType").value("poker"))
                .andExpect(jsonPath("$.hasHost").value(true));

        verify(roomService).getRoom(roomId);
    }

    @Test
    void testGetPublicRoomInfoPokerNotFound() throws Exception {
        // Given
        String roomId = "test1234";
        when(roomService.getRoom(roomId)).thenReturn(null);

        // When & Then
        mockMvc.perform(get("/api/rooms/poker/{roomId}/public", roomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Room not found or expired"));

        verify(roomService).getRoom(roomId);
    }

    @Test
    void testGetPublicRoomInfoRetro() throws Exception {
        // Given
        String roomId = "test1234";
        RetrospectiveBoard board = new RetrospectiveBoard();
        board.setRoomId(roomId);
        board.setHostName("Host"); // Set hostName directly
        ConcurrentHashMap<String, String> userNames = new ConcurrentHashMap<>();
        userNames.put("user1", "Host");
        board.setUserNames(userNames);
        when(retrospectiveService.getBoard(roomId)).thenReturn(board);

        // When & Then
        mockMvc.perform(get("/api/rooms/retro/{roomId}/public", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.roomType").value("retro"))
                .andExpect(jsonPath("$.hasHost").value(true));

        verify(retrospectiveService).getBoard(roomId);
    }

    @Test
    void testGetPublicRoomInfoRetroNotFound() throws Exception {
        // Given
        String roomId = "test1234";
        when(retrospectiveService.getBoard(roomId)).thenReturn(null);

        // When & Then
        mockMvc.perform(get("/api/rooms/retro/{roomId}/public", roomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Board not found or expired"));

        verify(retrospectiveService).getBoard(roomId);
    }

    @Test
    void testGetPublicRoomInfoMood() throws Exception {
        // Given
        String roomId = "test1234";
        MoodRoom moodRoom = new MoodRoom();
        moodRoom.setRoomId(roomId);
        moodRoom.setHostName("Host");
        when(moodService.getRoom(roomId)).thenReturn(moodRoom);

        // When & Then
        mockMvc.perform(get("/api/rooms/mood/{roomId}/public", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.roomType").value("mood"))
                .andExpect(jsonPath("$.hasHost").value(true));

        verify(moodService).getRoom(roomId);
    }

    @Test
    void testGetPublicRoomInfoMoodNotFound() throws Exception {
        // Given
        String roomId = "test1234";
        when(moodService.getRoom(roomId)).thenReturn(null);

        // When & Then
        mockMvc.perform(get("/api/rooms/mood/{roomId}/public", roomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Room not found or expired"));

        verify(moodService).getRoom(roomId);
    }

    @Test
    void testGetPublicRoomInfoInvalidRoomType() throws Exception {
        // Given
        String roomId = "test1234";

        // When & Then
        mockMvc.perform(get("/api/rooms/invalid/{roomId}/public", roomId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid room type"));
    }

    @Test
    void testGetPublicRoomInfoInvalidRoomId() throws Exception {
        // Given
        String roomId = "invalid";

        // When & Then
        mockMvc.perform(get("/api/rooms/poker/{roomId}/public", roomId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid room ID format"));
    }

    @Test
    void testGetPublicRoomInfoException() throws Exception {
        // Given
        String roomId = "test1234";
        when(roomService.getRoom(roomId)).thenThrow(new RuntimeException("Service error"));

        // When & Then
        mockMvc.perform(get("/api/rooms/poker/{roomId}/public", roomId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to retrieve room information"));
    }
}

