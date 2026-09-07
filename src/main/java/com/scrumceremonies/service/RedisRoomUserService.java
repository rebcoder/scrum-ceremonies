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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing room user limits using Redis Sets.
 * Provides atomic operations to enforce max users per room.
 * Thread-safe and works across multiple backend instances.
 */
@Service
public class RedisRoomUserService {
    private static final Logger log = LoggerFactory.getLogger(RedisRoomUserService.class);
    
    // Configurable max users per room via application.properties
    @Value("${app.room.max-users:10}")
    private int maxUsersPerRoom;
    
    // Redis key prefix for room user sets
    private static final String ROOM_USERS_KEY_PREFIX = "room:";
    private static final String ROOM_USERS_KEY_SUFFIX = ":users";
    
    // Room TTL (same as room expiration - 8 hours for poker/retro)
    private static final long ROOM_TTL_SECONDS = 8 * 60 * 60; // 8 hours
    
    private final StringRedisTemplate stringRedisTemplate;
    
    // Lua script for atomic user join
    // Returns: 1 = success, 0 = room full, -1 = user already exists
    private static final String JOIN_USER_SCRIPT = 
        "local roomKey = KEYS[1]\n" +
        "local userId = ARGV[1]\n" +
        "local maxUsers = tonumber(ARGV[2])\n" +
        "local ttl = tonumber(ARGV[3])\n" +
        "\n" +
        "-- Check if user already exists\n" +
        "local isMember = redis.call('SISMEMBER', roomKey, userId)\n" +
        "if isMember == 1 then\n" +
        "    -- User already in room, refresh TTL and return success\n" +
        "    redis.call('EXPIRE', roomKey, ttl)\n" +
        "    return 1\n" +
        "end\n" +
        "\n" +
        "-- Get current user count\n" +
        "local userCount = redis.call('SCARD', roomKey)\n" +
        "\n" +
        "-- Check if room is full\n" +
        "if userCount >= maxUsers then\n" +
        "    return 0  -- Room is full\n" +
        "end\n" +
        "\n" +
        "-- Add user to room set\n" +
        "redis.call('SADD', roomKey, userId)\n" +
        "\n" +
        "-- Set/refresh TTL\n" +
        "redis.call('EXPIRE', roomKey, ttl)\n" +
        "\n" +
        "-- Return success\n" +
        "return 1";
    
    private final DefaultRedisScript<Long> joinUserScript;
    
    @Autowired
    public RedisRoomUserService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.joinUserScript = new DefaultRedisScript<>();
        this.joinUserScript.setScriptText(JOIN_USER_SCRIPT);
        this.joinUserScript.setResultType(Long.class);
    }
    
    /**
     * Attempts to add a user to a room atomically.
     * Uses Redis Lua script to ensure race-condition safety.
     * 
     * @param roomCode Room code (8-character alphanumeric)
     * @param userId User identifier
     * @return JoinResult indicating success, room full, or user already exists
     */
    public JoinResult tryJoinRoom(String roomCode, String userId) {
        if (roomCode == null || roomCode.isEmpty() || userId == null || userId.isEmpty()) {
            return JoinResult.INVALID_INPUT;
        }
        
        try {
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            
            // Execute atomic join script
            List<String> keys = Collections.singletonList(roomKey);
            Long result = stringRedisTemplate.execute(
                joinUserScript,
                keys,
                userId,
                String.valueOf(maxUsersPerRoom),
                String.valueOf(ROOM_TTL_SECONDS)
            );
            
            if (result == null) {
                log.warn("Redis join script returned null for room: {}, user: {}", roomCode, userId);
                return JoinResult.ERROR;
            }
            
            // Result: 1 = success (new user added or user already exists), 0 = room full
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
     * Removes a user from a room.
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
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            stringRedisTemplate.opsForSet().remove(roomKey, userId);
            
            // Refresh TTL on activity
            stringRedisTemplate.expire(roomKey, ROOM_TTL_SECONDS, TimeUnit.SECONDS);
            
            log.debug("Removed user {} from room {}", userId, roomCode);
        } catch (Exception e) {
            log.warn("Error removing user {} from room {}: {}", userId, roomCode, e.getMessage());
            // Don't throw - removal failure shouldn't break the app
        }
    }
    
    /**
     * Gets the current number of users in a room.
     * 
     * @param roomCode Room code
     * @return Number of users (0 if room doesn't exist or error)
     */
    public int getUserCount(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return 0;
        }
        
        try {
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            Long count = stringRedisTemplate.opsForSet().size(roomKey);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            log.warn("Error getting user count for room {}: {}", roomCode, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Gets all user IDs in a room.
     * 
     * @param roomCode Room code
     * @return Set of user IDs (empty if room doesn't exist or error)
     */
    public Set<String> getUsers(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return Collections.emptySet();
        }
        
        try {
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            Set<String> users = stringRedisTemplate.opsForSet().members(roomKey);
            return users != null ? users : Collections.emptySet();
        } catch (Exception e) {
            log.warn("Error getting users for room {}: {}", roomCode, e.getMessage());
            return Collections.emptySet();
        }
    }
    
    /**
     * Checks if a user is in a room.
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
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(roomKey, userId);
            return Boolean.TRUE.equals(isMember);
        } catch (Exception e) {
            log.warn("Error checking if user {} is in room {}: {}", userId, roomCode, e.getMessage());
            return false;
        }
    }
    
    /**
     * Refreshes the TTL for a room's user set.
     * Called on activity (vote, message, etc.) to keep room alive.
     * 
     * @param roomCode Room code
     */
    public void refreshRoomTTL(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return;
        }
        
        try {
            String roomKey = ROOM_USERS_KEY_PREFIX + roomCode + ROOM_USERS_KEY_SUFFIX;
            stringRedisTemplate.expire(roomKey, ROOM_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error refreshing TTL for room {}: {}", roomCode, e.getMessage());
            // Don't throw - TTL refresh failure is non-critical
        }
    }
    
    /**
     * Result of a join attempt.
     */
    public enum JoinResult {
        SUCCESS,        // User successfully joined
        ROOM_FULL,      // Room has reached max capacity
        INVALID_INPUT,  // Invalid room code or user ID
        ERROR           // Redis error or other failure
    }
}

