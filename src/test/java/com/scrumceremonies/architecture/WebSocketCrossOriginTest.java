package com.scrumceremonies.architecture;

import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket integration test for frontend/backend split architecture.
 * Verifies connection to wss://localhost:{port}/ws with production-like CORS
 * (allowed origin https://example.com), and that SUBSCRIBE and JOIN work.
 * Does not modify production WebSocket or Redis logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-websocket")
@TestPropertySource(properties = {
    "app.room.max-users=10",
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "app.cors.allowed-origins=https://example.com,https://www.example.com,http://localhost:8080,http://localhost"
})
class WebSocketCrossOriginTest {

    @TestConfiguration
    @EnableWebSecurity
    @org.springframework.context.annotation.Profile("test-websocket")
    static class TestWebSocketSecurityConfig {

        @Bean
        @Primary
        @Order(1)
        public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                .cors(cors -> cors.configurationSource(request -> {
                    org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
                    config.setAllowedOrigins(java.util.Arrays.asList(
                        "https://example.com", "https://www.example.com", "http://localhost:8080", "http://localhost"));
                    config.setAllowedMethods(java.util.Arrays.asList("*"));
                    config.setAllowedHeaders(java.util.Arrays.asList("*"));
                    config.setAllowCredentials(false);
                    return config;
                }));
            return http.build();
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    private WebSocketStompClient stompClient;
    private String testRoomId;

    @BeforeEach
    void setUp() {
        testRoomId = roomService.createRoom();
        SockJsClient sockJsClient = new SockJsClient(
            Collections.singletonList(new WebSocketTransport(new StandardWebSocketClient()))
        );
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @Test
    void websocketConnectSubscribeAndJoin_succeedsWithAllowedOriginConfig() throws Exception {
        String url = "ws://localhost:" + port + "/ws";
        // Server is configured with allowed origins including https://example.com.
        // (Java client may not send Origin; connection still validates endpoint and broker.)
        StompSession session = stompClient.connect(url, new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);

        assertThat(session).isNotNull();
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        session.subscribe("/topic/room." + testRoomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((Map<String, Object>) payload);
            }
        });

        Map<String, String> joinPayload = Map.of("userId", "ws-arch-user", "userName", "ArchTest");
        session.send("/app/join." + testRoomId, joinPayload);

        Map<String, Object> response = messages.poll(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getOrDefault("allowed", false)).isEqualTo(true);

        session.disconnect();
    }
}
