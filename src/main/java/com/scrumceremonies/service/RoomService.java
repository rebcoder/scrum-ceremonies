package com.scrumceremonies.service;

import com.scrumceremonies.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    public static final String ROOM_KEY_PREFIX = "room:";
    private final RedisTemplate<String, Room> redisTemplate;
    @Autowired
    private SimpMessagingTemplate messageTemplate;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    public RoomService(RedisTemplate<String, Room> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String createRoom() {
        String roomId = generateRoomId();
        Room room = new Room();
        room.setRoomId(roomId);
        room.updateLastActivityTime();
        redisTemplate.opsForValue().set(ROOM_KEY_PREFIX + roomId, room, 8, TimeUnit.HOURS);
        return roomId;
    }

    @Cacheable(value = "rooms", key = "#roomId", unless = "#result == null")
    public Room getRoom(String roomId) {
        return redisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomId);
    }

    @CacheEvict(value = {"rooms", "roomStates"}, key = "#roomId")
    public void addOrUpdateVote(String roomId, String userId, String vote) {
        Room room = getRoom(roomId);
        if (room != null && room.getUserNames().containsKey(userId)) {
            room.addOrUpdateVote(userId, vote);
            redisTemplate.opsForValue().set(ROOM_KEY_PREFIX + roomId, room, 8, TimeUnit.HOURS);
        }
    }

    public Map<String, String> getVotes(String roomId) {
        Room room = getRoom(roomId);
        return room != null ? room.getVotesAsStrings() : Collections.emptyMap();
    }

    @CacheEvict(value = {"rooms", "roomStates"}, key = "#roomId")
    public void revealVotes(String roomId) {
        Room room = getRoom(roomId);
        if (room != null) {
            room.setRevealed(true);
            redisTemplate.opsForValue().set(ROOM_KEY_PREFIX + roomId, room, 8, TimeUnit.HOURS);
        }
    }

    @CacheEvict(value = {"rooms", "roomStates"}, key = "#roomId")
    public void clearVotes(String roomId) {
        Room room = getRoom(roomId);
        if (room != null) {
            room.getUserVotes().clear();
            room.setRevealed(false);
            redisTemplate.opsForValue().set(ROOM_KEY_PREFIX + roomId, room, 8, TimeUnit.HOURS);
        }
    }

    public Map<String, String> getUserNames(String roomId) {
        Room room = getRoom(roomId);
        return room != null ? room.getUserNames() : Collections.emptyMap();
    }

    @CacheEvict(value = {"rooms", "roomStates"}, key = "#roomId")
    public boolean addUserName(String roomId, String userId, String name) {
        // Re-fetch room to get latest state (important for concurrent requests)
        Room room = getRoom(roomId);
        if (room != null) {
            // Check if user already exists
            if (room.getUserNames().containsKey(userId)) {
                return true;  // User already in room
            }
            
            // Check capacity with latest state
            if (!room.canAddUser()) {
                return false;  // Room is full
            }
            
            // Add user and save
            room.addUserName(userId, name);
            redisTemplate.opsForValue().set(
                    ROOM_KEY_PREFIX + roomId,
                    room,
                    8,
                    TimeUnit.HOURS
            );
            return true;
        }
        return false;
    }

    public boolean isRevealed(String roomId) {
        Room room = getRoom(roomId);
        return room != null && room.isRevealed();
    }

    private String generateRoomId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }


    @CacheEvict(value = {"rooms", "roomStates"}, key = "#room.roomId")
    public void saveRoom(Room room) {
        redisTemplate.opsForValue().set(
                ROOM_KEY_PREFIX + room.getRoomId(),
                room,
                8, // TTL in hours — matches createRoom's TTL and the presence-hash TTL (all three must agree, see CONTRIBUTING.md)
                TimeUnit.HOURS
        );
    }

//    public void removeUser(String roomId, String userId) {
//        Room room = getRoom(roomId);
//        if (room != null) {
//            room.getUserVotes().remove(userId);
//            room.getUserNames().remove(userId);
//            saveRoom(room);
//        }
//    }

    @Cacheable(value = "roomStates", key = "#roomId", unless = "#result == null || #result.isEmpty()")
    public Map<String, Object> getRoomState(String roomId) {
        Map<String, Object> state = new HashMap<>();
        Room room = getRoom(roomId);
        if (room != null) {
            state.put("votes", getVotes(roomId));
            state.put("names", getUserNames(roomId));
            state.put("revealed", isRevealed(roomId));
            // Add host name (the first user who joined, stored in room)
            String hostName = room.getHostName();
            state.put("hostName", hostName);
            state.put("hasHost", hostName != null && !hostName.isEmpty());
        } else {
            state.put("votes", Collections.emptyMap());
            state.put("names", Collections.emptyMap());
            state.put("revealed", false);
            state.put("hostName", null);
            state.put("hasHost", false);
        }
        return state;
    }
    @CacheEvict(value = {"rooms", "roomStates"}, key = "#roomId")
    public void removeUser(String roomId, String userId) {
        Room room = getRoom(roomId);
        if (room != null) {
            room.getUserVotes().remove(userId);
            room.getUserNames().remove(userId);
            saveRoom(room);

            // Broadcast the updated user list
            Map<String, Object> state = getRoomState(roomId);
            messageTemplate.convertAndSend("/topic/room." + roomId + ".votes", state);
        }
    }


    // Add these methods to your RoomService.java

    /**
     * Restricts the SCAN in {@link #getAllRoomKeys()} to keys shaped like an actual room key
     * (the {@code room:} prefix plus an 8-character alphanumeric id). A bare {@code room:*}
     * pattern also matches presence sub-keys such as {@code room:<id>:users}; reading one of
     * those back as a {@code Room} throws a WRONGTYPE error and aborts the scan. This is a
     * read-side filter only — it does not change how any key is named.
     */
    private static final java.util.regex.Pattern ROOM_KEY_SHAPE =
            java.util.regex.Pattern.compile("^" + ROOM_KEY_PREFIX + "[a-zA-Z0-9]{8}$");

    /**
     * Finds all room keys using SCAN (safe for production use).
     */
    public Set<String> getAllRoomKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(ROOM_KEY_PREFIX + "*")
                .count(100) // batch size
                .build();

        try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory()
                .getConnection()
                .scan(options)) {

            while (cursor.hasNext()) {
                String key = new String(cursor.next());
                if (ROOM_KEY_SHAPE.matcher(key).matches()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * Gets a room if it exists
     */
    public Room getRoomIfExists(String roomId) {
        return (Room) redisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomId);
    }

    /**
     * Deletes a room and its associated presence hash.
     * Deletes both keys to prevent orphaned presence data that could cause:
     * - Ghost users
     * - False "room full" detection
     * - Incorrect concurrent join behavior
     */
    public void deleteRoom(String roomId) {
        String roomKey = ROOM_KEY_PREFIX + roomId;
        String presenceKey = "room:" + roomId + ":presence";
        
        // Delete room key using typed template (handles serialization)
        redisTemplate.delete(roomKey);
        
        // Delete presence hash using string template
        stringRedisTemplate.delete(presenceKey);
    }
}
