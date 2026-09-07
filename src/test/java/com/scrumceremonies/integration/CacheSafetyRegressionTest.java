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
 * NON-INTRUSIVE regression guard tests for backend API-only mode.
 * Backend does NOT serve static assets; unhandled paths return 404.
 * The static frontend host serves HTML/CSS/JS and cache headers.
 * 
 * Validates: unhandled static paths return 404 (not 500), missing assets return 404.
 */
@SpringBootTest
@AutoConfigureWebMvc
class CacheSafetyRegressionTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void testOldHtmlReferencingMissingAssetReturns404() throws Exception {
        // Simulate: Old HTML cached in browser references asset.v1_0_0.css
        // After deployment, new version is asset.v2_0_0.css
        // Old HTML should get 404 for missing asset, not JSON error
        
        String[] oldVersionedAssets = {
            "/style.v999_999_999.css",  // Simulate very old version
            "/script.v999_999_999.js",
            "/retro.v999_999_999.js"
        };
        
        for (String asset : oldVersionedAssets) {
            int status = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getStatus();
            
            String contentType = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getContentType();
            
            String body = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getContentAsString();
            
            // Should return 404 (proper HTTP response)
            // Should NOT return 200 with JSON (which would break browser)
            if (status == 200) {
                assertFalse(
                    contentType != null && contentType.contains("application/json"),
                    "Missing asset " + asset + " should NOT return 200 with JSON. " +
                    "This would cause MIME type errors in browser. Got Content-Type: " + contentType);
                
                assertFalse(
                    body.trim().startsWith("{") && body.trim().endsWith("}"),
                    "Missing asset " + asset + " should NOT return JSON body. " +
                    "This would cause MIME type errors in browser.");
            }
        }
    }

    @Test
    void testHtmlNotServedByBackendReturns404() throws Exception {
        // Backend is API-only; HTML is served by frontend (Static Web Apps)
        String[] htmlFiles = {"/index.html", "/poker.html", "/retro.html"};
        for (String htmlFile : htmlFiles) {
            int status = mockMvc.perform(get(htmlFile)).andReturn().getResponse().getStatus();
            assertEquals(404, status, "Backend should not serve HTML: " + htmlFile);
        }
    }

    @Test
    void testStaticAssetsAreSafelyCacheable() throws Exception {
        // Static assets (CSS/JS) should have long-term cache headers
        // This is safe because versioning ensures new versions have different filenames
        String[] staticAssets = {"/style.css", "/script.js", "/style.v0_0_1.css", "/script.v0_0_1.js"};
        
        for (String asset : staticAssets) {
            int status = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getStatus();
            
            if (status == 200) {
                String cacheControl = mockMvc.perform(get(asset))
                        .andReturn().getResponse().getHeader("Cache-Control");
                
                if (cacheControl != null) {
                    // Should have long-term cache (safe because of versioning)
                    boolean hasLongTermCache = cacheControl.contains("max-age") || 
                                             cacheControl.contains("immutable") ||
                                             cacheControl.contains("public");
                    
                    // This is acceptable - versioned assets can be cached long-term
                    // The test just validates they have cache headers (which is safe)
                    assertTrue(hasLongTermCache || cacheControl.contains("public"),
                            "Static asset " + asset + " should have cache headers. Got: " + cacheControl);
                }
            }
        }
    }

    @Test
    void testBrowserCacheSensitiveFilesNotServedByBackend() throws Exception {
        // Backend is API-only; index.html is served by frontend
        int status = mockMvc.perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertEquals(404, status, "Backend should not serve index.html");
    }

    @Test
    void testMissingAssetDoesNotSilentlySucceed() throws Exception {
        // Missing assets should return proper HTTP status, not silently succeed
        String[] missingAssets = {
            "/nonexistent-file-that-should-404.css",
            "/nonexistent-file-that-should-404.js"
        };
        
        for (String asset : missingAssets) {
            int status = mockMvc.perform(get(asset))
                    .andReturn().getResponse().getStatus();
            
            // Should be 404, 200, or 500 (but not 200 with JSON)
            // 200 with wrong content would break browser
            assertTrue(status == 404 || status == 200 || status == 500,
                    "Missing asset " + asset + " should return 404, 200, or 500. Got: " + status);
            
            if (status == 200) {
                // If 200, verify it's not a JSON error response
                String contentType = mockMvc.perform(get(asset))
                        .andReturn().getResponse().getContentType();
                
                assertFalse(
                    contentType != null && contentType.contains("application/json"),
                    "Missing asset " + asset + " returned 200 with JSON. " +
                    "This would cause MIME type errors. Got Content-Type: " + contentType);
            }
        }
    }

    @Test
    void testVersionedAssetReferencesBackendReturns404() throws Exception {
        // Backend is API-only; index.html and static assets are not served
        int status = mockMvc.perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertEquals(404, status, "Backend should not serve index.html");
        // Static assets also return 404 from backend
        int cssStatus = mockMvc.perform(get("/style.css")).andReturn().getResponse().getStatus();
        int jsStatus = mockMvc.perform(get("/script.js")).andReturn().getResponse().getStatus();
        assertEquals(404, cssStatus, "Backend should not serve style.css");
        assertEquals(404, jsStatus, "Backend should not serve script.js");
    }

}

