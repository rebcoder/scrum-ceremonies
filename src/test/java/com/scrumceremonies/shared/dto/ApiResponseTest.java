package com.scrumceremonies.shared.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ApiResponse} envelope.
 *
 * <p>Why this exists: {@code com.scrumceremonies.shared.dto} was the one package failing the
 * JaCoCo 60%-line-per-package gate in {@code mvn verify} (1 of 3 lines covered), which
 * made CI's backend job red. Only {@link ApiResponse#error(String)} had a production
 * caller — {@code GlobalExceptionHandler} — so the two {@code ok(...)} factories were
 * never executed by anything.
 *
 * <p>Note for whoever reads this next: those two factories still have <b>no production
 * caller</b>. This class pins their contract; it does not make them used. If they are
 * not part of the intended response contract, deleting them is the better fix and this
 * test should go with them.
 */
@DisplayName("ApiResponse — the success/error envelope")
class ApiResponseTest {

    @Nested
    @DisplayName("ok(data)")
    class OkWithData {

        @Test
        @DisplayName("marks success, carries the payload, and leaves the message null")
        void okWrapsPayload() {
            ApiResponse<String> response = ApiResponse.ok("room-created");

            assertTrue(response.isSuccess(), "ok() must set success=true");
            assertEquals("room-created", response.getData());
            assertNull(response.getMessage(), "ok(data) carries no message");
        }

        @Test
        @DisplayName("accepts a null payload without flipping success")
        void okAcceptsNullPayload() {
            ApiResponse<String> response = ApiResponse.ok(null);

            assertTrue(response.isSuccess());
            assertNull(response.getData());
        }
    }

    @Nested
    @DisplayName("ok(message, data)")
    class OkWithMessageAndData {

        @Test
        @DisplayName("carries both the message and the payload")
        void okWrapsMessageAndPayload() {
            ApiResponse<Integer> response = ApiResponse.ok("joined", 7);

            assertTrue(response.isSuccess());
            assertEquals("joined", response.getMessage());
            assertEquals(7, response.getData());
        }
    }

    @Nested
    @DisplayName("error(message)")
    class Error {

        @Test
        @DisplayName("marks failure, carries the message, and nulls the payload")
        void errorWrapsMessage() {
            ApiResponse<Void> response = ApiResponse.error("Room not found");

            assertFalse(response.isSuccess(), "error() must set success=false");
            assertEquals("Room not found", response.getMessage());
            assertNull(response.getData(), "error() never carries data");
        }
    }
}
