package com.scrumceremonies.service;

import com.scrumceremonies.model.MoodRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MoodService.
 * Tests room creation, response submission, aggregation, and state management.
 * 
 * REUSES: Same testing patterns as RetrospectiveServiceAdditionalTest
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MoodServiceTest {

    @Mock
    private RedisTemplate<String, MoodRoom> redisTemplate;

    @Mock
    private ValueOperations<String, MoodRoom> valueOperations;

    @Mock
    private SimpMessagingTemplate messageTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private MoodService moodService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        moodService = new MoodService(redisTemplate);
        
        // Use reflection to inject messageTemplate and stringRedisTemplate
        try {
            java.lang.reflect.Field field = MoodService.class.getDeclaredField("messageTemplate");
            field.setAccessible(true);
            field.set(moodService, messageTemplate);
            
            field = MoodService.class.getDeclaredField("stringRedisTemplate");
            field.setAccessible(true);
            field.set(moodService, stringRedisTemplate);
        } catch (Exception e) {
            fail("Failed to inject mocks: " + e.getMessage());
        }
    }

    // ==================== Room Creation Tests ====================

    @Test
    void testCreateRoom_QuickPulseMode() {
        // When
        String roomId = moodService.createRoom("QUICK_PULSE");

        // Then
        assertNotNull(roomId);
        assertEquals(8, roomId.length());
        
        ArgumentCaptor<MoodRoom> roomCaptor = ArgumentCaptor.forClass(MoodRoom.class);
        verify(valueOperations).set(eq("mood:" + roomId), roomCaptor.capture(), eq(8L), eq(TimeUnit.HOURS));
        
        MoodRoom savedRoom = roomCaptor.getValue();
        assertEquals(roomId, savedRoom.getRoomId());
        assertEquals("QUICK_PULSE", savedRoom.getMode());
    }

    @Test
    void testCreateRoom_ScrumPulseMode() {
        // When
        String roomId = moodService.createRoom("SCRUM_PULSE");

        // Then
        assertNotNull(roomId);
        
        ArgumentCaptor<MoodRoom> roomCaptor = ArgumentCaptor.forClass(MoodRoom.class);
        verify(valueOperations).set(eq("mood:" + roomId), roomCaptor.capture(), anyLong(), any(TimeUnit.class));
        
        assertEquals("SCRUM_PULSE", roomCaptor.getValue().getMode());
    }

    @Test
    void testCreateRoom_NullModeDefaultsToQuickPulse() {
        // When
        String roomId = moodService.createRoom(null);

        // Then
        ArgumentCaptor<MoodRoom> roomCaptor = ArgumentCaptor.forClass(MoodRoom.class);
        verify(valueOperations).set(eq("mood:" + roomId), roomCaptor.capture(), anyLong(), any(TimeUnit.class));
        
        assertEquals("QUICK_PULSE", roomCaptor.getValue().getMode());
    }

    @Test
    void testCreateRoom_EmptyModeDefaultsToQuickPulse() {
        // When
        String roomId = moodService.createRoom("");

        // Then
        ArgumentCaptor<MoodRoom> roomCaptor = ArgumentCaptor.forClass(MoodRoom.class);
        verify(valueOperations).set(eq("mood:" + roomId), roomCaptor.capture(), anyLong(), any(TimeUnit.class));
        
        assertEquals("QUICK_PULSE", roomCaptor.getValue().getMode());
    }

    // ==================== Room Retrieval Tests ====================

    @Test
    void testGetRoom_ExistingRoom() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        MoodRoom result = moodService.getRoom(roomId);

        // Then
        assertNotNull(result);
        assertEquals(roomId, result.getRoomId());
    }

    @Test
    void testGetRoom_NonExistingRoom() {
        // Given
        String roomId = "notfound";
        when(valueOperations.get("mood:" + roomId)).thenReturn(null);

        // When
        MoodRoom result = moodService.getRoom(roomId);

        // Then
        assertNull(result);
    }

    // ==================== Set Mode Tests ====================

    @Test
    void testSetRoomMode_Success() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode(null); // Mode not set
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        boolean result = moodService.setRoomMode(roomId, "SCRUM_PULSE");

        // Then
        assertTrue(result);
        assertEquals("SCRUM_PULSE", room.getMode());
        verify(valueOperations).set(eq("mood:" + roomId), eq(room), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testSetRoomMode_AlreadySet() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE"); // Mode already set
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        boolean result = moodService.setRoomMode(roomId, "SCRUM_PULSE");

        // Then
        assertFalse(result);
        assertEquals("QUICK_PULSE", room.getMode()); // Mode unchanged
    }

    @Test
    void testSetRoomMode_InvalidMode() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode(null);
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        boolean result = moodService.setRoomMode(roomId, "INVALID_MODE");

        // Then
        assertFalse(result);
        assertNull(room.getMode());
    }

    @Test
    void testSetRoomMode_RoomNotFound() {
        // Given
        when(valueOperations.get("mood:notfound")).thenReturn(null);

        // When
        boolean result = moodService.setRoomMode("notfound", "QUICK_PULSE");

        // Then
        assertFalse(result);
    }

    // ==================== Response Submission Tests ====================

    @Test
    void testSubmitResponse_Success() {
        // Given
        String roomId = "test1234";
        String userId = "user1";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser(userId, "User1");
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        Map<String, Object> responseData = Map.of("mood", "😄");

        // When
        boolean result = moodService.submitResponse(roomId, userId, responseData);

        // Then
        assertTrue(result);
        // save() is called once in submitResponse, and potentially again in revealResults if all users submitted
        // Since we have only one user, it will trigger auto-reveal, so save() may be called multiple times
        verify(valueOperations, atLeast(1)).set(eq("mood:" + roomId), eq(room), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testSubmitResponse_DuplicateSubmission() {
        // Given
        String roomId = "test1234";
        String userId = "user1";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser(userId, "User1");
        room.submitResponse(userId, Map.of("mood", "😄")); // Already submitted
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        boolean result = moodService.submitResponse(roomId, userId, Map.of("mood", "😞"));

        // Then
        assertFalse(result);
    }

    @Test
    void testSubmitResponse_RoomNotFound() {
        // Given
        when(valueOperations.get("mood:notfound")).thenReturn(null);

        // When
        boolean result = moodService.submitResponse("notfound", "user1", Map.of("mood", "😄"));

        // Then
        assertFalse(result);
    }

    @Test
    void testSubmitResponse_AutoRevealWhenAllSubmitted() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When - single user submits (all users have submitted)
        moodService.submitResponse(roomId, "user1", Map.of("mood", "😄"));

        // Then - results should NOT be auto-revealed (manual reveal only, as per requirements)
        assertFalse(room.isResultsRevealed(), "Results should not be auto-revealed - host must manually reveal");
        // Verify progress update was sent, but not results
        verify(messageTemplate).convertAndSend(eq("/topic/mood." + roomId), (Object) argThat(map -> 
            map instanceof Map && !((Map<?, ?>) map).containsKey("results")
        ));
    }

    // ==================== Aggregation Tests ====================

    @Test
    void testAggregateResults_QuickPulse() {
        // Given
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.addUser("user3", "User3");
        room.submitResponse("user1", Map.of("mood", "😄"));
        room.submitResponse("user2", Map.of("mood", "😄"));
        room.submitResponse("user3", Map.of("mood", "🙂"));

        // When
        Map<String, Object> results = moodService.aggregateResults(room);

        // Then
        assertEquals(3, results.get("totalResponses"));
        assertEquals("QUICK_PULSE", results.get("mode"));
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> moodDistribution = (Map<String, Integer>) results.get("moodDistribution");
        assertEquals(2, moodDistribution.get("😄"));
        assertEquals(1, moodDistribution.get("🙂"));
    }

    @Test
    void testAggregateResults_ScrumPulse_WithComments() {
        // Given
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setMode("SCRUM_PULSE");
        
        // Add 3 users (minimum for comments to be shown)
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.addUser("user3", "User3");
        
        room.submitResponse("user1", Map.of(
            "mood", "😄",
            "confidence", 4,
            "workload", "Balanced",
            "blocked", "No",
            "comment", "Great sprint!"
        ));
        room.submitResponse("user2", Map.of(
            "mood", "🙂",
            "confidence", 3,
            "workload", "Too high",
            "blocked", "Yes",
            "comment", "Need more time"
        ));
        room.submitResponse("user3", Map.of(
            "mood", "😐",
            "confidence", 5,
            "workload", "Balanced",
            "blocked", "No",
            "comment", ""
        ));

        // When
        Map<String, Object> results = moodService.aggregateResults(room);

        // Then
        assertEquals(3, results.get("totalResponses"));
        assertEquals("SCRUM_PULSE", results.get("mode"));
        
        // Average confidence: (4 + 3 + 5) / 3 = 4.0
        assertEquals(4.0, results.get("averageConfidence"));
        
        // Workload breakdown
        @SuppressWarnings("unchecked")
        Map<String, Integer> workloadBreakdown = (Map<String, Integer>) results.get("workloadBreakdown");
        assertEquals(2, workloadBreakdown.get("Balanced"));
        assertEquals(1, workloadBreakdown.get("Too high"));
        assertEquals(0, workloadBreakdown.get("Too low"));
        
        // Blocker count
        assertEquals(1, results.get("blockerCount"));
        
        // Comments (should show since >= 3 responses)
        @SuppressWarnings("unchecked")
        List<String> comments = (List<String>) results.get("comments");
        assertEquals(2, comments.size()); // Empty comments filtered out
        assertTrue(comments.contains("Great sprint!"));
        assertTrue(comments.contains("Need more time"));
    }

    @Test
    void testAggregateResults_ScrumPulse_CommentsHiddenWhenLessThan3Responses() {
        // Given
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setMode("SCRUM_PULSE");
        
        // Add only 2 users (less than minimum for comments)
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        
        room.submitResponse("user1", Map.of(
            "mood", "😄",
            "confidence", 4,
            "workload", "Balanced",
            "blocked", "No",
            "comment", "Secret comment"
        ));
        room.submitResponse("user2", Map.of(
            "mood", "🙂",
            "confidence", 3,
            "workload", "Too high",
            "blocked", "Yes",
            "comment", "Another secret"
        ));

        // When
        Map<String, Object> results = moodService.aggregateResults(room);

        // Then
        assertEquals(2, results.get("totalResponses"));
        
        // Comments should be empty (privacy protection)
        @SuppressWarnings("unchecked")
        List<String> comments = (List<String>) results.get("comments");
        assertTrue(comments.isEmpty());
    }

    // ==================== Full State Tests ====================

    @Test
    void testGetFullState_WithHost() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "HostUser"); // First user becomes host
        room.addUser("user2", "User2");
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        Map<String, Object> state = moodService.getFullState(roomId);

        // Then
        assertNotNull(state);
        assertEquals("QUICK_PULSE", state.get("mode"));
        assertEquals("HostUser", state.get("hostName"));
        assertEquals(true, state.get("hasHost"));
        assertEquals(2, state.get("totalUsers"));
        assertEquals(0, state.get("submittedCount"));
        assertEquals(false, state.get("resultsRevealed"));
    }

    @Test
    void testGetFullState_WithoutHost() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        // No users added
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        Map<String, Object> state = moodService.getFullState(roomId);

        // Then
        assertNull(state.get("hostName"));
        assertEquals(false, state.get("hasHost"));
    }

    @Test
    void testGetFullState_RoomNotFound() {
        // Given
        when(valueOperations.get("mood:notfound")).thenReturn(null);

        // When
        Map<String, Object> state = moodService.getFullState("notfound");

        // Then
        assertNotNull(state);
        assertEquals(false, state.get("hasHost"));
        assertEquals(0, state.get("totalUsers"));
        assertNull(state.get("mode"));
        assertNull(state.get("hostName"));
    }

    @Test
    void testGetFullState_WithResultsRevealed() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");
        room.submitResponse("user1", Map.of("mood", "😄"));
        room.setResultsRevealed(true);
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        Map<String, Object> state = moodService.getFullState(roomId);

        // Then
        assertTrue((Boolean) state.get("resultsRevealed"));
        assertNotNull(state.get("results"));
    }

    // ==================== User Management Tests ====================

    @Test
    void testRemoveUser() {
        // Given
        String roomId = "test1234";
        String userId = "user1";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.addUser(userId, "User1");
        room.addUser("user2", "User2");
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        moodService.removeUser(roomId, userId);

        // Then
        assertFalse(room.getUserNames().containsKey(userId));
        assertTrue(room.getUserNames().containsKey("user2"));
        verify(valueOperations).set(eq("mood:" + roomId), eq(room), anyLong(), any(TimeUnit.class));
        verify(messageTemplate).convertAndSend(eq("/topic/mood." + roomId), any(Map.class));
    }

    @Test
    void testRemoveUser_RoomNotFound() {
        // Given
        when(valueOperations.get("mood:notfound")).thenReturn(null);

        // When
        moodService.removeUser("notfound", "user1");

        // Then
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    // ==================== Delete Room Tests ====================

    @Test
    void testDeleteRoom() {
        // Given
        String roomId = "test1234";

        // When
        moodService.deleteRoom(roomId);

        // Then
        verify(redisTemplate).delete("mood:" + roomId);
        verify(stringRedisTemplate).delete("room:" + roomId + ":presence");
    }

    // ==================== Reveal Results Tests ====================

    @Test
    void testRevealResults() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");
        room.submitResponse("user1", Map.of("mood", "😄"));
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        moodService.revealResults(roomId);

        // Then
        assertTrue(room.isResultsRevealed());
        verify(valueOperations).set(eq("mood:" + roomId), eq(room), anyLong(), any(TimeUnit.class));
        verify(messageTemplate).convertAndSend(eq("/topic/mood." + roomId), (Object) argThat(map -> 
            map instanceof Map && "RESULTS_READY".equals(((Map<?, ?>) map).get("type"))
        ));
    }

    @Test
    void testRevealResults_RoomNotFound() {
        // Given
        when(valueOperations.get("mood:notfound")).thenReturn(null);

        // When
        moodService.revealResults("notfound");

        // Then
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        verify(messageTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    // ==================== Progress Broadcast Tests ====================

    @Test
    void testBroadcastProgress() {
        // Given
        String roomId = "test1234";
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.submitResponse("user1", Map.of("mood", "😄"));
        when(valueOperations.get("mood:" + roomId)).thenReturn(room);

        // When
        moodService.broadcastProgress(roomId);

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messageTemplate).convertAndSend(eq("/topic/mood." + roomId), (Object) messageCaptor.capture());
        
        Map<String, Object> message = messageCaptor.getValue();
        assertEquals("PROGRESS_UPDATE", message.get("type"));
        assertEquals(1, message.get("submittedCount"));
        assertEquals(2, message.get("totalUsers"));
        assertEquals(false, message.get("allSubmitted"));
    }
}

