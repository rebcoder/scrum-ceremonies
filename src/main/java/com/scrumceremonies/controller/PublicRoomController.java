package com.scrumceremonies.controller;

import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.model.Room;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RetrospectiveService;
import com.scrumceremonies.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller
public class PublicRoomController {

    @Autowired
    private RoomService roomService;

    @Autowired
    private RetrospectiveService retrospectiveService;

    @Autowired
    private MoodService moodService;

    /**
     * Public endpoint to get room information for sharing
     */
    @GetMapping("/api/rooms/{roomType}/{roomId}/public")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getPublicRoomInfo(
            @PathVariable String roomType,
            @PathVariable String roomId) {
        
        if (!isValidRoomId(roomId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid room ID format"));
        }

        Map<String, Object> response = new HashMap<>();
        
        try {
            switch (roomType.toLowerCase()) {
                case "poker":
                    Room room = roomService.getRoom(roomId);
                    if (room == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Room not found or expired"));
                    }
                    response.put("roomId", roomId);
                    response.put("roomType", "poker");
                    String hostName = getHostName(room);
                    response.put("hostName", hostName);
                    response.put("hasHost", hostName != null && !hostName.isEmpty());
                    response.put("expiresInMinutes", 480); // 8 hours default
                    break;
                    
                case "retro":
                    RetrospectiveBoard board = retrospectiveService.getBoard(roomId);
                    if (board == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Board not found or expired"));
                    }
                    response.put("roomId", roomId);
                    response.put("roomType", "retro");
                    String retroHostName = getHostName(board);
                    response.put("hostName", retroHostName);
                    response.put("hasHost", retroHostName != null && !retroHostName.isEmpty());
                    response.put("expiresInMinutes", 480); // 8 hours default
                    break;
                    
                case "mood":
                    MoodRoom moodRoom = moodService.getRoom(roomId);
                    if (moodRoom == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Room not found or expired"));
                    }
                    response.put("roomId", roomId);
                    response.put("roomType", "mood");
                    String moodHostName = getHostName(moodRoom);
                    response.put("hostName", moodHostName);
                    response.put("hasHost", moodHostName != null && !moodHostName.isEmpty());
                    response.put("expiresInMinutes", 480); // 8 hours default
                    break;
                    
                default:
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Invalid room type"));
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve room information"));
        }
    }

    private boolean isValidRoomId(String roomId) {
        return roomId != null && !roomId.isEmpty() && roomId.matches("^[a-zA-Z0-9]{8}$");
    }

        private String getFirstUserName(Map<String, String> userNames) {
            if (userNames == null || userNames.isEmpty()) {
                return null; // Return null to indicate no users yet
            }
            return userNames.values().iterator().next();
        }
        
        private String getHostName(Room room) {
            return room != null ? room.getHostName() : null;
        }
        
        private String getHostName(RetrospectiveBoard board) {
            return board != null ? board.getHostName() : null;
        }
        
        private String getHostName(MoodRoom moodRoom) {
            return moodRoom != null ? moodRoom.getHostName() : null;
        }
}

