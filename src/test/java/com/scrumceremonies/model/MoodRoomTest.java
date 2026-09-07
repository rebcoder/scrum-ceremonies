package com.scrumceremonies.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MoodRoom model.
 * Tests user management, response submission, and state tracking.
 */
class MoodRoomTest {

    private MoodRoom room;

    @BeforeEach
    void setUp() {
        room = new MoodRoom();
        room.setRoomId("test1234");
        room.setMode("QUICK_PULSE");
    }

    // ==================== User Management Tests ====================

    @Test
    void testAddUser_FirstUserBecomesHost() {
        // When
        room.addUser("user1", "HostUser");

        // Then
        assertEquals("HostUser", room.getHostName());
        assertTrue(room.getUserNames().containsKey("user1"));
        assertEquals("HostUser", room.getUserNames().get("user1"));
    }

    @Test
    void testAddUser_SecondUserNotHost() {
        // Given
        room.addUser("user1", "HostUser");

        // When
        room.addUser("user2", "SecondUser");

        // Then
        assertEquals("HostUser", room.getHostName()); // Host unchanged
        assertTrue(room.getUserNames().containsKey("user2"));
        assertEquals("SecondUser", room.getUserNames().get("user2"));
    }

    @Test
    void testAddUser_DuplicateNameHandling() {
        // Given
        room.addUser("user1", "TestUser");

        // When
        room.addUser("user2", "TestUser");

        // Then
        assertEquals("TestUser", room.getUserNames().get("user1"));
        assertEquals("TestUser1", room.getUserNames().get("user2")); // Appended number
    }

    @Test
    void testAddUser_NullNameBecomesAnonymous() {
        // When
        room.addUser("user1", null);

        // Then
        assertEquals("Anonymous", room.getUserNames().get("user1"));
    }

    @Test
    void testAddUser_EmptyNameBecomesAnonymous() {
        // When
        room.addUser("user1", "   ");

        // Then
        assertEquals("Anonymous", room.getUserNames().get("user1"));
    }

    @Test
    void testAddUser_NameTruncatedTo20Chars() {
        // When
        room.addUser("user1", "ThisIsAVeryLongNameThatExceeds20Characters");

        // Then
        String name = room.getUserNames().get("user1");
        assertTrue(name.length() <= 20, "Name should be truncated to 20 chars");
    }

    @Test
    void testAddUser_XSSPrevention() {
        // When
        room.addUser("user1", "<script>alert('xss')</script>");

        // Then
        String name = room.getUserNames().get("user1");
        assertFalse(name.contains("<script>"), "Script tags should be escaped");
        assertTrue(name.contains("&lt;"), "< should be escaped");
    }

    @Test
    void testCanAddUser_UnderLimit() {
        // Given - add 9 users
        for (int i = 1; i <= 9; i++) {
            room.addUser("user" + i, "User" + i);
        }

        // Then
        assertTrue(room.canAddUser(), "Should allow 10th user");
    }

    @Test
    void testCanAddUser_AtLimit() {
        // Given - add 10 users
        for (int i = 1; i <= 10; i++) {
            room.addUser("user" + i, "User" + i);
        }

        // Then
        assertFalse(room.canAddUser(), "Should not allow 11th user");
    }

    @Test
    void testGetUserCount() {
        // Given
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.addUser("user3", "User3");

        // Then
        assertEquals(3, room.getUserCount());
    }

    // ==================== Response Submission Tests ====================

    @Test
    void testSubmitResponse_Success() {
        // Given
        room.addUser("user1", "User1");
        Map<String, Object> response = Map.of("mood", "😄");

        // When
        boolean result = room.submitResponse("user1", response);

        // Then
        assertTrue(result);
        assertEquals(1, room.getSubmittedCount());
        assertTrue(room.getSubmittedUsers().contains("user1"));
        assertEquals("😄", room.getResponses().get("user1").get("mood"));
    }

    @Test
    void testSubmitResponse_DuplicateRejected() {
        // Given
        room.addUser("user1", "User1");
        room.submitResponse("user1", Map.of("mood", "😄"));

        // When - try to submit again
        boolean result = room.submitResponse("user1", Map.of("mood", "😞"));

        // Then
        assertFalse(result);
        assertEquals(1, room.getSubmittedCount());
        assertEquals("😄", room.getResponses().get("user1").get("mood")); // Original preserved
    }

    @Test
    void testSubmitResponse_MultipleUsers() {
        // Given
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.addUser("user3", "User3");

        // When
        room.submitResponse("user1", Map.of("mood", "😄"));
        room.submitResponse("user2", Map.of("mood", "🙂"));
        room.submitResponse("user3", Map.of("mood", "😐"));

        // Then
        assertEquals(3, room.getSubmittedCount());
        assertEquals(3, room.getResponses().size());
    }

    @Test
    void testGetSubmittedCount() {
        // Given
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.submitResponse("user1", Map.of("mood", "😄"));

        // Then
        assertEquals(1, room.getSubmittedCount());
    }

    // ==================== All Users Submitted Tests ====================

    @Test
    void testAllUsersSubmitted_True() {
        // Given
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.submitResponse("user1", Map.of("mood", "😄"));
        room.submitResponse("user2", Map.of("mood", "🙂"));

        // Then
        assertTrue(room.allUsersSubmitted());
    }

    @Test
    void testAllUsersSubmitted_False_NotAllSubmitted() {
        // Given
        room.addUser("user1", "User1");
        room.addUser("user2", "User2");
        room.submitResponse("user1", Map.of("mood", "😄"));

        // Then
        assertFalse(room.allUsersSubmitted());
    }

    @Test
    void testAllUsersSubmitted_False_NoUsers() {
        // Then
        assertFalse(room.allUsersSubmitted());
    }

    @Test
    void testAllUsersSubmitted_SingleUser() {
        // Given
        room.addUser("user1", "User1");
        room.submitResponse("user1", Map.of("mood", "😄"));

        // Then
        assertTrue(room.allUsersSubmitted());
    }

    // ==================== Remove User Tests ====================

    @Test
    void testRemoveSubmittedUser() {
        // Given
        room.addUser("user1", "User1");
        room.submitResponse("user1", Map.of("mood", "😄"));
        assertEquals(1, room.getSubmittedCount());

        // When
        room.removeSubmittedUser("user1");

        // Then
        assertEquals(0, room.getSubmittedCount());
        assertFalse(room.getSubmittedUsers().contains("user1"));
    }

    // ==================== Results Revealed Tests ====================

    @Test
    void testResultsRevealed_InitiallyFalse() {
        assertFalse(room.isResultsRevealed());
    }

    @Test
    void testResultsRevealed_SetTrue() {
        room.setResultsRevealed(true);
        assertTrue(room.isResultsRevealed());
    }

    // ==================== Mode Tests ====================

    @Test
    void testMode_QuickPulse() {
        room.setMode("QUICK_PULSE");
        assertEquals("QUICK_PULSE", room.getMode());
    }

    @Test
    void testMode_ScrumPulse() {
        room.setMode("SCRUM_PULSE");
        assertEquals("SCRUM_PULSE", room.getMode());
    }

    // ==================== Last Activity Time Tests ====================

    @Test
    void testUpdateLastActivityTime() {
        // Given
        long before = System.currentTimeMillis();

        // When
        room.updateLastActivityTime();

        // Then
        long after = System.currentTimeMillis();
        assertTrue(room.getLastActivityTime() >= before);
        assertTrue(room.getLastActivityTime() <= after);
    }

    @Test
    void testAddUser_UpdatesLastActivityTime() {
        // Given
        long before = System.currentTimeMillis();

        // When
        room.addUser("user1", "User1");

        // Then
        assertTrue(room.getLastActivityTime() >= before);
    }

    @Test
    void testSubmitResponse_UpdatesLastActivityTime() {
        // Given
        room.addUser("user1", "User1");
        long before = System.currentTimeMillis();

        // When
        room.submitResponse("user1", Map.of("mood", "😄"));

        // Then
        assertTrue(room.getLastActivityTime() >= before);
    }

    // ==================== Response Data Tests ====================

    @Test
    void testQuickPulseResponse() {
        // Given
        room.setMode("QUICK_PULSE");
        room.addUser("user1", "User1");

        // When
        Map<String, Object> response = Map.of("mood", "🚀");
        room.submitResponse("user1", response);

        // Then
        assertEquals("🚀", room.getResponses().get("user1").get("mood"));
    }

    @Test
    void testScrumPulseResponse() {
        // Given
        room.setMode("SCRUM_PULSE");
        room.addUser("user1", "User1");

        // When
        Map<String, Object> response = Map.of(
            "mood", "😄",
            "confidence", 4,
            "workload", "Balanced",
            "blocked", "No",
            "comment", "Great sprint!"
        );
        room.submitResponse("user1", response);

        // Then
        Map<String, Object> stored = room.getResponses().get("user1");
        assertEquals("😄", stored.get("mood"));
        assertEquals(4, stored.get("confidence"));
        assertEquals("Balanced", stored.get("workload"));
        assertEquals("No", stored.get("blocked"));
        assertEquals("Great sprint!", stored.get("comment"));
    }

    // ==================== Host Name Tests ====================

    @Test
    void testHostName_NullInitially() {
        assertNull(room.getHostName());
    }

    @Test
    void testHostName_SetByFirstUser() {
        room.addUser("user1", "FirstUser");
        assertEquals("FirstUser", room.getHostName());
    }

    @Test
    void testHostName_NotChangedBySubsequentUsers() {
        room.addUser("user1", "FirstUser");
        room.addUser("user2", "SecondUser");
        room.addUser("user3", "ThirdUser");
        assertEquals("FirstUser", room.getHostName());
    }

    // ==================== Edge Cases ====================

    @Test
    void testEmptyRoom() {
        assertEquals(0, room.getUserCount());
        assertEquals(0, room.getSubmittedCount());
        assertFalse(room.allUsersSubmitted());
        assertTrue(room.canAddUser());
        assertNull(room.getHostName());
    }

    @Test
    void testResponsesMapInitialized() {
        assertNotNull(room.getResponses());
        assertTrue(room.getResponses().isEmpty());
    }

    @Test
    void testUserNamesMapInitialized() {
        assertNotNull(room.getUserNames());
        assertTrue(room.getUserNames().isEmpty());
    }
}


