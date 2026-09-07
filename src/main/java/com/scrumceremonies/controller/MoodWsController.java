package com.scrumceremonies.controller;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RedisRoomUserService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * MoodWsController for Team Mood Check WebSocket message handling.
 * 
 * REUSES:
 * - WebSocket infrastructure (STOMP over SockJS)
 * - User presence tracking (RedisRoomPresenceService)
 * - Room validation patterns
 * - Message broadcasting patterns
 * 
 * NEW:
 * - Mood response submission handling
 * - Progress updates
 * - Results broadcasting
 */
@Controller
public class MoodWsController {
    private static final Logger log = LoggerFactory.getLogger(MoodWsController.class);
    
    @Autowired
    private MoodService moodService;

    @Autowired
    private RedisRoomUserService redisRoomUserService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Validates room ID format.
     * REUSED: Same validation as other controllers
     */
    private boolean isValidRoomId(String roomId) {
        return roomId != null && !roomId.isEmpty() && roomId.matches("^[a-zA-Z0-9]{8}$");
    }

    /**
     * WebSocket join handler for mood rooms.
     * REUSES: Same pattern as RetrospectiveWsController.join()
     */
    @MessageMapping("/mood.join.{roomId}")
    public void join(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid room code format", "allowed", false));
            return;
        }
        
        // Validate input size to prevent oversized payloads
        if (roomId != null && roomId.length() > 100) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room code is too long", "allowed", false));
            return;
        }
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room not found", "allowed", false));
            return;
        }
        
        // NEW: Check if session is closed (results revealed + all submitted OR host ended session)
        // This prevents new users from joining after the session has concluded
        // Note: Existing users can still reconnect (handled by tryJoinRoom)
        String userId = payload.get("userId");
        String userName = payload.get("userName");
        
        // Check if this is a new user (not already in room)
        boolean isNewUser = !room.getUserNames().containsKey(userId);
        
        if (isNewUser) {
            // Block new users if session is closed
            if (room.isResultsRevealed() && room.allUsersSubmitted()) {
                log.info("User {} denied join to mood room {} - session ended (results revealed + all submitted)", userId, roomId);
                Map<String, Object> currentState = moodService.getFullState(roomId);
                messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                        "error", "This session has ended. Results have been revealed and all participants have submitted.",
                        "sessionEnded", true,
                        "allowed", false,
                        "rejectedUserId", userId,
                        "state", currentState
                ));
                return;
            }
            if (room.isSessionEnded()) {
                log.info("User {} denied join to mood room {} - session ended by host", userId, roomId);
                Map<String, Object> currentState = moodService.getFullState(roomId);
                messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                        "error", "This session has been ended by the host.",
                        "sessionEnded", true,
                        "allowed", false,
                        "rejectedUserId", userId,
                        "state", currentState
                ));
                return;
            }
        }
        
        // Validate userId and userName size
        if (userId != null && userId.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "User ID is too long", "allowed", false));
            return;
        }
        if (userName != null && userName.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "User name is too long", "allowed", false));
            return;
        }
        
        // Atomic join check using presence service (handles rejoin scenarios)
        RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
        
        if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
            log.info("User {} denied join to mood room {} - room is full", userId, roomId);
            // Send error with rejectedUserId so frontend can identify which user was rejected
            // Include current state so existing users stay in sync
            Map<String, Object> currentState = moodService.getFullState(roomId);
            messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                    "error", "This room is full (max 10 participants).",
                    "roomFull", true,
                    "allowed", false,
                    "rejectedUserId", userId,
                    "state", currentState
            ));
            return;
        } else if (joinResult == RedisRoomPresenceService.JoinResult.SUCCESS) {
            // User successfully joined (either new or rejoin/refresh)
            // Add to room model if not already present
            if (!room.getUserNames().containsKey(userId)) {
                // CRITICAL: Always check Redis count AND room model count before adding
                int redisUserCount = redisRoomPresenceService.getUserCount(roomId);
                int roomUserCount = room.getUserNames().size();
                
                // STRICT CHECK: If Redis has more than 10 users, always reject
                if (redisUserCount > 10) {
                    log.error("CRITICAL: Mood room {} has {} users in Redis (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, redisUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    Map<String, Object> currentState = moodService.getFullState(roomId);
                    messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", currentState
                    ));
                    return;
                }
                
                // If room model already has 10 users, reject
                if (roomUserCount >= 10) {
                    log.error("CRITICAL: Mood room {} model already has {} users (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, roomUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    Map<String, Object> currentState = moodService.getFullState(roomId);
                    messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", currentState
                    ));
                    return;
                }
                
                // Safe to add user
                room.addUser(userId, userName != null ? userName : "Anonymous");
                moodService.save(room);
            }
            
            // Update activity (heartbeat) - user is active
            redisRoomPresenceService.updateUserActivity(roomId, userId);
            redisRoomPresenceService.refreshRoomTTL(roomId);
            
            // Broadcast success to all users so they get updated state
            Map<String, Object> roomState = moodService.getFullState(roomId);
            messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                    "allowed", true,
                    "state", roomState
            ));
        } else {
            // Error or invalid input
            log.warn("Join failed for user {} to mood room {}: {}", userId, roomId, joinResult);
            // Send error with current state so existing users don't lose sync
            Map<String, Object> currentState = moodService.getFullState(roomId);
            messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                    "error", "Unable to join room",
                    "allowed", false,
                    "state", currentState
            ));
        }
    }

    /**
     * Submit mood response.
     * NEW: Handles mood response submission
     * 
     * Payload:
     * {
     *   "userId": "...",
     *   "responses": {
     *     "mood": "😄",
     *     "confidence": 4,  // SCRUM_PULSE only
     *     "workload": "Balanced",  // SCRUM_PULSE only
     *     "blocked": "No",  // SCRUM_PULSE only
     *     "comment": "..."  // SCRUM_PULSE only, optional
     *   }
     * }
     */
    @MessageMapping("/mood.response.{roomId}")
    public void submitResponse(
            @Payload Map<String, Object> payload,
            @DestinationVariable("roomId") String roomId) {
        
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid room code format", "type", "ERROR"));
            return;
        }
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room not found", "type", "ERROR"));
            return;
        }
        
        String userId = (String) payload.get("userId");
        if (userId == null || userId.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "User ID required", "type", "ERROR"));
            return;
        }
        
        // Check if user is in room
        if (!redisRoomPresenceService.isUserInRoom(roomId, userId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "User not in room", "type", "ERROR"));
            return;
        }
        
        // Extract response data
        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = (Map<String, Object>) payload.get("responses");
        if (responseData == null || responseData.isEmpty()) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Response data required", "type", "ERROR"));
            return;
        }
        
        // Submit response (enforces one per user)
        boolean submitted = moodService.submitResponse(roomId, userId, responseData);
        
        if (!submitted) {
            // User already submitted
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "You have already submitted a response", "type", "ERROR"));
            return;
        }
        
        // Update user activity (heartbeat)
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);
        
        // Progress update or results will be broadcast by MoodService
        // (either broadcastProgress or broadcastResults is called in submitResponse)
    }

    /**
     * Set room mode (if not already set).
     * NEW: Allows setting mode after room creation
     */
    @MessageMapping("/mood.setmode.{roomId}")
    public void setMode(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {
        
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid room code format", "type", "ERROR"));
            return;
        }
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room not found", "type", "ERROR"));
            return;
        }
        
        String mode = payload.get("mode");
        if (mode == null || (!mode.equals("QUICK_PULSE") && !mode.equals("SCRUM_PULSE"))) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid mode", "type", "ERROR"));
            return;
        }
        
        // Set mode (only if not already set)
        boolean success = moodService.setRoomMode(roomId, mode);
        
        if (success) {
            // Broadcast updated state
            Map<String, Object> state = moodService.getFullState(roomId);
            messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                    "type", "MODE_SET",
                    "mode", mode,
                    "state", state
            ));
        } else {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Mode already set or invalid", "type", "ERROR"));
        }
    }

    /**
     * Host can manually reveal results before all users submit.
     * NEW: Allows host to end session early
     */
    @MessageMapping("/mood.reveal.{roomId}")
    public void revealResults(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {
        
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid room code format", "type", "ERROR"));
            return;
        }
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room not found", "type", "ERROR"));
            return;
        }
        
        String userId = payload.get("userId");
        
        // Only host can reveal results early
        // REUSES: Same host check pattern as Poker Planning
        // Host is the first user who joined (stored in hostName field)
        // Check if this user's name matches the host name
        if (userId == null || room.getHostName() == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Only the host can reveal results", "type", "ERROR"));
            return;
        }
        
        String userName = room.getUserNames().get(userId);
        if (userName == null || !userName.equals(room.getHostName())) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Only the host can reveal results", "type", "ERROR"));
            return;
        }
        
        // Reveal results
        moodService.revealResults(roomId);
        
        // Update activity
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);
    }

    /**
     * Host can explicitly end the Team Mood Check session.
     * NEW: Prevents new users from joining after session is ended
     * 
     * Payload:
     * {
     *   "userId": "..."
     * }
     */
    @MessageMapping("/mood.endSession.{roomId}")
    public void endSession(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {
        
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Invalid room code format", "type", "ERROR"));
            return;
        }
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Room not found", "type", "ERROR"));
            return;
        }
        
        String userId = payload.get("userId");
        
        // Only host can end session
        // REUSES: Same host check pattern as revealResults
        if (userId == null || room.getHostName() == null) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Only the host can end the session", "type", "ERROR"));
            return;
        }
        
        String userName = room.getUserNames().get(userId);
        if (userName == null || !userName.equals(room.getHostName())) {
            messagingTemplate.convertAndSend("/topic/mood." + roomId,
                Map.of("error", "Only the host can end the session", "type", "ERROR"));
            return;
        }
        
        // End session
        room.setSessionEnded(true);
        moodService.save(room);
        
        // Broadcast updated state
        Map<String, Object> state = moodService.getFullState(roomId);
        messagingTemplate.convertAndSend("/topic/mood." + roomId, Map.of(
                "type", "SESSION_ENDED",
                "state", state
        ));
        
        // Update activity
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);
    }

    /**
     * WebSocket leave handler for mood rooms.
     * REUSES: Same pattern as RetrospectiveWsController.handleLeave()
     */
    @MessageMapping("/mood.leave.{roomId}")
    public void handleLeave(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {

        // Validate room ID format - silently ignore invalid IDs
        if (!isValidRoomId(roomId)) {
            return;
        }
        
        String userId = payload.get("userId");
        
        // Remove from presence hash (atomic)
        redisRoomPresenceService.removeUser(roomId, userId);
        
        // Also remove from legacy user set (backward compatibility)
        redisRoomUserService.removeUser(roomId, userId);
        
        // Remove from room model
        moodService.removeUser(roomId, userId);
    }
}

