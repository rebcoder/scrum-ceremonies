package com.scrumceremonies.controller;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.service.RoomService;
import com.scrumceremonies.service.RedisRoomUserService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

@Controller
public class VoteController {
    private static final Logger log = LoggerFactory.getLogger(VoteController.class);
    
    @Autowired
    private RoomService roomService;

    @Autowired
    private RedisRoomUserService redisRoomUserService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Validates room ID format.
     * Room IDs should be exactly 8 alphanumeric characters (a-z, A-Z, 0-9).
     */
    private boolean isValidRoomId(String roomId) {
        return roomId != null && !roomId.isEmpty() && roomId.matches("^[a-zA-Z0-9]{8}$");
    }

    @MessageMapping("/vote.{roomId}")
    @SendTo("/topic/room.{roomId}.votes")
    public Map<String, Object> vote(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {

        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of(
                    "error", "Invalid room code format",
                    "allowed", false
            );
        }

        String userId = payload.get("userId");
        String vote = payload.get("vote");
        String userName = payload.get("userName");

        // Get current room state
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return Map.of(
                    "error", "Room does not exist",
                    "allowed", false
            );
        }

        // Check if user is in room using presence service (atomic check with lastSeen tracking)
        if (!redisRoomPresenceService.isUserInRoom(roomId, userId)) {
            // User not in room, try to join atomically with presence tracking
            RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
            
            if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
                return Map.of(
                        "error", "This room is full (max 10 participants).",
                        "roomFull", true,
                        "allowed", false,
                        "rejectedUserId", userId  // CRITICAL: Identify which user was rejected
                );
            } else if (joinResult != RedisRoomPresenceService.JoinResult.SUCCESS) {
                return Map.of(
                        "error", "Unable to join room",
                        "allowed", false
                );
            }
            
            // User successfully joined, add to room model
            roomService.addUserName(roomId, userId, userName);
        } else {
            // User already in room, update activity (heartbeat)
            redisRoomPresenceService.updateUserActivity(roomId, userId);
        }

        // Refresh room TTL on activity
        redisRoomPresenceService.refreshRoomTTL(roomId);

        // Process vote
        roomService.addOrUpdateVote(roomId, userId, vote);
        
        // Update user activity (heartbeat) - user is active
        redisRoomPresenceService.updateUserActivity(roomId, userId);

        return roomService.getRoomState(roomId);
    }

    @MessageMapping("/room.{roomId}.reveal")
    @SendTo("/topic/room.{roomId}")
    public Map<String, Object> revealVotes(@DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }
        
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return Map.of("error", "Room not found");
        }
        
        room.setRevealed(true);
        roomService.saveRoom(room);
        
        // Update user activity (heartbeat) - get userId from room state if available
        // Note: This is a room-level operation, so we update all active users' presence
        redisRoomPresenceService.refreshRoomTTL(roomId);

        return Map.of(
                "votes", room.getVotesAsStrings(),
                "names", room.getUserNames(),
                "revealed", true
        );
    }

    @MessageMapping("/room.{roomId}.clear")
    @SendTo("/topic/room.{roomId}")
    public Map<String, Object> clearVotes(@DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }
        
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return Map.of("error", "Room not found");
        }
        
        room.getUserVotes().clear();
        room.setRevealed(false);
        roomService.saveRoom(room);
        
        // Update user activity (heartbeat)
        redisRoomPresenceService.refreshRoomTTL(roomId);

        return Map.of(
                "votes", Collections.emptyMap(),
                "names", room.getUserNames(),
                "revealed", false
        );
    }

    /**
     * WebSocket join handler for poker rooms.
     * Uses atomic Redis operations to enforce user limit.
     * IMPORTANT: Errors are NOT broadcast to all users to prevent disconnects.
     * Only success messages are broadcast so all users get updated state.
     */
    @MessageMapping("/join.{roomId}")
    public void joinRoom(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {

        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            // Send error only to requesting user, not broadcast
            messagingTemplate.convertAndSend("/topic/room." + roomId, 
                Map.of("error", "Invalid room code format", "allowed", false));
            return;
        }
        
        // Validate input size to prevent oversized payloads
        if (roomId != null && roomId.length() > 100) {
            messagingTemplate.convertAndSend("/topic/room." + roomId,
                Map.of("error", "Room code is too long", "allowed", false));
            return;
        }

        String userId = payload.get("userId");
        String userName = payload.get("userName");
        
        // Validate userId and userName size
        if (userId != null && userId.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/room." + roomId,
                Map.of("error", "User ID is too long", "allowed", false));
            return;
        }
        if (userName != null && userName.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/room." + roomId,
                Map.of("error", "User name is too long", "allowed", false));
            return;
        }

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            messagingTemplate.convertAndSend("/topic/room." + roomId,
                Map.of("error", "Room not found", "allowed", false));
            return;
        }

        // Atomic join check using presence service (handles rejoin scenarios)
        RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
        
        if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
            log.info("User {} denied join to room {} - room is full", userId, roomId);
            // Send error with rejectedUserId so frontend can identify which user was rejected
            // Include current state so existing users stay in sync
            Map<String, Object> currentState = roomService.getRoomState(roomId);
            messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of(
                    "error", "This room is full (max 10 participants).",
                    "roomFull", true,
                    "allowed", false,
                    "rejectedUserId", userId,  // CRITICAL: Identify which user was rejected
                    "state", currentState  // Include state so existing users stay in sync
            ));
            return;
        } else if (joinResult == RedisRoomPresenceService.JoinResult.SUCCESS) {
            // User successfully joined (either new or rejoin/refresh)
            // Add to room model if not already present
            // Note: If user is not in room model but Redis allowed join, it means:
            // 1. User was just added to Redis (new join) - need to check room capacity
            // 2. User was already in Redis (rejoin) - should already be in room model, but if not, add them
            if (!room.getUserNames().containsKey(userId)) {
                // CRITICAL: Always check Redis count AND room model count before adding
                // This ensures we never exceed 10 users even if there's a sync issue
                int redisUserCount = redisRoomPresenceService.getUserCount(roomId);
                int roomUserCount = room.getUserNames().size();
                
                // STRICT CHECK: If Redis has more than 10 users, always reject
                if (redisUserCount > 10) {
                    log.error("CRITICAL: Room {} has {} users in Redis (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, redisUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    Map<String, Object> currentState = roomService.getRoomState(roomId);
                    messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", currentState
                    ));
                    return;
                }
                
                // If room model already has 10 users, reject (this is 11th+ user)
                // This is the key check - room model should never have more than 10 users
                if (roomUserCount >= 10) {
                    log.error("CRITICAL: Room {} model already has {} users (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, roomUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    Map<String, Object> currentState = roomService.getRoomState(roomId);
                    messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", currentState
                    ));
                    return;
                }
                
                // If Redis has 10 users and room has 9, this is the 10th user - allow
                // If Redis has < 10 users, safe to add
                // At this point, we know roomUserCount < 10, so it's safe to add
                roomService.addUserName(roomId, userId, userName);
            }
            
            // Update activity (heartbeat) - user is active
            redisRoomPresenceService.updateUserActivity(roomId, userId);
            
            // Broadcast success to all users so they get updated state
            Map<String, Object> roomState = roomService.getRoomState(roomId);
            messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of(
                    "allowed", true,
                    "state", roomState
            ));
        } else {
            // Error or invalid input
            log.warn("Join failed for user {} to room {}: {}", userId, roomId, joinResult);
            // Send error with current state so existing users don't lose sync
            Map<String, Object> currentState = roomService.getRoomState(roomId);
            messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of(
                    "error", "Unable to join room",
                    "allowed", false,
                    "state", currentState  // Include state so existing users stay in sync
            ));
        }
    }

    /**
     * WebSocket leave handler.
     * Removes user from Redis set and room model.
     */
    @MessageMapping("/room.{roomId}.leave")
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
        roomService.removeUser(roomId, userId);
    }

    /**
     * WebSocket reconnect handler.
     * Re-establishes user in room if they were previously connected.
     */
    @MessageMapping("/reconnect.{roomId}")
    @SendTo("/topic/room.{roomId}.votes")
    public Map<String, Object> handleReconnect(
            @Payload Map<String, String> payload,
            @DestinationVariable("roomId") String roomId) {

        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }

        String userId = payload.get("userId");

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return Map.of("error", "Room not found");
        }

        // Check if user was previously in room (using presence service)
        if (redisRoomPresenceService.isUserInRoom(roomId, userId)) {
            // User was in room, re-establish connection (reconnect scenario)
            room.updateLastActivityTime();
            roomService.saveRoom(room);
            // Update activity (heartbeat) - user reconnected
            redisRoomPresenceService.updateUserActivity(roomId, userId);
            redisRoomPresenceService.refreshRoomTTL(roomId);
        } else {
            // User not in room, try to join (may fail if room is full)
            RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
            if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
                return Map.of(
                        "error", "This room is full (max 10 participants).",
                        "allowed", false
                );
            } else if (joinResult == RedisRoomPresenceService.JoinResult.SUCCESS) {
                // User successfully rejoined, update activity
                redisRoomPresenceService.updateUserActivity(roomId, userId);
            }
        }

        return roomService.getRoomState(roomId);
    }
}
