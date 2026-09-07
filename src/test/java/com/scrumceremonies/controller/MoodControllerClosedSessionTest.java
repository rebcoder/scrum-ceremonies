package com.scrumceremonies.controller;

import com.scrumceremonies.config.TestSecurityConfig;
import com.scrumceremonies.model.MoodRoom;
import com.scrumceremonies.service.MoodService;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = MoodController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class MoodControllerClosedSessionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private MoodService moodService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private RoomLimitService roomLimitService;
    @MockitoBean private RedisRoomPresenceService redisRoomPresenceService;
    @MockitoBean private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    @Test
    @DisplayName("join rejects when results revealed and all users submitted")
    void joinRejectsClosedSession() throws Exception {
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setResultsRevealed(true);
        // Add a user and mark them as submitted so allUsersSubmitted() returns true
        room.addUser("user1", "Alice");
        room.submitResponse("user1", Map.of("mood", "happy"));

        when(moodService.getRoom("test1234")).thenReturn(room);

        mockMvc.perform(post("/api/mood/join").param("roomId", "test1234"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("session has ended")));
    }

    @Test
    @DisplayName("join rejects when session ended by host")
    void joinRejectsEndedSession() throws Exception {
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setSessionEnded(true);
        when(moodService.getRoom("test1234")).thenReturn(room);

        mockMvc.perform(post("/api/mood/join").param("roomId", "test1234"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("ended by the host")));
    }

    @Test
    @DisplayName("join proceeds when results revealed but not all submitted")
    void joinAllowedWhenResultsRevealedButNotAllSubmitted() throws Exception {
        MoodRoom room = new MoodRoom();
        room.setRoomId("test1234");
        room.setResultsRevealed(true);
        // Add user but don't submit - allUsersSubmitted() returns false
        room.addUser("user1", "Alice");
        when(moodService.getRoom("test1234")).thenReturn(room);
        when(redisRoomPresenceService.getUserCount("test1234")).thenReturn(1);

        mockMvc.perform(post("/api/mood/join").param("roomId", "test1234"))
                .andExpect(status().isOk());
    }
}
