package com.scrumceremonies.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Rate limiting service for room creation per IP address.
 * Implements multi-tier rate limiting: per minute, per hour, and per day.
 * Uses Redis atomic operations for thread-safe, distributed rate limiting.
 */
@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    
    // Configurable limits via application.properties
    @Value("${app.rate-limit.room-creation.per-minute:5}")
    private int maxPerMinute;
    
    @Value("${app.rate-limit.room-creation.per-hour:20}")
    private int maxPerHour;
    
    @Value("${app.rate-limit.room-creation.per-day:100}")
    private int maxPerDay;

    @Value("${app.rate-limit.room-join.per-minute:20}")
    private int maxJoinAttemptsPerMinute;

    // Redis key prefixes
    private static final String RATE_LIMIT_KEY_PREFIX = "rate:ip:";
    private static final String JOIN_ATTEMPT_KEY_PREFIX = "room:join:";
    
    private final StringRedisTemplate redisTemplate;
    
    // Lua script for atomic rate limiting (sliding window)
    // Returns 1 if allowed, 0 if rate limit exceeded
    private static final String RATE_LIMIT_SCRIPT = 
        "local key = KEYS[1]\n" +
        "local limit = tonumber(ARGV[1])\n" +
        "local window = tonumber(ARGV[2])\n" +
        "local current = redis.call('INCR', key)\n" +
        "if current == 1 then\n" +
        "    redis.call('EXPIRE', key, window)\n" +
        "end\n" +
        "if current <= limit then\n" +
        "    return 1\n" +
        "else\n" +
        "    return 0\n" +
        "end";
    
    private final DefaultRedisScript<Long> rateLimitScript;
    
    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(RATE_LIMIT_SCRIPT);
        this.rateLimitScript.setResultType(Long.class);
    }
    
    /**
     * Check if room creation is allowed for the given IP using multi-tier rate limiting.
     * Checks: per minute (60s TTL), per hour (3600s TTL), per day (86400s TTL)
     * 
     * @param ipAddress IP address to check
     * @return true if allowed, false if any limit exceeded
     */
    public boolean canCreateRoom(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress)) {
            // Allow if IP cannot be determined (fail open for edge cases)
            return true;
        }
        
        try {
            // Check per-minute limit (60 seconds TTL)
            String minuteKey = RATE_LIMIT_KEY_PREFIX + ipAddress + ":min";
            if (!checkRateLimit(minuteKey, maxPerMinute, 60)) {
                log.warn("Rate limit exceeded: {} attempted to create room (per-minute limit)", ipAddress);
                return false;
            }
            
            // Check per-hour limit (3600 seconds TTL)
            String hourKey = RATE_LIMIT_KEY_PREFIX + ipAddress + ":hour";
            if (!checkRateLimit(hourKey, maxPerHour, 3600)) {
                log.warn("Rate limit exceeded: {} attempted to create room (per-hour limit)", ipAddress);
                return false;
            }
            
            // Check per-day limit (86400 seconds TTL)
            String dayKey = RATE_LIMIT_KEY_PREFIX + ipAddress + ":day";
            if (!checkRateLimit(dayKey, maxPerDay, 86400)) {
                log.warn("Rate limit exceeded: {} attempted to create room (per-day limit)", ipAddress);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            // If Redis is unavailable, fall back to allowing the request
            // This prevents Redis failures from blocking legitimate users
            log.warn("Rate limiting check failed, allowing request: {}", e.getMessage());
            return true; // Fail open - allow request if Redis is down
        }
    }
    
    /**
     * Check if join attempt is allowed for the given IP using Redis
     * Uses sliding window algorithm with atomic operations
     */
    public boolean canJoinRoom(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress)) {
            return true;
        }
        return checkRateLimit(JOIN_ATTEMPT_KEY_PREFIX + ipAddress, maxJoinAttemptsPerMinute, 60);
    }

    /**
     * Check rate limit using Redis with atomic operations (fail-open).
     * Returns true if request is allowed, false if rate limit exceeded.
     * On Redis failure, allows the request (fail-open) — suitable for room operations.
     *
     * @param key Redis key for the rate limit counter
     * @param maxRequests Maximum number of requests allowed
     * @param ttlSeconds Time-to-live in seconds for the Redis key
     * @return true if allowed, false if rate limit exceeded
     */
    private boolean checkRateLimit(String key, int maxRequests, int ttlSeconds) {
        try {
            Long result = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.scriptingCommands().eval(
                    RATE_LIMIT_SCRIPT.getBytes(),
                    ReturnType.INTEGER,
                    1,
                    key.getBytes(),
                    String.valueOf(maxRequests).getBytes(),
                    String.valueOf(ttlSeconds).getBytes()
                )
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Rate limiting check failed for key {}, allowing request: {}", key, e.getMessage());
            return true; // Fail open - allow request if Redis is down
        }
    }

    /**
     * Get remaining requests for room creation (per-minute limit)
     */
    public int getRemainingRoomCreations(String ipAddress) {
        try {
            String key = RATE_LIMIT_KEY_PREFIX + ipAddress + ":min";
            String countStr = redisTemplate.opsForValue().get(key);
            if (countStr == null) {
                return maxPerMinute;
            }
            int currentCount = Integer.parseInt(countStr);
            return Math.max(0, maxPerMinute - currentCount);
        } catch (Exception e) {
            // If Redis is unavailable, return max to allow request
            return maxPerMinute;
        }
    }
}
