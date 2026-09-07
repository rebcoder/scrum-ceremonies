package com.scrumceremonies.service;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.model.Vote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RoomCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(RoomCleanupTask.class);

    @Autowired
    private RoomService roomService;

    @Autowired
    private RetrospectiveService retrospectiveService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Scheduled(fixedRate = 60000) // Run every minute
    public void cleanupEmptyRooms() {
        try {
        long currentTime = System.currentTimeMillis();
        long emptyRoomThreshold = 15 * 60 * 1000; // 15 minutes

        roomService.getAllRoomKeys().forEach(roomKey -> {
            String roomId = roomKey.replace(RoomService.ROOM_KEY_PREFIX, "");
            Room room = roomService.getRoomIfExists(roomId);

            if (room != null) {
                // Check if room is empty
                if (room.getUserNames().isEmpty()) {
                    // Room is empty, check if it's been empty for 15 minutes
                    long timeSinceLastActivity = currentTime - room.getLastActivityTime();
                    if (timeSinceLastActivity > emptyRoomThreshold) {
                        roomService.deleteRoom(roomId);
                        log.info("Cleaned up empty room: {}", roomId);
                    }
                }
            }
        });
        } catch (RedisConnectionFailureException | RedisSystemException ex) {
            // Redis down/misconfigured; don't let scheduled tasks spam errors or impact app health.
            log.warn("Skipping room cleanup because Redis is unavailable: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedRate = 60000) // Run every minute
    public void cleanupEmptyRetroBoards() {
        try {
        long currentTime = System.currentTimeMillis();
        long emptyBoardThreshold = 15 * 60 * 1000; // 15 minutes

        retrospectiveService.getAllBoardKeys().forEach(boardKey -> {
            String roomId = boardKey.replace(RetrospectiveService.RETRO_KEY_PREFIX, "");
            var board = retrospectiveService.getBoard(roomId);

            if (board != null) {
                // Check if board is empty
                if (board.getUserNames().isEmpty()) {
                    // Board is empty, check if it's been empty for 15 minutes
                    long timeSinceLastActivity = currentTime - board.getLastActivityTime();
                    if (timeSinceLastActivity > emptyBoardThreshold) {
                        retrospectiveService.deleteBoard(roomId);
                        log.info("Cleaned up empty retro board: {}", roomId);
                    }
                }
            }
        });
        } catch (RedisConnectionFailureException | RedisSystemException ex) {
            log.warn("Skipping retro cleanup because Redis is unavailable: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedRate = 30000) // Run every 30 seconds
    public void cleanupInactiveUsers() {
        try {
        long currentTime = System.currentTimeMillis();
        long userInactivityThreshold = 15 * 60 * 1000; // 15 minutes

        roomService.getAllRoomKeys().forEach(roomKey -> {
            String roomId = roomKey.replace(RoomService.ROOM_KEY_PREFIX, "");
            Room room = roomService.getRoomIfExists(roomId);

            if (room != null) {
                // Check each user's last activity
                room.getUserNames().keySet().forEach(userId -> {
                    Vote vote = room.getUserVotes().get(userId);
                    if (vote != null &&
                            (currentTime - vote.getLastActivity()) > userInactivityThreshold) {
                        roomService.removeUser(roomId, userId);
                        log.info("Removed inactive user: {} from room: {}", userId, roomId);
                    }
                });
            }
        });
        } catch (RedisConnectionFailureException | RedisSystemException ex) {
            log.warn("Skipping inactive user cleanup because Redis is unavailable: {}", ex.getMessage());
        }
    }

    /**
     * Cleanup task for presence-based inactive users.
     * Removes users from presence hash where lastSeen > configured TTL (default 60 seconds).
     * Runs every 30-60 seconds to clean up users who closed tabs, lost network, etc.
     * 
     * This is NON-INVASIVE:
     * - Only removes from presence hash (doesn't delete rooms or votes)
     * - Works alongside existing cleanup tasks
     * - Safe to run even if Redis has issues
     */
    @Scheduled(fixedRate = 45000) // Run every 45 seconds (between 30-60 as requested)
    public void cleanupInactivePresenceUsers() {
        try {
            // Clean up inactive users from poker rooms
            roomService.getAllRoomKeys().forEach(roomKey -> {
                String roomId = roomKey.replace(RoomService.ROOM_KEY_PREFIX, "");
                int removed = redisRoomPresenceService.cleanupInactiveUsers(roomId);
                if (removed > 0) {
                    log.debug("Cleaned up {} inactive users from room {} presence", removed, roomId);
                }
            });
            
            // Clean up inactive users from retro boards
            retrospectiveService.getAllBoardKeys().forEach(boardKey -> {
                String roomId = boardKey.replace(RetrospectiveService.RETRO_KEY_PREFIX, "");
                int removed = redisRoomPresenceService.cleanupInactiveUsers(roomId);
                if (removed > 0) {
                    log.debug("Cleaned up {} inactive users from retro board {} presence", removed, roomId);
                }
            });
        } catch (RedisConnectionFailureException | RedisSystemException ex) {
            log.warn("Skipping presence cleanup because Redis is unavailable: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Error during presence cleanup: {}", ex.getMessage());
            // Don't let cleanup errors break the app
        }
    }
}
