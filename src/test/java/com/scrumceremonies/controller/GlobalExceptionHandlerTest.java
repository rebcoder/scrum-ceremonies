package com.scrumceremonies.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import com.scrumceremonies.service.VisitorAnalyticsService;
import com.scrumceremonies.config.TestSecurityConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for GlobalExceptionHandler.
 * Tests exception handling and error response formatting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@Import({TestSecurityConfig.class, GlobalExceptionHandlerTest.TestControllerConfig.class})
@TestPropertySource(properties = {
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60"
})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VisitorAnalyticsService visitorAnalyticsService;

    @TestConfiguration
    static class TestControllerConfig {
        @Bean
        public TestController testController() {
            return new TestController();
        }
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @GetMapping("/data-access")
        public String throwDataAccessException() {
            throw new TestDataAccessException("Redis connection failed");
        }

        @GetMapping("/illegal-argument")
        public String throwIllegalArgumentException() {
            throw new IllegalArgumentException("Invalid input");
        }

        @GetMapping("/generic-exception")
        public String throwGenericException() {
            throw new RuntimeException("Unexpected error");
        }

        @GetMapping("/null-message")
        public String throwExceptionWithNullMessage() {
            throw new RuntimeException();
        }

        @GetMapping("/requires-room-id")
        public String requiresRoomId(@RequestParam(value = "roomId", required = true) String roomId) {
            return roomId;
        }
    }

    static class TestDataAccessException extends org.springframework.dao.DataRetrievalFailureException {
        public TestDataAccessException(String message) {
            super(message);
        }
    }

    @Test
    void testHandleDataAccessException() throws Exception {
        // GlobalExceptionHandler returns the unified ApiResponse shape
        // ({success,message,data}) for every handler, this one included.
        mockMvc.perform(get("/test/data-access"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Database connection error. Please try again later."));
    }

    @Test
    void testHandleIllegalArgumentException() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid input"));
    }

    @Test
    void testHandleGenericException() throws Exception {
        mockMvc.perform(get("/test/generic-exception"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void testHandleExceptionWithNullMessage() throws Exception {
        mockMvc.perform(get("/test/null-message"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void testHandleMissingServletRequestParameter() throws Exception {
        // GET without required roomId triggers MissingServletRequestParameterException -> 400
        mockMvc.perform(get("/test/requires-room-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Required parameter 'roomId' is not present."));
    }

    @Test
    void testWrongHttpMethodReturns405NotServerError() throws Exception {
        // A wrong HTTP method on a GET-only path must produce 405 with an Allow header,
        // not fall through to the generic 500 handler.
        mockMvc.perform(post("/test/data-access"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("not supported")));
    }
}

