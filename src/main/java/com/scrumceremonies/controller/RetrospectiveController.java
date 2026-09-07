package com.scrumceremonies.controller;

import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.service.RetrospectiveService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/retro")
public class RetrospectiveController {
    private static final Logger log = LoggerFactory.getLogger(RetrospectiveController.class);
    
    @Autowired
    private RetrospectiveService retrospectiveService;

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

    /**
     * Get session identifier from request.
     * Uses IP address as session identifier for anonymous users.
     */
    private String getSessionId(HttpServletRequest request) {
        // For now, use IP address as session identifier
        // This works for anonymous users without explicit session management
        return getClientIpAddress(request);
    }

    /**
     * Validates room ID format.
     * Room IDs should be exactly 8 alphanumeric characters (a-z, A-Z, 0-9).
     */
    private boolean isValidRoomId(String roomId) {
        return roomId != null && !roomId.isEmpty() && roomId.matches("^[a-zA-Z0-9]{8}$");
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(HttpServletRequest request) {
        try {
            String ipAddress = getClientIpAddress(request);
            String sessionId = getSessionId(request);
            
            // 1. Check rate limiting per IP (BEFORE board creation)
            if (!rateLimitService.canCreateRoom(ipAddress)) {
                log.warn("Rate limit exceeded for IP: {}", ipAddress);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Too many rooms created from this IP. Please wait and try again.");
            }
            
            // 2. Check max active rooms per session (BEFORE board creation)
            if (!roomLimitService.canCreateRoom(sessionId)) {
                log.warn("Room limit exceeded for session: {}", sessionId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("You already have the maximum number of active rooms.");
            }
            
            // 3. Create the board
            String boardId = retrospectiveService.createBoard();
            
            // 4. Register board creation for session tracking
            roomLimitService.registerRoomCreation(sessionId, boardId);
            
            return ResponseEntity.ok(boardId);
        } catch (Exception ex) {
            log.error("Unexpected error creating retro board", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create board. Please try again later.");
        }
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestParam("roomId") String roomId, HttpServletRequest request) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid room code format. Room code must be 8 alphanumeric characters.");
        }
        
        // Note: Join operations are NOT rate-limited per requirements
        // Only room/board creation is rate-limited to prevent abuse
        
        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Check user limit - just check count, don't add user (actual join happens via WebSocket)
        int currentUserCount = redisRoomPresenceService.getUserCount(roomId);
        if (currentUserCount >= 10) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("This board is full (max 10 participants).");
        }
        
        return ResponseEntity.ok().build();
    }

    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam("roomId") String roomId) {
        // Validate room ID format - return empty state for invalid IDs
        if (!isValidRoomId(roomId)) {
            return Map.of(
                    "wentWell", java.util.Collections.emptyList(),
                    "toImprove", java.util.Collections.emptyList(),
                    "actionItems", java.util.Collections.emptyList(),
                    "names", java.util.Collections.emptyMap(),
                    "hostName", null,
                    "hasHost", false
            );
        }
        return retrospectiveService.getFullState(roomId);
    }
}
