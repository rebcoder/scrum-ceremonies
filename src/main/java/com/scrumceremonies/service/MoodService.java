package com.scrumceremonies.service;

import com.scrumceremonies.model.MoodRoom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MoodService for Team Mood Check tool.
 * 
 * REUSES:
 * - Redis storage pattern from RoomService and RetrospectiveService
 * - TTL expiration (8 hours)
 * - Cache annotations
 * - RedisRoomPresenceService for user presence tracking
 * 
 * NEW:
 * - Mood response storage and aggregation
 * - Result calculation (mood distribution, averages, etc.)
 * - Anonymous response handling (no user names with responses)
 */
@Service
public class MoodService {
    public static final String MOOD_KEY_PREFIX = "mood:";

    private final RedisTemplate<String, MoodRoom> redisTemplate;

    @Autowired
    private SimpMessagingTemplate messageTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    public MoodService(@Qualifier("moodRedisTemplate") RedisTemplate<String, MoodRoom> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Create a new mood room.
     * REUSES: Same pattern as RetrospectiveService.createBoard()
     * 
     * @param mode "QUICK_PULSE" or "SCRUM_PULSE" (can be null, will default to QUICK_PULSE)
     */
    public String createRoom(String mode) {
        String roomId = UUID.randomUUID().toString().substring(0, 8);
        MoodRoom room = new MoodRoom();
        room.setRoomId(roomId);
        // Default to QUICK_PULSE if mode not specified
        room.setMode(mode != null && !mode.isEmpty() ? mode : "QUICK_PULSE");
        room.updateLastActivityTime();
        redisTemplate.opsForValue().set(MOOD_KEY_PREFIX + roomId, room, 8, TimeUnit.HOURS);
        return roomId;
    }
    
    /**
     * Update room mode (if mode not set yet).
     * NEW: Allows setting mode after room creation
     */
    @CacheEvict(value = "moodRooms", key = "#roomId")
    public boolean setRoomMode(String roomId, String mode) {
        MoodRoom room = getRoom(roomId);
        if (room == null) {
            return false;
        }
        
        // Only allow setting mode if it's not already set
        if (room.getMode() == null || room.getMode().isEmpty()) {
            if ("QUICK_PULSE".equals(mode) || "SCRUM_PULSE".equals(mode)) {
                room.setMode(mode);
                save(room);
                return true;
            }
        }
        
        return false;
    }

    @Cacheable(value = "moodRooms", key = "#roomId", unless = "#result == null")
    public MoodRoom getRoom(String roomId) {
        return redisTemplate.opsForValue().get(MOOD_KEY_PREFIX + roomId);
    }

    @CacheEvict(value = "moodRooms", key = "#room.roomId", beforeInvocation = false)
    public void save(MoodRoom room) {
        // Save to Redis first
        redisTemplate.opsForValue().set(MOOD_KEY_PREFIX + room.getRoomId(), room, 8, TimeUnit.HOURS);
        // Cache eviction happens AFTER save (default), ensuring cache is cleared after Redis write
    }

    /**
     * Submit a mood response for a user.
     * Enforces one response per user.
     * NEW: Response handling specific to mood check
     */
    @CacheEvict(value = "moodRooms", key = "#roomId", beforeInvocation = true)
    public boolean submitResponse(String roomId, String userId, Map<String, Object> responseData) {
        // Explicitly fetch from Redis directly (bypass cache) to ensure we get the latest state
        // Cache was evicted before method execution, but we fetch directly to avoid any cache miss caching
        MoodRoom room = redisTemplate.opsForValue().get(MOOD_KEY_PREFIX + roomId);
        if (room == null) {
            return false;
        }
        
        // Submit response (enforces one per user)
        boolean submitted = room.submitResponse(userId, responseData);
        if (submitted) {
            // Save to Redis and evict cache
            save(room);
            
            // Always broadcast progress update (results are only revealed manually by host)
            broadcastProgress(roomId);
        }
        
        return submitted;
    }

    /**
     * Reveal results to all participants.
     * NEW: Controls when results are shown
     * 
     * @param roomId The room ID
     * @param room Optional room object to use (avoids cache fetch). If null, will fetch from Redis.
     */
    @CacheEvict(value = "moodRooms", key = "#roomId")
    public void revealResults(String roomId) {
        revealResults(roomId, null);
    }
    
    /**
     * Reveal results to all participants (internal method with room object).
     * Avoids duplicate cache fetches when called from submitResponse.
     */
    @CacheEvict(value = "moodRooms", key = "#roomId")
    public void revealResults(String roomId, MoodRoom room) {
        if (room == null) {
            // Fetch directly from Redis (bypass cache) to get latest state
            room = redisTemplate.opsForValue().get(MOOD_KEY_PREFIX + roomId);
        }
        if (room == null) {
            return;
        }
        
        room.setResultsRevealed(true);
        save(room);
        
        // Broadcast aggregated results
        Map<String, Object> results = aggregateResults(room);
        broadcastResults(roomId, results);
    }

    /**
     * Broadcast progress update (how many users have submitted).
     * NEW: Progress tracking for mood check
     */
    public void broadcastProgress(String roomId) {
        MoodRoom room = getRoom(roomId);
        if (room == null) {
            return;
        }
        
        Map<String, Object> progress = new HashMap<>();
        progress.put("type", "PROGRESS_UPDATE");
        progress.put("submittedCount", room.getSubmittedCount());
        progress.put("totalUsers", room.getUserCount());
        progress.put("allSubmitted", room.allUsersSubmitted());
        
        messageTemplate.convertAndSend("/topic/mood." + roomId, progress);
    }

    /**
     * Broadcast aggregated results.
     * NEW: Results broadcasting for mood check
     */
    public void broadcastResults(String roomId, Map<String, Object> results) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "RESULTS_READY");
        message.put("results", results);
        // Include updated state so frontend can update button text to "Update Results"
        Map<String, Object> state = getFullState(roomId);
        message.put("state", state);
        
        messageTemplate.convertAndSend("/topic/mood." + roomId, message);
    }

    /**
     * Aggregate mood responses into summary statistics.
     * NEW: Server-side aggregation logic
     * 
     * Returns:
     * - Mood distribution (emoji counts)
     * - Average confidence score (for SCRUM_PULSE)
     * - Workload breakdown (for SCRUM_PULSE)
     * - Blocker count (for SCRUM_PULSE)
     * - Anonymous comments (for SCRUM_PULSE, only if >= 3 responses)
     */
    public Map<String, Object> aggregateResults(MoodRoom room) {
        Map<String, Object> results = new HashMap<>();
        
        Collection<Map<String, Object>> allResponses = room.getResponses().values();
        int totalResponses = allResponses.size();
        
        results.put("totalResponses", totalResponses);
        results.put("mode", room.getMode());
        
        // Mood distribution (both modes)
        Map<String, Integer> moodCounts = new HashMap<>();
        for (Map<String, Object> response : allResponses) {
            Object mood = response.get("mood");
            if (mood != null) {
                String moodStr = mood.toString();
                moodCounts.put(moodStr, moodCounts.getOrDefault(moodStr, 0) + 1);
            }
        }
        results.put("moodDistribution", moodCounts);
        
        // SCRUM_PULSE specific aggregations
        if ("SCRUM_PULSE".equals(room.getMode())) {
            // Average confidence
            List<Integer> confidenceScores = new ArrayList<>();
            for (Map<String, Object> response : allResponses) {
                Object confidence = response.get("confidence");
                if (confidence instanceof Number) {
                    confidenceScores.add(((Number) confidence).intValue());
                }
            }
            if (!confidenceScores.isEmpty()) {
                double avgConfidence = confidenceScores.stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
                results.put("averageConfidence", Math.round(avgConfidence * 10.0) / 10.0); // Round to 1 decimal
            } else {
                results.put("averageConfidence", null);
            }
            
            // Workload breakdown
            Map<String, Integer> workloadCounts = new HashMap<>();
            workloadCounts.put("Too low", 0);
            workloadCounts.put("Balanced", 0);
            workloadCounts.put("Too high", 0);
            
            for (Map<String, Object> response : allResponses) {
                Object workload = response.get("workload");
                if (workload != null) {
                    String workloadStr = workload.toString();
                    workloadCounts.put(workloadStr, workloadCounts.getOrDefault(workloadStr, 0) + 1);
                }
            }
            results.put("workloadBreakdown", workloadCounts);
            
            // Blocker count
            long blockedCount = allResponses.stream()
                .filter(response -> "Yes".equals(response.get("blocked")))
                .count();
            results.put("blockerCount", (int) blockedCount);
            
            // Anonymous comments (only if >= 3 responses for privacy)
            if (totalResponses >= 3) {
                List<String> comments = allResponses.stream()
                    .map(response -> response.get("comment"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(comment -> !comment.trim().isEmpty())
                    .collect(Collectors.toList());
                results.put("comments", comments);
            } else {
                results.put("comments", Collections.emptyList());
            }
        }
        
        return results;
    }

    /**
     * Get full room state including user names and submission status.
     * REUSES: Similar pattern to RetrospectiveService.getFullState()
     */
    public Map<String, Object> getFullState(String roomId) {
        MoodRoom room = getRoom(roomId);
        if (room != null) {
            Map<String, Object> state = new HashMap<>();
            state.put("mode", room.getMode());
            state.put("names", room.getUserNames());
            state.put("submittedCount", room.getSubmittedCount());
            state.put("totalUsers", room.getUserCount());
            state.put("resultsRevealed", room.isResultsRevealed());
            state.put("allSubmitted", room.allUsersSubmitted());
            state.put("sessionEnded", room.isSessionEnded());
            
            // Add host name
            String hostName = room.getHostName();
            state.put("hostName", hostName);
            state.put("hasHost", hostName != null && !hostName.isEmpty());
            
            // If results are revealed, include aggregated results
            if (room.isResultsRevealed()) {
                state.put("results", aggregateResults(room));
            }
            
            return state;
        } else {
            Map<String, Object> emptyState = new HashMap<>();
            emptyState.put("mode", null);
            emptyState.put("names", Collections.emptyMap());
            emptyState.put("submittedCount", 0);
            emptyState.put("totalUsers", 0);
            emptyState.put("resultsRevealed", false);
            emptyState.put("allSubmitted", false);
            emptyState.put("sessionEnded", false);
            emptyState.put("hostName", null);
            emptyState.put("hasHost", false);
            return emptyState;
        }
    }

    /**
     * Remove user from mood room.
     * REUSES: Same pattern as RetrospectiveService.removeUser()
     */
    @CacheEvict(value = "moodRooms", key = "#roomId")
    public void removeUser(String roomId, String userId) {
        MoodRoom room = getRoom(roomId);
        if (room != null) {
            room.getUserNames().remove(userId);
            // Note: We keep the response even if user leaves (for aggregation)
            // But remove from submitted users tracking
            room.removeSubmittedUser(userId);
            save(room);

            // Broadcast updated state
            Map<String, Object> state = getFullState(roomId);
            messageTemplate.convertAndSend("/topic/mood." + roomId, Map.of("state", state));
        }
    }

    /**
     * Deletes a mood room and its associated presence hash.
     * REUSES: Same pattern as RetrospectiveService.deleteBoard()
     * Uses stringRedisTemplate to delete presence hash
     */
    public void deleteRoom(String roomId) {
        String roomKey = MOOD_KEY_PREFIX + roomId;
        String presenceKey = "room:" + roomId + ":presence";
        
        // Delete room key using typed template (handles serialization)
        redisTemplate.delete(roomKey);
        
        // Delete presence hash using string template
        stringRedisTemplate.delete(presenceKey);
    }
}

