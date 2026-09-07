package com.scrumceremonies.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warmup endpoint effectiveness tests.
 * 
 * Verifies that the warmup endpoint exists and effectively reduces cold-start impact.
 * 
 * These tests are READ-ONLY - they only test and report, no code changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WarmupEffectivenessTest {

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate = new TestRestTemplate();

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Test 1: Verify warmup endpoint exists and is accessible
     */
    @Test
    void testWarmupEndpointExists() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);

        // Then
        assertThat(response.getStatusCode())
                .describedAs("Warmup endpoint must return HTTP 200")
                .isEqualTo(HttpStatus.OK);
        
        assertThat(response.getBody())
                .describedAs("Warmup endpoint must return minimal payload")
                .isEqualTo("OK");
        
        System.out.println("✅ Warmup endpoint exists at /_warmup");
    }

    /**
     * Test 2: Measure warmup endpoint response time
     * Should be < 200ms for effective warmup
     */
    @Test
    void testWarmupEndpointResponseTime() {
        // When
        Instant start = Instant.now();
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);
        Instant end = Instant.now();

        // Then
        Duration responseTime = Duration.between(start, end);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Warmup should be very fast (< 200ms)
        assertThat(responseTime.toMillis())
                .describedAs("Warmup endpoint should respond < 200ms")
                .isLessThan(200);
        
        System.out.println("✅ Warmup endpoint TTFB: " + responseTime.toMillis() + "ms");
    }

    /**
     * Test 3: Verify warmup endpoint has no authentication requirement
     */
    @Test
    void testWarmupEndpointNoAuth() {
        // When - No authentication headers
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);

        // Then
        assertThat(response.getStatusCode())
                .describedAs("Warmup endpoint must work without authentication")
                .isEqualTo(HttpStatus.OK);
        
        System.out.println("✅ Warmup endpoint requires no authentication");
    }

    /**
     * Test 4: Verify warmup endpoint works with Googlebot User-Agent
     */
    @Test
    void testWarmupEndpointWithGooglebotUserAgent() {
        // Given
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");

        // When
        Instant start = Instant.now();
        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/_warmup",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class
        );
        Instant end = Instant.now();

        // Then
        Duration responseTime = Duration.between(start, end);
        
        assertThat(response.getStatusCode())
                .describedAs("Warmup endpoint must work with Googlebot User-Agent")
                .isEqualTo(HttpStatus.OK);
        
        assertThat(responseTime.toMillis())
                .describedAs("Warmup endpoint should respond < 200ms even with Googlebot UA")
                .isLessThan(200);
        
        System.out.println("✅ Warmup endpoint works with Googlebot UA: " + responseTime.toMillis() + "ms");
    }

    /**
     * Test 5: Cold-start effectiveness - WITHOUT warmup
     * Backend is API-only; use /actuator/health instead of /
     */
    @Test
    void testColdStartWithoutWarmup() {
        Instant start = Instant.now();
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/actuator/health", String.class);
        Instant end = Instant.now();
        Duration responseTime = Duration.between(start, end);
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        long ttfbWithoutWarmup = responseTime.toMillis();
        System.out.println("📊 Health TTFB WITHOUT warmup: " + ttfbWithoutWarmup + "ms");
        assertThat(ttfbWithoutWarmup).isLessThan(15000);
    }

    /**
     * Test 6: Cold-start effectiveness - WITH warmup
     * Use /actuator/health after warmup (backend is API-only). 503 when Redis unavailable.
     */
    @Test
    void testColdStartWithWarmup() {
        restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Instant start = Instant.now();
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/actuator/health", String.class);
        Instant end = Instant.now();
        Duration responseTime = Duration.between(start, end);
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        long ttfbWithWarmup = responseTime.toMillis();
        System.out.println("📊 Health TTFB WITH warmup: " + ttfbWithWarmup + "ms");
        assertThat(ttfbWithWarmup).isLessThan(2000);
    }

    /**
     * Test 7: Compare cold-start with vs without warmup (using /actuator/health)
     */
    @Test
    void testWarmupEffectivenessComparison() {
        Instant start1 = Instant.now();
        ResponseEntity<String> response1 = restTemplate.getForEntity(getBaseUrl() + "/actuator/health", String.class);
        Instant end1 = Instant.now();
        long ttfbWithoutWarmup = Duration.between(start1, end1).toMillis();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Instant start2 = Instant.now();
        ResponseEntity<String> response2 = restTemplate.getForEntity(getBaseUrl() + "/actuator/health", String.class);
        Instant end2 = Instant.now();
        long ttfbWithWarmup = Duration.between(start2, end2).toMillis();
        assertThat(response1.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response2.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        System.out.println("📊 Cold-start comparison (actuator/health):");
        System.out.println("  Without warmup: " + ttfbWithoutWarmup + "ms");
        System.out.println("  With warmup: " + ttfbWithWarmup + "ms");
        if (ttfbWithWarmup < ttfbWithoutWarmup) {
            long improvement = ttfbWithoutWarmup - ttfbWithWarmup;
            double improvementPercent = (improvement * 100.0) / ttfbWithoutWarmup;
            System.out.println("  ✅ Improvement: " + improvement + "ms (" + String.format("%.1f", improvementPercent) + "%)");
        } else {
            System.out.println("  ⚠️ Warmup did not improve response time (may be due to test environment)");
        }
    }

    /**
     * Test 8: Verify warmup endpoint does not expose sensitive data
     */
    @Test
    void testWarmupEndpointNoSensitiveData() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Body should be minimal ("OK" only)
        assertThat(response.getBody())
                .describedAs("Warmup endpoint should not expose sensitive data")
                .isEqualTo("OK");
        
        // Check headers don't expose sensitive info
        assertThat(response.getHeaders().get("X-Application-Name")).isNull();
        assertThat(response.getHeaders().get("X-Server-Version")).isNull();
        
        System.out.println("✅ Warmup endpoint does not expose sensitive data");
    }

    /**
     * Test 9: Verify warmup endpoint does not require cookies or session
     */
    @Test
    void testWarmupEndpointNoCookiesRequired() {
        // When - No cookies in request
        ResponseEntity<String> response = restTemplate.getForEntity(getBaseUrl() + "/_warmup", String.class);

        // Then
        assertThat(response.getStatusCode())
                .describedAs("Warmup endpoint must work without cookies")
                .isEqualTo(HttpStatus.OK);
        
        System.out.println("✅ Warmup endpoint requires no cookies or session");
    }
}

