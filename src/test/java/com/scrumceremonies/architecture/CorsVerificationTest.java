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
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CORS configuration for frontend/backend split.
 * Allowed origins: https://example.com, https://www.example.com (and localhost for dev).
 * Unknown origins must not receive Access-Control-Allow-Origin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "app.cors.allowed-origins=https://example.com,https://www.example.com,http://localhost:8080,http://localhost"
})
class CorsVerificationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void allowedOrigin_example_com_returns200_withCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://example.com");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/actuator/health",
            HttpMethod.GET,
            entity,
            String.class
        );

        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        String allowOrigin = response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).as("CORS header must be present for allowed origin").isEqualTo("https://example.com");
    }

    @Test
    void allowedOrigin_www_example_com_returns200_withCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://www.example.com");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/actuator/health",
            HttpMethod.GET,
            entity,
            String.class
        );

        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        String allowOrigin = response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).as("CORS header must be present for allowed origin").isEqualTo("https://www.example.com");
    }

    @Test
    void disallowedOrigin_evil_com_doesNotReceiveCorsAllowOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://evil.com");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
            baseUrl() + "/actuator/health",
            HttpMethod.GET,
            entity,
            String.class
        );

        // Disallowed origin: either request is blocked (403) or response has no CORS header
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN);
        if (response.getStatusCode() != HttpStatus.FORBIDDEN) {
            String allowOrigin = response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
            assertThat(allowOrigin).as("Disallowed origin must not receive Access-Control-Allow-Origin").isNullOrEmpty();
        }
    }
}
