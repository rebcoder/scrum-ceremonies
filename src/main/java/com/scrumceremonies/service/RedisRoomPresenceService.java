package com.scrumceremonies.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced service for managing room user presence using Redis HASH.
 * Tracks userId -> lastSeenEpochMillis for robust presence management.
 * Handles page refresh, tab close, network drops, and safe rejoin.
 * 
 * This service EXTENDS existing functionality without breaking changes:
 * - Works alongside existing RedisRoomUserService
 * - Uses HASH for presence tracking (userId -> lastSeen)
 * - Provides heartbeat mechanism
 * - Enables cleanup of inactive users
 */
@Service
public class RedisRoomPresenceService {
    private static final Logger log = LoggerFactory.getLogger(RedisRoomPresenceService.class);
    
    // Configurable values via application.properties
    @Value("${app.room.max-users:10}")
    private int maxUsersPerRoom;
    
    @Value("${app.room.user-ttl-seconds:60}")
    private long userTtlSeconds;
    
    // Redis key prefix for room user presence (HASH)
    private static final String ROOM_PRESENCE_KEY_PREFIX = "room:";
    private static final String ROOM_PRESENCE_KEY_SUFFIX = ":presence";
    
    // Room TTL (same as room expiration - 8 hours)
    private static final long ROOM_TTL_SECONDS = 8 * 60 * 60; // 8 hours
    
    private final StringRedisTemplate stringRedisTemplate;
    
    /**
     * Lua script for atomic user join with presence tracking.
     * Uses Redis HASH to store userId -> lastSeenEpochMillis
     * 
     * Returns: 1 = success, 0 = room full
     */
    private static final String JOIN_USER_WITH_PRESENCE_SCRIPT = 
        "local roomKey = KEYS[1]\n" +
        "local userId = ARGV[1]\n" +
        "local maxUsers = tonumber(ARGV[2])\n" +
        "local ttl = tonumber(ARGV[3])\n" +
        "local currentTime = tonumber(ARGV[4])\n" +
        "\n" +
        "-- Check if user already exists in presence hash\n" +
        "local existingTime = redis.call('HGET', roomKey, userId)\n" +
        "if existingTime ~= false then\n" +
        "    -- User already in room, update lastSeen and refresh TTL\n" +
        "    redis.call('HSET', roomKey, userId, currentTime)\n" +
        "    redis.call('EXPIRE', roomKey, ttl)\n" +
        "    return 1  -- Success (reconnect/refresh)\n" +
        "end\n" +
        "\n" +
        "-- Get current active user count (only users with recent activity)\n" +
        "-- Count all fields in hash (we'll filter inactive users in cleanup)\n" +
        "local userCount = redis.call('HLEN', roomKey)\n" +
        "\n" +
        "-- Check if room is full\n" +
        "if userCount >= maxUsers then\n" +
        "    return 0  -- Room is full\n" +
        "end\n" +
        "\n" +
        "-- Add user to presence hash with current timestamp\n" +
        "redis.call('HSET', roomKey, userId, currentTime)\n" +
        "\n" +
        "-- Set/refresh TTL\n" +
        "redis.call('EXPIRE', roomKey, ttl)\n" +
        "\n" +
        "-- Return success\n" +
        "return 1";
    
    private final DefaultRedisScript<Long> joinUserWithPresenceScript;
    
    @Autowired
    public RedisRoomPresenceService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.joinUserWithPresenceScript = new DefaultRedisScript<>();
        this.joinUserWithPresenceScript.setScriptText(JOIN_USER_WITH_PRESENCE_SCRIPT);
        this.joinUserWithPresenceScript.setResultType(Long.class);
    }
    
    /**
     * Attempts to add a user to a room atomically with presence tracking.
     * Handles rejoin scenarios (page refresh, reconnect, etc.)
     * 
     * @param roomCode Room code (8-character alphanumeric)
     * @param userId User identifier (from frontend or generated)
     * @return JoinResult indicating success or room full
     */
    public JoinResult tryJoinRoom(String roomCode, String userId) {
        if (roomCode == null || roomCode.isEmpty() || userId == null || userId.isEmpty()) {
            return JoinResult.INVALID_INPUT;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            long currentTime = System.currentTimeMillis();
            
            // Execute atomic join script
            List<String> keys = Collections.singletonList(roomKey);
            Long result = stringRedisTemplate.execute(
                joinUserWithPresenceScript,
                keys,
                userId,
                String.valueOf(maxUsersPerRoom),
                String.valueOf(ROOM_TTL_SECONDS),
                String.valueOf(currentTime)
            );
            
            if (result == null) {
                log.warn("Redis join script returned null for room: {}, user: {}", roomCode, userId);
                return JoinResult.ERROR;
            }
            
            if (result == 1L) {
                return JoinResult.SUCCESS;
            } else if (result == 0L) {
                log.info("Room {} is full (max {} users), user {} denied", roomCode, maxUsersPerRoom, userId);
                return JoinResult.ROOM_FULL;
            } else {
                log.warn("Unexpected result from join script: {} for room: {}, user: {}", result, roomCode, userId);
                return JoinResult.ERROR;
            }
        } catch (Exception e) {
            log.error("Error executing join script for room: {}, user: {}", roomCode, userId, e);
            // Fail open - allow join if Redis is unavailable (prevents service outage)
            return JoinResult.ERROR;
        }
    }
    
    /**
     * Updates user's lastSeen timestamp (heartbeat).
     * Called on any activity: vote, message, ping, etc.
     * 
     * @param roomCode Room code
     * @param userId User identifier
     */
    public void updateUserActivity(String roomCode, String userId) {
        if (roomCode == null || roomCode.isEmpty() || userId == null || userId.isEmpty()) {
            return;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            long currentTime = System.currentTimeMillis();
            
            // Update lastSeen timestamp (only if user exists)
            if (stringRedisTemplate.opsForHash().hasKey(roomKey, userId)) {
                stringRedisTemplate.opsForHash().put(roomKey, userId, String.valueOf(currentTime));
                // Refresh TTL on activity
                stringRedisTemplate.expire(roomKey, ROOM_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Error updating user activity for room: {}, user: {}: {}", roomCode, userId, e.getMessage());
            // Don't throw - activity update failure shouldn't break the app
        }
    }
    
    /**
     * Removes a user from room presence.
     * Safe to call even if user doesn't exist.
     * 
     * @param roomCode Room code
     * @param userId User identifier
     */
    public void removeUser(String roomCode, String userId) {
        if (roomCode == null || roomCode.isEmpty() || userId == null || userId.isEmpty()) {
            return;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            stringRedisTemplate.opsForHash().delete(roomKey, userId);
            
            // Refresh TTL on activity (if other users remain)
            stringRedisTemplate.expire(roomKey, ROOM_TTL_SECONDS, TimeUnit.SECONDS);
            
            log.debug("Removed user {} from room {} presence", userId, roomCode);
        } catch (Exception e) {
            log.warn("Error removing user {} from room {} presence: {}", userId, roomCode, e.getMessage());
            // Don't throw - removal failure shouldn't break the app
        }
    }
    
    /**
     * Gets the current number of active users in a room.
     * Counts all users in presence hash (cleanup task filters inactive ones).
     * 
     * @param roomCode Room code
     * @return Number of users (0 if room doesn't exist or error)
     */
    public int getUserCount(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return 0;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            Long count = stringRedisTemplate.opsForHash().size(roomKey);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            log.warn("Error getting user count for room {}: {}", roomCode, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Gets all user IDs in a room with their lastSeen timestamps.
     * 
     * @param roomCode Room code
     * @return Map of userId -> lastSeenEpochMillis (empty if room doesn't exist or error)
     */
    public Map<Object, Object> getUsersWithPresence(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return Collections.emptyMap();
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            Map<Object, Object> users = stringRedisTemplate.opsForHash().entries(roomKey);
            return users != null ? users : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Error getting users with presence for room {}: {}", roomCode, e.getMessage());
            return Collections.emptyMap();
        }
    }
    
    /**
     * Checks if a user is in a room (based on presence hash).
     * 
     * @param roomCode Room code
     * @param userId User identifier
     * @return true if user is in room, false otherwise
     */
    public boolean isUserInRoom(String roomCode, String userId) {
        if (roomCode == null || roomCode.isEmpty() || userId == null || userId.isEmpty()) {
            return false;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            Boolean exists = stringRedisTemplate.opsForHash().hasKey(roomKey, userId);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Error checking if user {} is in room {}: {}", userId, roomCode, e.getMessage());
            return false;
        }
    }
    
    /**
     * Refreshes the TTL for a room's presence hash.
     * Called on activity to keep room alive.
     * 
     * @param roomCode Room code
     */
    public void refreshRoomTTL(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            stringRedisTemplate.expire(roomKey, ROOM_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error refreshing TTL for room {}: {}", roomCode, e.getMessage());
            // Don't throw - TTL refresh failure is non-critical
        }
    }
    
    /**
     * Cleans up inactive users from a room.
     * Removes users where (now - lastSeen) > userTtlSeconds
     * 
     * @param roomCode Room code
     * @return Number of users removed
     */
    public int cleanupInactiveUsers(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return 0;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            long currentTime = System.currentTimeMillis();
            long threshold = currentTime - (userTtlSeconds * 1000);
            
            Map<Object, Object> users = stringRedisTemplate.opsForHash().entries(roomKey);
            if (users == null || users.isEmpty()) {
                return 0;
            }
            
            int removed = 0;
            for (Map.Entry<Object, Object> entry : users.entrySet()) {
                String userId = (String) entry.getKey();
                String lastSeenStr = (String) entry.getValue();
                
                try {
                    long lastSeen = Long.parseLong(lastSeenStr);
                    if (lastSeen < threshold) {
                        // User is inactive, remove
                        stringRedisTemplate.opsForHash().delete(roomKey, userId);
                        removed++;
                        log.debug("Removed inactive user {} from room {} (lastSeen: {}ms ago)", 
                                userId, roomCode, currentTime - lastSeen);
                    }
                } catch (NumberFormatException e) {
                    // Invalid timestamp, remove it
                    stringRedisTemplate.opsForHash().delete(roomKey, userId);
                    removed++;
                    log.warn("Removed user {} with invalid timestamp from room {}", userId, roomCode);
                }
            }
            
            return removed;
        } catch (Exception e) {
            log.warn("Error cleaning up inactive users for room {}: {}", roomCode, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Deletes the presence hash for a room.
     * Used when a room/board is deleted to prevent orphaned presence data.
     * 
     * @param roomCode Room code
     */
    public void deletePresence(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return;
        }
        
        try {
            String roomKey = ROOM_PRESENCE_KEY_PREFIX + roomCode + ROOM_PRESENCE_KEY_SUFFIX;
            stringRedisTemplate.delete(roomKey);
            log.debug("Deleted presence hash for room: {}", roomCode);
        } catch (Exception e) {
            log.warn("Error deleting presence hash for room {}: {}", roomCode, e.getMessage());
            // Don't throw - deletion failure shouldn't break the app
        }
    }
    
    /**
     * Result of a join attempt.
     */
    public enum JoinResult {
        SUCCESS,        // User successfully joined (new or rejoin)
        ROOM_FULL,      // Room has reached max capacity
        INVALID_INPUT,  // Invalid room code or user ID
        ERROR           // Redis error or other failure
    }
}

