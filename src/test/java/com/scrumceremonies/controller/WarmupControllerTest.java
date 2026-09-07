package com.scrumceremonies.controller;

import com.scrumceremonies.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = WarmupController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class WarmupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("returns 200 OK with plain text body")
    void returnsOk() throws Exception {
        mockMvc.perform(get("/_warmup"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"))
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(header().exists("Cache-Control"));
    }
}
