package com.scrumceremonies.model;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MoodRoom model for Team Mood Check tool.
 * 
 * REUSES: Same pattern as Room and RetrospectiveBoard
 * - Redis storage with TTL
 * - User presence tracking (via RedisRoomPresenceService)
 * - Max 10 users per room
 * - Anonymous responses (no user names stored with responses)
 * 
 * NEW: Stores mood responses instead of votes or cards
 */
@Getter
@Setter
public class MoodRoom implements Serializable {
    private static final int MAX_USERS = 10;  // Maximum users per mood room (reused from Room)
    
    private String roomId;
    private String mode; // "QUICK_PULSE" or "SCRUM_PULSE"
    
    // User presence tracking (for join/leave, but NOT stored with responses)
    // This is separate from responses to maintain anonymity
    private ConcurrentMap<String, String> userNames = new ConcurrentHashMap<>();
    
    // Anonymous responses: userId -> response data
    // Response structure depends on mode:
    // - QUICK_PULSE: {"mood": "😄"}
    // - SCRUM_PULSE: {"mood": "😄", "confidence": 4, "workload": "Balanced", "blocked": "No", "comment": "..."}
    private ConcurrentMap<String, Map<String, Object>> responses = new ConcurrentHashMap<>();
    
    // Track which users have submitted (one response per user)
    // REUSES: Uses ConcurrentHashMap keys as Set for Jackson serialization compatibility
    // The value (Boolean) is always true, we only use the keys
    // NOTE: @JsonProperty ensures field is serialized directly (not via getter which is @JsonIgnore)
    @JsonProperty("submittedUsers")
    private ConcurrentMap<String, Boolean> submittedUsers = new ConcurrentHashMap<>();
    
    // Results revealed flag (controlled by backend)
    private boolean resultsRevealed = false;
    
    // Session ended flag (set by host to explicitly end session)
    // NEW: Prevents new users from joining after session is ended
    private boolean sessionEnded = false;
    
    private long lastActivityTime;
    private String hostName; // Name of the first user who joined (the host)

    public MoodRoom() {
        this.userNames = new ConcurrentHashMap<>();
        this.responses = new ConcurrentHashMap<>();
        this.submittedUsers = new ConcurrentHashMap<>();
    }

    public boolean canAddUser() {
        return this.userNames.size() < MAX_USERS;
    }

    /**
     * Add user to room (for presence tracking only).
     * Names are NOT stored with responses to maintain anonymity.
     */
    public void addUser(String userId, String name) {
        // Validate and sanitize name before processing
        String validatedName = validateAndSanitizeName(name);
        // Handle duplicate names by appending numbers
        String finalName = ensureUniqueName(validatedName);
        
        // If this is the first user joining, set them as the host
        if (userNames.isEmpty() && hostName == null) {
            hostName = finalName;
        }
        
        userNames.put(userId, finalName);
        updateLastActivityTime();
    }

    /**
     * Submit mood response for a user.
     * Enforces one response per user.
     * Responses are stored anonymously (no user name association).
     */
    public boolean submitResponse(String userId, Map<String, Object> responseData) {
        // Check if user already submitted
        if (submittedUsers.containsKey(userId)) {
            return false; // Already submitted
        }
        
        // Store response (anonymous - no user name)
        responses.put(userId, responseData);
        submittedUsers.put(userId, true); // Use map keys as set
        updateLastActivityTime();
        
        return true;
    }

    /**
     * Check if all users in room have submitted responses.
     */
    public boolean allUsersSubmitted() {
        // Only check users who are currently in the room
        return userNames.keySet().equals(submittedUsers.keySet()) && !userNames.isEmpty();
    }

    /**
     * Get count of submitted responses.
     * NOTE: @JsonIgnore prevents Jackson from serializing this computed property
     */
    @JsonIgnore
    public int getSubmittedCount() {
        return submittedUsers.size();
    }
    
    /**
     * Get the set of submitted user IDs.
     * REUSES: Returns keySet() of the map for Set operations
     * NOTE: @JsonIgnore prevents Jackson from using this getter for serialization
     */
    @JsonIgnore
    public Set<String> getSubmittedUsers() {
        return submittedUsers.keySet();
    }
    
    /**
     * Remove a user from submitted users tracking.
     * REUSES: Removes from the underlying map
     */
    public void removeSubmittedUser(String userId) {
        submittedUsers.remove(userId);
    }

    /**
     * Get total user count.
     * NOTE: @JsonIgnore prevents Jackson from serializing this computed property
     */
    @JsonIgnore
    public int getUserCount() {
        return userNames.size();
    }

    /**
     * Validates and sanitizes user name input.
     * REUSED: Same validation logic as Room and RetrospectiveBoard
     */
    private String validateAndSanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Anonymous";
        }
        
        String trimmed = name.trim();
        
        if (trimmed.length() > 20) {
            trimmed = trimmed.substring(0, 20);
        }
        
        trimmed = trimmed.replaceAll("[\\x00-\\x1F\\x7F]", "");
        trimmed = trimmed.replace("<", "&lt;")
                         .replace(">", "&gt;")
                         .replace("\"", "&quot;")
                         .replace("'", "&#x27;");
        
        if (trimmed.isEmpty()) {
            return "Anonymous";
        }
        
        return trimmed;
    }

    private String ensureUniqueName(String name) {
        if (!userNames.containsValue(name)) {
            return name;
        }
        int counter = 1;
        String candidateName;
        do {
            candidateName = name + counter;
            counter++;
        } while (userNames.containsValue(candidateName) && counter < 1000);
        return candidateName;
    }

    public void updateLastActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }
}

