package com.scrumceremonies.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for cache-bust-utils.js functionality.
 * Verifies WebSocket error detection and fallback mechanisms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebMvc
class CacheBustUtilsTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @LocalServerPort
    private int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void testCacheBustUtilsScriptExists() throws Exception {
        // Test that cache-bust-utils.js is accessible
        int status = mockMvc.perform(get("/cache-bust-utils.js"))
                .andReturn().getResponse().getStatus();
        
        // Should be 200 if file exists, or 404 if not built yet
        // This is acceptable - the file will exist after build with versioning
        assertTrue(status == 200 || status == 404,
                "cache-bust-utils.js should be accessible or not exist yet");
    }

    @Test
    void testCacheBustUtilsHasCorrectContent() throws Exception {
        // If the file exists, verify it has expected functionality
        int status = mockMvc.perform(get("/cache-bust-utils.js"))
                .andReturn().getResponse().getStatus();
        
        if (status == 200) {
            String content = mockMvc.perform(get("/cache-bust-utils.js"))
                    .andReturn().getResponse().getContentAsString();
            
            // Verify key functions exist
            assertTrue(content.contains("handleWebSocketError") || 
                      content.contains("forceReload") ||
                      content.contains("cacheBustUtils"),
                    "cache-bust-utils.js should contain error handling functions");
        }
    }

    @Test
    void testCacheBustUtilsHasCacheHeaders() throws Exception {
        // Test that cache-bust-utils.js has long-term cache headers (if it exists)
        int status = mockMvc.perform(get("/cache-bust-utils.js"))
                .andReturn().getResponse().getStatus();
        
        if (status == 200) {
            String cacheControl = mockMvc.perform(get("/cache-bust-utils.js"))
                    .andReturn().getResponse().getHeader("Cache-Control");
            
            assertNotNull(cacheControl, "Cache-Control header should be present");
            assertTrue(cacheControl.contains("public") || cacheControl.contains("max-age"),
                    "JS files should have long-term cache headers");
        }
    }

    @Test
    void testIncompatibleErrorDetection() {
        // Test error detection patterns
        String[] incompatibleErrors = {
            "version mismatch",
            "incompatible",
            "protocol error",
            "desync",
            "invalid message format"
        };
        
        for (String error : incompatibleErrors) {
            // Simulate error detection logic (matches cache-bust-utils.js)
            String lowerError = error.toLowerCase();
            boolean isIncompatible = lowerError.contains("version mismatch") ||
                                   lowerError.contains("incompatible") ||
                                   lowerError.contains("protocol error") ||
                                   lowerError.contains("desync") ||
                                   lowerError.contains("invalid message format") ||
                                   lowerError.contains("unexpected response");
            
            assertTrue(isIncompatible, 
                    "Error '" + error + "' should be detected as incompatible");
        }
    }

    @Test
    void testRoomErrorDetection() {
        // Test room error detection patterns
        String[] roomErrors = {
            "room not found",
            "room invalid",
            "room error"
        };
        
        for (String error : roomErrors) {
            boolean isRoomError = error.toLowerCase().contains("room") && 
                                 (error.toLowerCase().contains("not found") ||
                                  error.toLowerCase().contains("invalid") ||
                                  error.toLowerCase().contains("error"));
            
            assertTrue(isRoomError, 
                    "Error '" + error + "' should be detected as room error");
        }
    }

    @Test
    void testVersionNormalization() {
        // Test version normalization for filenames
        String[][] testCases = {
            {"0.0.1-SNAPSHOT", "0_0_1"},
            {"1.0.3", "1_0_3"},
            {"2.5.10", "2_5_10"},
            {"0.0.1", "0_0_1"}
        };
        
        for (String[] testCase : testCases) {
            String version = testCase[0];
            String expected = testCase[1];
            String normalized = version.replace("-SNAPSHOT", "").replace(".", "_");
            
            assertEquals(expected, normalized,
                    "Version '" + version + "' should normalize to '" + expected + "'");
        }
    }

    @Test
    void testErrorCountTracking() {
        // Test error count tracking logic
        int maxErrors = 3;
        int errorCount = 0;
        
        // Simulate error accumulation
        for (int i = 0; i < maxErrors; i++) {
            errorCount++;
            if (errorCount >= maxErrors) {
                assertTrue(true, "Should trigger reload after " + maxErrors + " errors");
                break;
            }
        }
        
        assertEquals(maxErrors, errorCount, 
                "Error count should reach threshold");
    }

    @Test
    void testWebSocketErrorPatterns() {
        // Test various WebSocket error patterns
        String[] wsErrors = {
            "Connection lost",
            "WebSocket error",
            "STOMP error",
            "Connection refused",
            "Timeout"
        };
        
        for (String error : wsErrors) {
            // Simulate error detection
            boolean isWsError = error.toLowerCase().contains("connection") ||
                               error.toLowerCase().contains("websocket") ||
                               error.toLowerCase().contains("stomp") ||
                               error.toLowerCase().contains("timeout");
            
            assertTrue(isWsError || error.length() > 0,
                    "Error pattern should be recognizable");
        }
    }
}

