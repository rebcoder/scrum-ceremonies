package com.scrumceremonies.service;

import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.model.RetroCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RetrospectiveService {
    public static final String RETRO_KEY_PREFIX = "retro:";

    private final RedisTemplate<String, RetrospectiveBoard> redisTemplate;

    @Autowired
    private SimpMessagingTemplate messageTemplate;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    public RetrospectiveService(@Qualifier("retroRedisTemplate") RedisTemplate<String, RetrospectiveBoard> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String createBoard() {
        String roomId = java.util.UUID.randomUUID().toString().substring(0, 8);
        RetrospectiveBoard board = new RetrospectiveBoard();
        board.setRoomId(roomId);
        board.updateLastActivityTime(); // Initialize lastActivityTime for room limit tracking
        redisTemplate.opsForValue().set(RETRO_KEY_PREFIX + roomId, board, 8, TimeUnit.HOURS);
        return roomId;
    }

    @Cacheable(value = "retroBoards", key = "#roomId", unless = "#result == null")
    public RetrospectiveBoard getBoard(String roomId) {
        return redisTemplate.opsForValue().get(RETRO_KEY_PREFIX + roomId);
    }

    @CacheEvict(value = "retroBoards", key = "#board.roomId")
    public void save(RetrospectiveBoard board) {
        redisTemplate.opsForValue().set(RETRO_KEY_PREFIX + board.getRoomId(), board, 8, TimeUnit.HOURS);
    }

    public Map<String, java.util.List<RetroCard>> getState(String roomId) {
        RetrospectiveBoard board = getBoard(roomId);
        return board != null ? board.getColumns() : java.util.Map.of();
    }
    
    /**
     * Gets full board state including columns, user names, and host information
     */
    public Map<String, Object> getFullState(String roomId) {
        try {
            RetrospectiveBoard board = getBoard(roomId);
            if (board != null) {
            Map<String, Object> state = new java.util.HashMap<>();
            if (board.getColumns() == null) {
                state.put("wentWell", java.util.Collections.emptyList());
                state.put("toImprove", java.util.Collections.emptyList());
                state.put("actionItems", java.util.Collections.emptyList());
                state.put("names", board.getUserNames() != null ? board.getUserNames() : java.util.Collections.emptyMap());
                state.put("hostName", board.getHostName());
                state.put("hasHost", board.getHostName() != null && !board.getHostName().isEmpty());
                return state;
            }
            state.put("wentWell", board.getColumns().getOrDefault("wentWell", java.util.Collections.emptyList()));
            state.put("toImprove", board.getColumns().getOrDefault("toImprove", java.util.Collections.emptyList()));
            state.put("actionItems", board.getColumns().getOrDefault("actionItems", java.util.Collections.emptyList()));
            state.put("names", board.getUserNames() != null ? board.getUserNames() : java.util.Collections.emptyMap());
            // Add host name (the first user who joined, stored in board)
            String hostName = board.getHostName();
            state.put("hostName", hostName);
            state.put("hasHost", hostName != null && !hostName.isEmpty());
            return state;
        } else {
            return Map.of(
                    "wentWell", java.util.Collections.emptyList(),
                    "toImprove", java.util.Collections.emptyList(),
                    "actionItems", java.util.Collections.emptyList(),
                    "names", java.util.Collections.emptyMap(),
                    "hostName", null,
                    "hasHost", false
            );
        }
        } catch (Exception e) {
            return Map.of(
                    "wentWell", java.util.Collections.emptyList(),
                    "toImprove", java.util.Collections.emptyList(),
                    "actionItems", java.util.Collections.emptyList(),
                    "names", java.util.Collections.emptyMap(),
                    "hostName", null,
                    "hasHost", false
            );
        }
    }

    /**
     * Finds all retro board keys using SCAN (safe for production use)
     */
    public Set<String> getAllBoardKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(RETRO_KEY_PREFIX + "*")
                .count(100) // batch size
                .build();

        try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory()
                .getConnection()
                .scan(options)) {

            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        }
        return keys;
    }

    /**
     * Deletes a retro board and its associated presence hash.
     * Deletes both keys to prevent orphaned presence data that could cause:
     * - Ghost users
     * - False "room full" detection
     * - Incorrect concurrent join behavior
     */
    public void deleteBoard(String roomId) {
        String boardKey = RETRO_KEY_PREFIX + roomId;
        String presenceKey = "room:" + roomId + ":presence";
        
        // Delete board key using typed template (handles serialization)
        redisTemplate.delete(boardKey);
        
        // Delete presence hash using string template
        stringRedisTemplate.delete(presenceKey);
    }

    /**
     * Removes a user from a retro board
     */
    @CacheEvict(value = "retroBoards", key = "#roomId")
    public void removeUser(String roomId, String userId) {
        RetrospectiveBoard board = getBoard(roomId);
        if (board != null) {
            board.getUserNames().remove(userId);
            save(board);

            // Broadcast the updated user list
            Map<String, Object> state = Map.of(
                    "state", board.getColumns(),
                    "names", board.getUserNames()
            );
            messageTemplate.convertAndSend("/topic/retro." + roomId, state);
        }
    }
}
