package com.scrumceremonies.util;

import java.util.UUID;

/**
 * Utility class for test helpers.
 */
public class TestUtils {
    
    /**
     * Generates a unique user ID for testing.
     */
    public static String generateUserId() {
        return "test-user-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Generates a valid room code format (8 alphanumeric characters).
     */
    public static String generateRoomCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    
    /**
     * Generates a test IP address.
     */
    public static String generateTestIp(int suffix) {
        return "192.168.1." + (suffix % 255);
    }
}

