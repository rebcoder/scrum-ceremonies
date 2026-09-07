package com.scrumceremonies.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.model.RetrospectiveBoard;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/**
 * Service to limit the number of active rooms per session/user.
 * Tracks rooms per sessionId (or IP if sessionId not available).
 * A room is considered ACTIVE if lastActivity < 30 minutes.
 * Works with both Poker rooms and Retrospective boards.
 * Uses Redis for distributed tracking across multiple instances.
 */
@Service
public class RoomLimitService {
    private static final Logger log = LoggerFactory.getLogger(RoomLimitService.class);
    
    // Configurable limit via application.properties
    @Value("${app.room-limit.max-active-per-session:3}")
    private int maxActiveRoomsPerSession;
    
    // Room is considered active if last activity was within this time (30 minutes)
    private static final long ACTIVE_ROOM_THRESHOLD_MS = 30 * 60 * 1000; // 30 minutes in milliseconds
    
    // Redis key prefixes
    private static final String ROOMS_SESSION_KEY_PREFIX = "rooms:session:";
    private static final String ROOM_KEY_PREFIX = "room:";
    private static final String RETRO_KEY_PREFIX = "retro:";
    
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Room> roomRedisTemplate;
    private final RedisTemplate<String, RetrospectiveBoard> retroRedisTemplate;
    
    public RoomLimitService(
            StringRedisTemplate stringRedisTemplate,
            RedisTemplate<String, Room> roomRedisTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("retroRedisTemplate") 
            RedisTemplate<String, RetrospectiveBoard> retroRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.roomRedisTemplate = roomRedisTemplate;
        this.retroRedisTemplate = retroRedisTemplate;
    }
    
    /**
     * Check if a new room can be created for the given session.
     * A room is considered active if its lastActivityTime is within 30 minutes.
     * Counts both Poker rooms and Retrospective boards.
     * 
     * @param sessionId Session identifier (IP address or actual sessionId)
     * @return true if room creation is allowed, false if max active rooms reached
     */
    public boolean canCreateRoom(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || "unknown".equals(sessionId)) {
            // Allow if session cannot be determined (fail open for edge cases)
            return true;
        }
        
        try {
            // Get list of room codes for this session
            String sessionKey = ROOMS_SESSION_KEY_PREFIX + sessionId;
            Set<String> roomCodes = stringRedisTemplate.opsForSet().members(sessionKey);
            
            if (roomCodes == null || roomCodes.isEmpty()) {
                return true; // No active rooms, allow creation
            }
            
            // Filter to only active rooms (lastActivity < 30 minutes)
            Set<String> activeRoomCodes = new HashSet<>();
            long currentTime = System.currentTimeMillis();
            
            for (String roomCode : roomCodes) {
                // Try Poker room first
                Room room = roomRedisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomCode);
                if (room != null) {
                    long lastActivity = room.getLastActivityTime();
                    long timeSinceActivity = currentTime - lastActivity;
                    
                    if (timeSinceActivity < ACTIVE_ROOM_THRESHOLD_MS) {
                        activeRoomCodes.add(roomCode);
                    } else {
                        // Room is inactive, remove from session tracking
                        stringRedisTemplate.opsForSet().remove(sessionKey, roomCode);
                    }
                } else {
                    // Try Retrospective board
                    RetrospectiveBoard board = retroRedisTemplate.opsForValue().get(RETRO_KEY_PREFIX + roomCode);
                    if (board != null) {
                        long lastActivity = board.getLastActivityTime();
                        long timeSinceActivity = currentTime - lastActivity;
                        
                        if (timeSinceActivity < ACTIVE_ROOM_THRESHOLD_MS) {
                            activeRoomCodes.add(roomCode);
                        } else {
                            // Board is inactive, remove from session tracking
                            stringRedisTemplate.opsForSet().remove(sessionKey, roomCode);
                        }
                    } else {
                        // Room/board doesn't exist in Redis, remove from session tracking
                        stringRedisTemplate.opsForSet().remove(sessionKey, roomCode);
                    }
                }
            }
            
            // Check if we're at the limit
            if (activeRoomCodes.size() >= maxActiveRoomsPerSession) {
                log.warn("Room limit exceeded: session {} has {} active rooms (max: {})", 
                    sessionId, activeRoomCodes.size(), maxActiveRoomsPerSession);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            // If Redis is unavailable, fall back to allowing the request
            log.warn("Room limit check failed for session {}, allowing request: {}", sessionId, e.getMessage());
            return true; // Fail open - allow request if Redis is down
        }
    }
    
    /**
     * Register a room as created by a session.
     * Adds the room code to the session's room set in Redis.
     * Works for both Poker rooms and Retrospective boards.
     * 
     * @param sessionId Session identifier
     * @param roomCode Room code that was created
     */
    public void registerRoomCreation(String sessionId, String roomCode) {
        if (sessionId == null || sessionId.isEmpty() || "unknown".equals(sessionId)) {
            return; // Skip registration if session cannot be determined
        }
        
        if (roomCode == null || roomCode.isEmpty()) {
            return; // Skip if room code is invalid
        }
        
        try {
            String sessionKey = ROOMS_SESSION_KEY_PREFIX + sessionId;
            
            // Add room code to session's set
            // TTL: 24 hours (rooms expire after 8 hours, but keep tracking for 24h for cleanup)
            stringRedisTemplate.opsForSet().add(sessionKey, roomCode);
            stringRedisTemplate.expire(sessionKey, 24, TimeUnit.HOURS);
            
            log.debug("Registered room {} for session {}", roomCode, sessionId);
        } catch (Exception e) {
            log.warn("Failed to register room creation for session {}: {}", sessionId, e.getMessage());
            // Don't throw - registration failure shouldn't block room creation
        }
    }
    
    /**
     * Get the number of active rooms for a session.
     * Counts both Poker rooms and Retrospective boards.
     * 
     * @param sessionId Session identifier
     * @return Number of active rooms (0 if none or error)
     */
    public int getActiveRoomCount(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || "unknown".equals(sessionId)) {
            return 0;
        }
        
        try {
            String sessionKey = ROOMS_SESSION_KEY_PREFIX + sessionId;
            Set<String> roomCodes = stringRedisTemplate.opsForSet().members(sessionKey);
            
            if (roomCodes == null || roomCodes.isEmpty()) {
                return 0;
            }
            
            // Count only active rooms
            long currentTime = System.currentTimeMillis();
            int activeCount = 0;
            
            for (String roomCode : roomCodes) {
                // Try Poker room first
                Room room = roomRedisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomCode);
                if (room != null) {
                    long lastActivity = room.getLastActivityTime();
                    long timeSinceActivity = currentTime - lastActivity;
                    
                    if (timeSinceActivity < ACTIVE_ROOM_THRESHOLD_MS) {
                        activeCount++;
                    }
                } else {
                    // Try Retrospective board
                    RetrospectiveBoard board = retroRedisTemplate.opsForValue().get(RETRO_KEY_PREFIX + roomCode);
                    if (board != null) {
                        long lastActivity = board.getLastActivityTime();
                        long timeSinceActivity = currentTime - lastActivity;
                        
                        if (timeSinceActivity < ACTIVE_ROOM_THRESHOLD_MS) {
                            activeCount++;
                        }
                    }
                }
            }
            
            return activeCount;
        } catch (Exception e) {
            log.warn("Failed to get active room count for session {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }
}
