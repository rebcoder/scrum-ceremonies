package com.scrumceremonies.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures backend does NOT serve frontend files (API-only architecture).
 * Requests to /, index.html, script.js, style.css must return 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticFileRemovalTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void root_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void indexHtml_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void scriptJs_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/script.js", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void styleCss_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/style.css", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
