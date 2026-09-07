package com.scrumceremonies.controller;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.service.RoomService;
import com.scrumceremonies.service.RedisRoomUserService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RoomController {
    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    @Autowired
    private RoomService roomService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RoomLimitService roomLimitService;

    @Autowired
    private RedisRoomUserService redisRoomUserService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private ClientIpResolver clientIpResolver;

    private String getClientIpAddress(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    /**
     * Get session identifier from request.
     * Uses IP address as session identifier for anonymous users.
     * In the future, could use actual session ID if session management is added.
     */
    private String getSessionId(HttpServletRequest request) {
        // For now, use IP address as session identifier
        // This works for anonymous users without explicit session management
        return getClientIpAddress(request);
    }

    /**
     * Validates room ID format.
     * Room IDs should be exactly 8 alphanumeric characters (a-z, A-Z, 0-9).
     * 
     * @param roomId Room ID to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidRoomId(String roomId) {
        if (roomId == null || roomId.isEmpty()) {
            return false;
        }
        // Room ID should be exactly 8 alphanumeric characters
        return roomId.matches("^[a-zA-Z0-9]{8}$");
    }

    @PostMapping("/create-room")
    public ResponseEntity<?> createRoom(HttpServletRequest request) {
        try {
            String ipAddress = getClientIpAddress(request);
            String sessionId = getSessionId(request);
            
            // 1. Check rate limiting per IP (BEFORE room creation)
            if (!rateLimitService.canCreateRoom(ipAddress)) {
                log.warn("Rate limit exceeded for IP: {}", ipAddress);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Too many rooms created from this IP. Please wait and try again.");
            }
            
            // 2. Check max active rooms per session (BEFORE room creation)
            if (!roomLimitService.canCreateRoom(sessionId)) {
                log.warn("Room limit exceeded for session: {}", sessionId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("You already have the maximum number of active rooms.");
            }
            
            // 3. Create the room
            String roomId = roomService.createRoom();
            
            // 4. Register room creation for session tracking
            roomLimitService.registerRoomCreation(sessionId, roomId);
            
            return ResponseEntity.ok(roomId);
        } catch (RedisConnectionFailureException | RedisSystemException ex) {
            log.error("Redis unavailable while creating room", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Room service temporarily unavailable. Please try again later.");
        } catch (Exception ex) {
            log.error("Unexpected error creating room", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create room. Please try again later.");
        }
    }

    @PostMapping("/join-room")
    public ResponseEntity<?> joinRoom(
            @RequestParam String roomId,
            @RequestParam(required = false) String userId,
            HttpServletRequest request) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid room code format. Room code must be 8 alphanumeric characters.");
        }
        
        // Validate input size to prevent oversized payloads
        if (roomId != null && roomId.length() > 100) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Room code is too long.");
        }
        
        if (userId != null && userId.length() > 1000) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("User ID is too long.");
        }
        
        // Note: Join operations are NOT rate-limited per requirements
        // Only room creation is rate-limited to prevent abuse
        
        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Room not found");
        }
        
        // If userId provided, check room capacity using atomic Redis presence operation
        if (userId != null && !userId.isEmpty()) {
            RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
            
            if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
                log.info("User {} denied REST join to room {} - room is full", userId, roomId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("This room is full (max 10 participants).");
            } else if (joinResult == RedisRoomPresenceService.JoinResult.SUCCESS) {
                // User successfully joined (either new or rejoin/refresh)
                // Sync room model from Redis (source of truth) to handle concurrent updates
                // Get all users from Redis and ensure they're all in room model
                Map<Object, Object> redisUsers = redisRoomPresenceService.getUsersWithPresence(roomId);
                Room currentRoom = roomService.getRoom(roomId);
                if (currentRoom == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Room not found");
                }
                
                // Sync: Add any Redis users that are missing from room model
                boolean roomUpdated = false;
                for (Object redisUserIdObj : redisUsers.keySet()) {
                    String redisUserId = redisUserIdObj.toString();
                    if (!currentRoom.getUserNames().containsKey(redisUserId)) {
                        // User in Redis but not in room model - add them
                        currentRoom.addUserName(redisUserId, "User"); // Default name for REST joins
                        roomUpdated = true;
                    }
                }
                
                // Save if room was updated
                if (roomUpdated) {
                    roomService.saveRoom(currentRoom);
                }
                
                // Update activity (heartbeat) - user is active
                redisRoomPresenceService.updateUserActivity(roomId, userId);
                redisRoomPresenceService.refreshRoomTTL(roomId);
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unable to join room.");
            }
        }
        
        return ResponseEntity.ok().build();
    }

    @GetMapping("/votes")
    public Map<String, String> getVotes(@RequestParam String roomId) {
        // Validate room ID format - return empty map for invalid IDs
        if (!isValidRoomId(roomId)) {
            return Map.of();
        }
        return roomService.getVotes(roomId);
    }

    @GetMapping("/room-state")
    public Map<String, Object> getRoomState(@RequestParam String roomId) {
        // Validate room ID format - return empty state for invalid IDs
        if (!isValidRoomId(roomId)) {
            return Map.of("names", Map.of(), "revealed", false, "votes", Map.of());
        }
        return roomService.getRoomState(roomId);
    }

    @PostMapping("/leave-room")
    public ResponseEntity<?> leaveRoom(
            @RequestParam String roomId,
            @RequestParam String userId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid room code format.");
        }
        
        // Validate input size
        if (roomId != null && roomId.length() > 100) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Room code is too long.");
        }
        
        if (userId != null && userId.length() > 1000) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("User ID is too long.");
        }

        // Remove from presence hash (atomic)
        redisRoomPresenceService.removeUser(roomId, userId);
        
        // Also remove from legacy user set (backward compatibility)
        redisRoomUserService.removeUser(roomId, userId);
        
        // Remove from room model
        roomService.removeUser(roomId, userId);
        
        return ResponseEntity.ok().build();
    }
}
