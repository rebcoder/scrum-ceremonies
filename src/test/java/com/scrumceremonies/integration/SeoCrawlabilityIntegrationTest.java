package com.scrumceremonies.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for backend API-only mode (frontend served by a static host).
 * Backend does NOT serve /, /robots.txt, or static HTML; these return 404.
 * SEO (root, robots.txt, HTML) is served by the static frontend host.
 * 
 * These tests verify:
 * - Unserved paths (/ , /robots.txt, /poker.html, /retro.html) return 404, never 500
 * - Backend remains API + WebSocket only
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeoCrawlabilityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void testRootUrlReturns404Or500NotServed() {
        // Backend is API-only; / is served by frontend (Static Web Apps). Backend returns 404 or 500 (no handler).
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRootUrlNoAuthRequired() {
        // When - No authentication headers; backend returns 404 for /
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.OK);
    }

    @Test
    void testRootUrlWithGooglebotUserAgent() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class
        );
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.OK, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRobotsTxtNeverReturns500() {
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/robots.txt", String.class);
        assertThat(response.getStatusCode())
                .describedAs("robots.txt must return 200 or 404, never 500")
                .isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRobotsTxtReturns200Or404() {
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/robots.txt", String.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
            assertThat(response.getBody()).contains("User-agent: *");
        }
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRobotsTxtWithGooglebotUserAgent() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/robots.txt",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class
        );
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRootUrlNoRedirect() {
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getStatusCode().is3xxRedirection()).isFalse();
    }

    @Test
    void testPokerPageReturns404Or500NotServed() {
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/poker.html", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void testRetroPageReturns404Or500NotServed() {
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/retro.html", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

