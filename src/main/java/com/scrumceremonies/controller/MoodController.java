package com.scrumceremonies.controller;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * MoodController for Team Mood Check REST endpoints.
 * 
 * REUSES:
 * - Rate limiting (RateLimitService)
 * - Room limit tracking (RoomLimitService)
 * - User presence tracking (RedisRoomPresenceService)
 * - Same validation patterns as RetrospectiveController
 * 
 * NEW:
 * - Accepts mode parameter (QUICK_PULSE or SCRUM_PULSE) in create request
 */
@RestController
@RequestMapping("/api/mood")
public class MoodController {
    private static final Logger log = LoggerFactory.getLogger(MoodController.class);
    
    @Autowired
    private MoodService moodService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RoomLimitService roomLimitService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private ClientIpResolver clientIpResolver;

    private String getClientIpAddress(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private String getSessionId(HttpServletRequest request) {
        return getClientIpAddress(request);
    }

    private boolean isValidRoomId(String roomId) {
        return roomId != null && !roomId.isEmpty() && roomId.matches("^[a-zA-Z0-9]{8}$");
    }

    /**
     * Create a new mood room.
     * NEW: Accepts mode parameter (QUICK_PULSE or SCRUM_PULSE)
     * 
     * Request body (optional):
     * {
     *   "mode": "QUICK_PULSE" | "SCRUM_PULSE"
     * }
     * 
     * If no body or mode not specified, defaults to QUICK_PULSE.
     */
    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, String> requestBody, HttpServletRequest request) {
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
            
            // 3. Extract mode from request body (can be null - will default to QUICK_PULSE in service)
            String mode = null;
            if (requestBody != null && requestBody.containsKey("mode")) {
                String requestedMode = requestBody.get("mode");
                if ("QUICK_PULSE".equals(requestedMode) || "SCRUM_PULSE".equals(requestedMode)) {
                    mode = requestedMode;
                } else {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("Invalid mode. Must be 'QUICK_PULSE' or 'SCRUM_PULSE'.");
                }
            }
            
            // 4. Create the room (mode can be null, will default to QUICK_PULSE)
            String roomId = moodService.createRoom(mode);
            
            // 5. Register room creation for session tracking
            roomLimitService.registerRoomCreation(sessionId, roomId);
            
            return ResponseEntity.ok(roomId);
        } catch (Exception ex) {
            log.error("Unexpected error creating mood room", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create room. Please try again later.");
        }
    }

    /**
     * Join a mood room (REST endpoint for validation).
     * REUSES: Same pattern as RetrospectiveController.join()
     */
    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestParam("roomId") String roomId, HttpServletRequest request) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid room code format. Room code must be 8 alphanumeric characters.");
        }
        
        // Note: Join operations are NOT rate-limited per requirements
        // Only room creation is rate-limited to prevent abuse
        
        MoodRoom room = moodService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        
        // NEW: Check if session is closed (results revealed + all submitted OR host ended session)
        // This prevents new users from joining after the session has concluded
        if (room.isResultsRevealed() && room.allUsersSubmitted()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("This session has ended. Results have been revealed and all participants have submitted.");
        }
        if (room.isSessionEnded()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("This session has been ended by the host.");
        }
        
        // Check user limit - just check count, don't add user (actual join happens via WebSocket)
        int currentUserCount = redisRoomPresenceService.getUserCount(roomId);
        if (currentUserCount >= 10) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("This room is full (max 10 participants).");
        }
        
        return ResponseEntity.ok().build();
    }

    /**
     * Get room state.
     * REUSES: Same pattern as RetrospectiveController.state()
     */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam("roomId") String roomId) {
        // Validate room ID format - return empty state for invalid IDs
        if (!isValidRoomId(roomId)) {
            Map<String, Object> emptyState = new HashMap<>();
            emptyState.put("mode", null);
            emptyState.put("names", Map.of());
            emptyState.put("submittedCount", 0);
            emptyState.put("totalUsers", 0);
            emptyState.put("resultsRevealed", false);
            emptyState.put("allSubmitted", false);
            emptyState.put("sessionEnded", false);
            emptyState.put("hostName", null);
            emptyState.put("hasHost", false);
            return emptyState;
        }
        return moodService.getFullState(roomId);
    }
}

