package com.scrumceremonies.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Public endpoint security headers")
    class PublicEndpointHeaders {

        @Test
        @DisplayName("should include X-Content-Type-Options: nosniff")
        void xContentTypeOptions() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test
        @DisplayName("should include X-Frame-Options: DENY")
        void xFrameOptions() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test
        @DisplayName("should include Content-Security-Policy")
        void contentSecurityPolicy() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().exists("Content-Security-Policy"));
        }

        @Test
        @DisplayName("CSP should contain frame-ancestors 'self'")
        void cspFrameAncestors() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("frame-ancestors 'self'")));
        }

        @Test
        @DisplayName("CSP should contain default-src 'self'")
        void cspDefaultSrc() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("Content-Security-Policy",
                            org.hamcrest.Matchers.containsString("default-src 'self'")));
        }

        @Test
        @DisplayName("should include Referrer-Policy")
        void referrerPolicy() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
        }

        @Test
        @DisplayName("should include Permissions-Policy")
        void permissionsPolicy() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().string("Permissions-Policy",
                            "camera=(), microphone=(), geolocation=()"));
        }

        @Test
        @DisplayName("should include Cache-Control headers")
        void cacheControl() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(header().exists("Cache-Control"));
        }
    }

    @Nested
    @DisplayName("CORS configuration")
    class CorsHeaders {

        @Test
        @DisplayName("preflight request from allowed origin should succeed")
        void preflightFromAllowedOrigin() throws Exception {
            mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/create-room")
                            .header("Origin", "http://localhost:8080")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type")
            ).andExpect(header().exists("Access-Control-Allow-Origin"));
        }

        @Test
        @DisplayName("CORS should allow credentials")
        void corsAllowsCredentials() throws Exception {
            mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/create-room")
                            .header("Origin", "http://localhost:8080")
                            .header("Access-Control-Request-Method", "POST")
            ).andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }
}
