package com.scrumceremonies.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures all REST endpoints under /api/** and actuator are accessible.
 * Backend is API + WebSocket only; these paths must work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiBasePathTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void healthEndpoint_actuator_health_accessible() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/health", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
    }

    /** Unique IP per test to avoid per-session room limit (app.room-limit.max-active-per-session). */
    private HttpHeaders uniqueIpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "10.0.0." + (System.currentTimeMillis() % 255));
        return headers;
    }

    @Test
    void createRoom_api_create_room_accessible() {
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/create-room",
            HttpMethod.POST,
            new HttpEntity<>(uniqueIpHeaders()),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).matches("^[a-zA-Z0-9]{8}$");
    }

    @Test
    void joinRoom_api_join_room_accessible() {
        String roomId = restTemplate.exchange(
            baseUrl() + "/api/create-room",
            HttpMethod.POST,
            new HttpEntity<>(uniqueIpHeaders()),
            String.class
        ).getBody();
        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/api/join-room?roomId=" + roomId,
            HttpMethod.POST,
            new HttpEntity<>(uniqueIpHeaders()),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
