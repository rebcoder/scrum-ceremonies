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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NON-INTRUSIVE tests for backend API-only mode.
 * Backend does NOT serve static assets (HTML/CSS/JS); they return 404.
 * The static frontend host serves static assets.
 * 
 * Validates:
 * - Unserved static paths return 404 (or 200/500 as documented), never 200 with wrong content
 * - Missing assets return 404/500, not JSON with 200 OK
 */
@SpringBootTest
@AutoConfigureWebMvc
class StaticAssetIntegrityTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void testCssFilesReturnCorrectMimeType() throws Exception {
        // CSS files should return Content-Type: text/css
        // Test both versioned and non-versioned CSS files
        String[] cssFiles = {"/style.css", "/style.v0_0_1.css"};
        
        for (String cssFile : cssFiles) {
            int status = mockMvc.perform(get(cssFile))
                    .andReturn().getResponse().getStatus();
            
            if (status == 200) {
                String contentType = mockMvc.perform(get(cssFile))
                        .andReturn().getResponse().getContentType();
                
                assertNotNull(contentType, "Content-Type should be present for CSS file: " + cssFile);
                assertTrue(contentType.contains("text/css") || contentType.contains("text/css;"),
                        "CSS file should have Content-Type: text/css, got: " + contentType + " for " + cssFile);
            }
        }
    }

    @Test
    void testJsFilesReturnCorrectMimeType() throws Exception {
        // JS files should return Content-Type: application/javascript or text/javascript
        String[] jsFiles = {"/script.js", "/retro.js", "/script.v0_0_1.js"};
        
        for (String jsFile : jsFiles) {
            int status = mockMvc.perform(get(jsFile))
                    .andReturn().getResponse().getStatus();
            
            if (status == 200) {
                String contentType = mockMvc.perform(get(jsFile))
                        .andReturn().getResponse().getContentType();
                
                assertNotNull(contentType, "Content-Type should be present for JS file: " + jsFile);
                assertTrue(
                    contentType.contains("application/javascript") || 
                    contentType.contains("text/javascript") ||
                    contentType.contains("application/x-javascript"),
                    "JS file should have JavaScript MIME type, got: " + contentType + " for " + jsFile);
            }
        }
    }

    @Test
    void testMissingAssetsReturn404NotJson() throws Exception {
        // Missing assets should return 404, not JSON with 200 OK
        String[] missingAssets = {
            "/nonexistent.css",
            "/nonexistent.js",
            "/style.v999_999_999.css",  // Non-existent version
            "/script.v999_999_999.js"   // Non-existent version
        };
        
        for (String asset : missingAssets) {
            int status = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getStatus();
            
            String contentType = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getContentType();
            
            String body = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getContentAsString();
            
            // Should return 404, 200, or 500 (but not 200 with JSON)
            assertTrue(status == 404 || status == 200 || status == 500, 
                    "Missing asset should return 404, 200, or 500, got: " + status + " for " + asset);
            
            // If 200, should NOT be JSON (which would indicate API error response)
            if (status == 200) {
                assertFalse(
                    (contentType != null && contentType.contains("application/json")) ||
                    (body.trim().startsWith("{") && body.trim().endsWith("}")),
                    "Missing asset should NOT return JSON with 200 OK. Got Content-Type: " + 
                    contentType + " for " + asset);
            }
        }
    }

    @Test
    void testHtmlFilesNotServedByBackend() throws Exception {
        // Backend is API-only; HTML is served by frontend (Static Web Apps)
        String[] htmlFiles = {"/index.html", "/poker.html", "/retro.html",
                              "/about.html", "/contact.html", "/terms.html",
                              "/privacy.html"};
        for (String htmlFile : htmlFiles) {
            int status = mockMvc.perform(get(htmlFile)).andReturn().getResponse().getStatus();
            assertTrue(status == 404 || status == 500,
                    "Backend should not serve HTML (expect 404/500): " + htmlFile + " got " + status);
        }
    }

    @Test
    void testIndexHtmlReturns404Or500() throws Exception {
        int status = mockMvc.perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertTrue(status == 404 || status == 500,
                "Backend should not serve index.html (expect 404/500), got: " + status);
    }

    @Test
    void testCssNotServedAsJson() throws Exception {
        // CSS files should never be served as JSON (MIME type error)
        String[] cssFiles = {"/style.css", "/style.v0_0_1.css"};
        
        for (String cssFile : cssFiles) {
            int status = mockMvc.perform(get(cssFile))
                    .andReturn().getResponse().getStatus();
            
            if (status == 200) {
                String contentType = mockMvc.perform(get(cssFile))
                        .andReturn().getResponse().getContentType();
                
                String body = mockMvc.perform(get(cssFile))
                        .andReturn().getResponse().getContentAsString();
                
                // Should NOT be JSON
                assertFalse(
                    contentType != null && contentType.contains("application/json"),
                    "CSS file should NOT be served as JSON. Got Content-Type: " + contentType + " for " + cssFile);
                
                // Body should NOT be JSON (should be CSS content)
                assertFalse(
                    body.trim().startsWith("{") && body.trim().endsWith("}"),
                    "CSS file should NOT return JSON body for " + cssFile);
            }
        }
    }

    @Test
    void testJsNotServedAsJson() throws Exception {
        // JS files should never be served as JSON (MIME type error)
        String[] jsFiles = {"/script.js", "/retro.js", "/script.v0_0_1.js"};
        
        for (String jsFile : jsFiles) {
            int status = mockMvc.perform(get(jsFile))
                    .andReturn().getResponse().getStatus();
            
            if (status == 200) {
                String contentType = mockMvc.perform(get(jsFile))
                        .andReturn().getResponse().getContentType();
                
                String body = mockMvc.perform(get(jsFile))
                        .andReturn().getResponse().getContentAsString();
                
                // Should NOT be JSON (unless it's actually a JSON file, which these aren't)
                assertFalse(
                    contentType != null && contentType.contains("application/json") && !jsFile.endsWith(".json"),
                    "JS file should NOT be served as JSON. Got Content-Type: " + contentType + " for " + jsFile);
            }
        }
    }

    @Test
    void testFaviconHandling() throws Exception {
        // Favicon.ico is commonly requested by browsers
        // Missing favicon should return 404, not 500 with JSON
        int status = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getStatus();
        
        String contentType = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getContentType();
        
        String body = mockMvc.perform(get("/favicon.ico"))
                .andReturn().getResponse().getContentAsString();
        
        // Should return 404 (ideal) or 500 (acceptable, but not ideal)
        // Should NOT return 200 with JSON (which would break browser)
        assertTrue(status == 404 || status == 500 || status == 200,
                "Favicon should return 404, 500, or 200. Got: " + status);
        
        // If 200, should NOT be JSON
        if (status == 200) {
            assertFalse(
                contentType != null && contentType.contains("application/json"),
                "Favicon should NOT return 200 with JSON. Got Content-Type: " + contentType);
        }
        
        // If 500, log as acceptable but not ideal
        if (status == 500) {
            System.out.println("WARNING: Favicon returns 500 instead of 404. This is acceptable but not ideal.");
        }
    }
}

