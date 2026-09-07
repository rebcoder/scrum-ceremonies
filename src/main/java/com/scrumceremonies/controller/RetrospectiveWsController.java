package com.scrumceremonies.controller;

import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.service.RetrospectiveService;
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

import java.util.Map;

@Controller
public class RetrospectiveWsController {
    private static final Logger log = LoggerFactory.getLogger(RetrospectiveWsController.class);
    
    @Autowired
    private RetrospectiveService retrospectiveService;

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

    /** Build standard board state response including timer info */
    private java.util.HashMap<String, Object> boardResponse(RetrospectiveBoard board) {
        var resp = new java.util.HashMap<String, Object>();
        resp.put("state", board.getColumns());
        resp.put("names", board.getUserNames());
        if (board.getTimerStartedAt() > 0) {
            resp.put("timerStartedAt", board.getTimerStartedAt());
            resp.put("timerDurationMs", board.getTimerDurationMs());
        }
        return resp;
    }

    /**
     * WebSocket join handler for retrospective boards.
     * Uses atomic Redis operations to enforce user limit.
     * IMPORTANT: Errors include current state so existing users don't lose sync.
     */
    @MessageMapping("/retro.join.{roomId}")
    public void join(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            messagingTemplate.convertAndSend("/topic/retro." + roomId,
                Map.of("error", "Invalid room code format", "allowed", false));
            return;
        }
        
        // Validate input size to prevent oversized payloads
        if (roomId != null && roomId.length() > 100) {
            messagingTemplate.convertAndSend("/topic/retro." + roomId,
                Map.of("error", "Room code is too long", "allowed", false));
            return;
        }
        
        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) {
            messagingTemplate.convertAndSend("/topic/retro." + roomId,
                Map.of("error", "Board not found", "allowed", false));
            return;
        }
        
        String userId = payload.get("userId");
        String userName = payload.get("userName");
        
        // Validate userId and userName size
        if (userId != null && userId.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/retro." + roomId,
                Map.of("error", "User ID is too long", "allowed", false));
            return;
        }
        if (userName != null && userName.length() > 1000) {
            messagingTemplate.convertAndSend("/topic/retro." + roomId,
                Map.of("error", "User name is too long", "allowed", false));
            return;
        }
        
        // Atomic join check using presence service (handles rejoin scenarios)
        RedisRoomPresenceService.JoinResult joinResult = redisRoomPresenceService.tryJoinRoom(roomId, userId);
        
        if (joinResult == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
            log.info("User {} denied join to retro board {} - board is full", userId, roomId);
            // Send error with rejectedUserId so frontend can identify which user was rejected
            // Include current state so existing users stay in sync
            messagingTemplate.convertAndSend("/topic/retro." + roomId, Map.of(
                    "error", "This room is full (max 10 participants).",
                    "roomFull", true,
                    "allowed", false,
                    "rejectedUserId", userId,  // CRITICAL: Identify which user was rejected
                    "state", board.getColumns(),  // Include state so existing users stay in sync
                    "names", board.getUserNames()  // Include names so existing users stay in sync
            ));
            return;
        } else if (joinResult == RedisRoomPresenceService.JoinResult.SUCCESS) {
            // User successfully joined (either new or rejoin/refresh)
            // Add to board model if not already present
            // Note: If user is not in board model but Redis allowed join, it means:
            // 1. User was just added to Redis (new join) - need to check board capacity
            // 2. User was already in Redis (rejoin) - should already be in board model, but if not, add them
            if (!board.getUserNames().containsKey(userId)) {
                // CRITICAL: Always check Redis count AND board model count before adding
                // This ensures we never exceed 10 users even if there's a sync issue
                int redisUserCount = redisRoomPresenceService.getUserCount(roomId);
                int boardUserCount = board.getUserNames().size();
                
                // STRICT CHECK: If Redis has more than 10 users, always reject
                if (redisUserCount > 10) {
                    log.error("CRITICAL: Room {} has {} users in Redis (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, redisUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    messagingTemplate.convertAndSend("/topic/retro." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", board.getColumns(),
                            "names", board.getUserNames()
                    ));
                    return;
                }
                
                // If board model already has 10 users, reject (this is 11th+ user)
                // This is the key check - board model should never have more than 10 users
                if (boardUserCount >= 10) {
                    log.error("CRITICAL: Board {} model already has {} users (limit is 10) - REJECTING user {} to maintain limit!", 
                        roomId, boardUserCount, userId);
                    redisRoomPresenceService.removeUser(roomId, userId);
                    messagingTemplate.convertAndSend("/topic/retro." + roomId, Map.of(
                            "error", "This room is full (max 10 participants).",
                            "roomFull", true,
                            "allowed", false,
                            "rejectedUserId", userId,
                            "state", board.getColumns(),
                            "names", board.getUserNames()
                    ));
                    return;
                }
                
                // If Redis has 10 users and board has 9, this is the 10th user - allow
                // If Redis has < 10 users, safe to add
                // At this point, we know boardUserCount < 10, so it's safe to add
                board.addUser(userId, userName);
                retrospectiveService.save(board);
            }
            
            // Update activity (heartbeat) - user is active
            redisRoomPresenceService.updateUserActivity(roomId, userId);
            redisRoomPresenceService.refreshRoomTTL(roomId);
            
            // Broadcast success to all users so they get updated state
            var joinResp = boardResponse(board);
            joinResp.put("allowed", true);
            messagingTemplate.convertAndSend("/topic/retro." + roomId, joinResp);
        } else {
            // Error or invalid input
            log.warn("Join failed for user {} to retro board {}: {}", userId, roomId, joinResult);
            // Send error with current state so existing users don't lose sync
            messagingTemplate.convertAndSend("/topic/retro." + roomId, Map.of(
                    "error", "Unable to join board",
                    "allowed", false,
                    "state", board.getColumns(),  // Include state so existing users stay in sync
                    "names", board.getUserNames()  // Include names so existing users stay in sync
            ));
        }
    }

    @MessageMapping("/retro.card.add.{roomId}")
    @SendTo("/topic/retro.{roomId}")
    public Map<String, Object> addCard(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }
        
        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) return Map.of("error", "Board not found");
        
        String column = payload.get("column");
        String text = payload.get("text");
        String userId = payload.get("userId");
        String userName = payload.get("userName");

        if (text == null || text.trim().isEmpty()) {
            return Map.of("error", "Card text cannot be empty");
        }
        if (text.length() > 180) {
            return Map.of("error", "Card text exceeds maximum length of 180 characters");
        }

        board.addCard(column, text, userId, userName);
        retrospectiveService.save(board);
        
        // Update user activity (heartbeat) - user is active
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);

        return boardResponse(board);
    }

    @MessageMapping("/retro.card.delete.{roomId}")
    @SendTo("/topic/retro.{roomId}")
    public Map<String, Object> deleteCard(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }
        
        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) return Map.of("error", "Board not found");
        
        String column = payload.get("column");
        String cardId = payload.get("cardId");
        String userId = payload.get("userId");

        if (userId == null || userId.isEmpty()) {
            return Map.of("error", "User ID required");
        }

        boolean deleted = board.deleteCard(column, cardId, userId);
        if (!deleted) {
            return Map.of("error", "Only the card author or host can delete this note");
        }
        retrospectiveService.save(board);

        // Update user activity (heartbeat) - user is active
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);

        return boardResponse(board);
    }

    @MessageMapping("/retro.card.upvote.{roomId}")
    @SendTo("/topic/retro.{roomId}")
    public Map<String, Object> upvoteCard(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        // Validate room ID format
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }
        
        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) return Map.of("error", "Board not found");
        
        String column = payload.get("column");
        String cardId = payload.get("cardId");
        String userId = payload.get("userId");

        if (userId == null || userId.isEmpty()) {
            return Map.of("error", "User ID required");
        }

        if (!board.canVote(userId)) {
            return Map.of("error", "Vote limit reached (max 5 votes)", "remainingVotes", 0);
        }

        boolean voted = board.upvoteCard(column, cardId, userId);
        if (!voted) {
            return Map.of("error", "Could not vote on this card");
        }
        retrospectiveService.save(board);

        // Update user activity (heartbeat) - user is active
        redisRoomPresenceService.updateUserActivity(roomId, userId);
        redisRoomPresenceService.refreshRoomTTL(roomId);

        var resp = boardResponse(board);
        resp.put("remainingVotes", board.getRemainingVotes(userId));
        return resp;
    }

    /**
     * WebSocket leave handler for retrospective boards.
     * Removes user from Redis set and board model.
     */
    @MessageMapping("/retro.leave.{roomId}")
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
        
        // Remove from board model
        retrospectiveService.removeUser(roomId, userId);
    }

    @MessageMapping("/retro.timer.start.{roomId}")
    @SendTo("/topic/retro.{roomId}")
    public Map<String, Object> startTimer(@Payload Map<String, String> payload, @DestinationVariable("roomId") String roomId) {
        if (!isValidRoomId(roomId)) {
            return Map.of("error", "Invalid room code format");
        }

        RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
        if (board == null) return Map.of("error", "Board not found");

        String userId = payload.get("userId");
        if (userId == null || !board.isHost(userId)) {
            return Map.of("error", "Only the host can start the timer");
        }

        board.setTimerStartedAt(System.currentTimeMillis());
        retrospectiveService.save(board);

        return boardResponse(board);
    }
}
