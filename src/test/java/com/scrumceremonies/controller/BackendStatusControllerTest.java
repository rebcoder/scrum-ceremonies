package com.scrumceremonies.controller;

import com.scrumceremonies.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = BackendStatusController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class BackendStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthContributorRegistry healthContributorRegistry;

    @Test
    @DisplayName("returns running status with Redis UP")
    void returnsRunningStatusWithRedisUp() throws Exception {
        HealthIndicator redisHealth = () -> Health.up().build();
        when(healthContributorRegistry.getContributor("redis")).thenReturn(redisHealth);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.message").value("Backend is running"))
                .andExpect(jsonPath("$.redis").value("up"));
    }

    @Test
    @DisplayName("returns running status with Redis DOWN")
    void returnsRunningStatusWithRedisDown() throws Exception {
        HealthIndicator redisHealth = () -> Health.down().build();
        when(healthContributorRegistry.getContributor("redis")).thenReturn(redisHealth);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.redis").value("down"));
    }

    @Test
    @DisplayName("returns unknown when Redis contributor is missing")
    void returnsUnknownWhenRedisContributorMissing() throws Exception {
        when(healthContributorRegistry.getContributor("redis")).thenReturn(null);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.redis").value("unknown"));
    }

    @Test
    @DisplayName("returns unknown when Redis health check throws exception")
    void returnsUnknownWhenRedisHealthThrows() throws Exception {
        when(healthContributorRegistry.getContributor("redis")).thenThrow(new RuntimeException("connection failed"));

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.redis").value("unknown"));
    }
}
