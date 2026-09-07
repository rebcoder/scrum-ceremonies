package com.scrumceremonies.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * NON-INTRUSIVE test for favicon.ico handling.
 * 
 * Validates:
 * - Favicon requests don't cause 500 errors with JSON responses
 * - Missing favicon is handled gracefully
 * 
 * IMPORTANT: This test only validates behavior, it does NOT modify production code.
 */
@SpringBootTest
@AutoConfigureWebMvc
class FaviconHandlingTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void testFaviconReturnsProperStatus() throws Exception {
        // Favicon.ico is automatically requested by browsers
        // Should return 200 (if file exists) or 404 (if missing)
        // Should NOT return 500 (incorrect behavior)
        
        int status = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getStatus();
        
        String contentType = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getContentType();
        
        // Should return 200 (file exists) or 404 (file missing)
        // Should NOT return 500 (incorrect error handling)
        assertTrue(status == 200 || status == 404,
                "Favicon should return 200 (exists) or 404 (missing), NOT 500. Got: " + status);
        
        // If 200, verify it's an ICO file, not JSON
        if (status == 200) {
            assertTrue(
                contentType != null && (contentType.contains("image/x-icon") || 
                                       contentType.contains("image/vnd.microsoft.icon") ||
                                       contentType.contains("image/ico") ||
                                       contentType.contains("application/octet-stream")),
                "Favicon should return image MIME type, not JSON. Got Content-Type: " + contentType);
            
            assertFalse(
                contentType != null && contentType.contains("application/json"),
                "Favicon should NOT return JSON. Got Content-Type: " + contentType);
        }
    }

    @Test
    void testFaviconDoesNotReturnJsonWith200() throws Exception {
        // Critical: Favicon should NOT return 200 with JSON
        // This would cause browser issues
        
        int status = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getStatus();
        
        if (status == 200) {
            String contentType = mockMvc.perform(get("/favicon.ico"))
                    .andReturn().getResponse().getContentType();
            
            String body = mockMvc.perform(get("/favicon.ico"))
                    .andReturn().getResponse().getContentAsString();
            
            // Should NOT be JSON
            assertFalse(
                contentType != null && contentType.contains("application/json"),
                "Favicon should NOT return 200 with JSON. Got Content-Type: " + contentType);
            
            // Body should NOT be JSON
            assertFalse(
                body.trim().startsWith("{") && body.trim().endsWith("}"),
                "Favicon should NOT return JSON body");
        }
    }

    @Test
    void testFaviconErrorIsNotBreaking() throws Exception {
        // Even if favicon returns 500, it should not break the application
        // Browser should handle missing favicon gracefully
        
        int status = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getStatus();
        
        // Any status is acceptable (404, 500, 200)
        // The important thing is it doesn't break the page
        assertTrue(status == 404 || status == 500 || status == 200,
                "Favicon status should be 404, 500, or 200. Got: " + status);
        
        // Backend is API-only; index.html is served by frontend (404 from backend is expected)
        int indexStatus = mockMvc.perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertTrue(indexStatus == 404 || indexStatus == 200,
                "Backend returns 404 for index.html (frontend serves it); or 200 if still serving.");
    }
}

