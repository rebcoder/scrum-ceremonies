package com.scrumceremonies.controller;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RedisRoomUserService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.service.RoomService;
import com.scrumceremonies.service.VisitorAnalyticsService;
import com.scrumceremonies.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = RoomController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerHappyPathTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private RoomService roomService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private RoomLimitService roomLimitService;
    @MockitoBean private RedisRoomUserService redisRoomUserService;
    @MockitoBean private RedisRoomPresenceService redisRoomPresenceService;
    @MockitoBean private SimpMessagingTemplate simpMessagingTemplate;
    @MockitoBean private VisitorAnalyticsService visitorAnalyticsService;
    @MockitoBean private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    @Nested
    @DisplayName("POST /api/create-room")
    class CreateRoom {

        @Test
        @DisplayName("creates room successfully")
        void createsRoomSuccessfully() throws Exception {
            when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
            when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
            when(roomService.createRoom()).thenReturn("abcd1234");

            mockMvc.perform(post("/api/create-room"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("abcd1234"));

            verify(roomLimitService).registerRoomCreation(anyString(), eq("abcd1234"));
        }
    }

    @Nested
    @DisplayName("POST /api/join-room")
    class JoinRoom {

        @Test
        @DisplayName("joins room without userId")
        void joinsRoomWithoutUserId() throws Exception {
            String roomId = "test1234";
            Room room = new Room();
            room.setRoomId(roomId);
            room.setUserNames(new ConcurrentHashMap<>());
            when(roomService.getRoom(roomId)).thenReturn(room);

            mockMvc.perform(post("/api/join-room")
                    .param("roomId", roomId))
                    .andExpect(status().isOk());

            verify(redisRoomPresenceService, never()).tryJoinRoom(anyString(), anyString());
        }

        @Test
        @DisplayName("joins room with userId successfully")
        void joinsRoomWithUserIdSuccessfully() throws Exception {
            String roomId = "test1234";
            String userId = "user1";
            Room room = new Room();
            room.setRoomId(roomId);
            room.setUserNames(new ConcurrentHashMap<>());

            when(roomService.getRoom(roomId)).thenReturn(room);
            when(redisRoomPresenceService.tryJoinRoom(roomId, userId))
                    .thenReturn(RedisRoomPresenceService.JoinResult.SUCCESS);
            when(redisRoomPresenceService.getUsersWithPresence(roomId))
                    .thenReturn(Map.of(userId, "{}"));

            mockMvc.perform(post("/api/join-room")
                    .param("roomId", roomId)
                    .param("userId", userId))
                    .andExpect(status().isOk());

            verify(redisRoomPresenceService).updateUserActivity(roomId, userId);
            verify(redisRoomPresenceService).refreshRoomTTL(roomId);
        }

        @Test
        @DisplayName("syncs Redis users to room model on join")
        void syncsRedisUsersToRoomModel() throws Exception {
            String roomId = "test1234";
            String userId = "user1";
            Room room = new Room();
            room.setRoomId(roomId);
            room.setUserNames(new ConcurrentHashMap<>());

            when(roomService.getRoom(roomId)).thenReturn(room);
            when(redisRoomPresenceService.tryJoinRoom(roomId, userId))
                    .thenReturn(RedisRoomPresenceService.JoinResult.SUCCESS);
            // Redis has two users, room model has none
            Map<Object, Object> redisUsers = new HashMap<>();
            redisUsers.put(userId, "{}");
            redisUsers.put("user2", "{}");
            when(redisRoomPresenceService.getUsersWithPresence(roomId)).thenReturn(redisUsers);

            mockMvc.perform(post("/api/join-room")
                    .param("roomId", roomId)
                    .param("userId", userId))
                    .andExpect(status().isOk());

            verify(roomService).saveRoom(room);
        }
    }

    @Nested
    @DisplayName("GET /api/votes")
    class GetVotes {

        @Test
        @DisplayName("returns votes for valid room")
        void returnsVotesForValidRoom() throws Exception {
            String roomId = "test1234";
            when(roomService.getVotes(roomId)).thenReturn(Map.of("user1", "5", "user2", "8"));

            mockMvc.perform(get("/api/votes").param("roomId", roomId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user1").value("5"))
                    .andExpect(jsonPath("$.user2").value("8"));
        }
    }

    @Nested
    @DisplayName("GET /api/room-state")
    class GetRoomState {

        @Test
        @DisplayName("returns room state for valid room")
        void returnsRoomStateForValidRoom() throws Exception {
            String roomId = "test1234";
            Map<String, Object> state = new HashMap<>();
            state.put("names", Map.of("user1", "Alice"));
            state.put("revealed", false);
            state.put("votes", Map.of());
            when(roomService.getRoomState(roomId)).thenReturn(state);

            mockMvc.perform(get("/api/room-state").param("roomId", roomId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revealed").value(false))
                    .andExpect(jsonPath("$.names.user1").value("Alice"));
        }
    }

    @Nested
    @DisplayName("POST /api/leave-room")
    class LeaveRoom {

        @Test
        @DisplayName("leaves room successfully")
        void leavesRoomSuccessfully() throws Exception {
            String roomId = "test1234";
            String userId = "user1";

            mockMvc.perform(post("/api/leave-room")
                    .param("roomId", roomId)
                    .param("userId", userId))
                    .andExpect(status().isOk());

            verify(redisRoomPresenceService).removeUser(roomId, userId);
            verify(redisRoomUserService).removeUser(roomId, userId);
            verify(roomService).removeUser(roomId, userId);
        }
    }
}
