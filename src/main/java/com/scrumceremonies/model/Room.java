package com.scrumceremonies.model;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
@Setter
public class Room implements Serializable {
    private String roomId;
    private ConcurrentMap<String, Vote> userVotes; // Changed from Map<String, String>
    private ConcurrentMap<String, String> userNames;
    private boolean revealed;
    private long lastActivityTime;
    private String hostName; // Name of the first user who joined (the host)
    private static final int MAX_USERS = 10;  // Maximum users per room

    @JsonCreator
    public Room(@JsonProperty("roomId") String roomId,
                @JsonProperty("userVotes") ConcurrentMap<String, Vote> userVotes,
                @JsonProperty("userNames") ConcurrentMap<String, String> userNames,
                @JsonProperty("revealed") boolean revealed,
                @JsonProperty("lastActivityTime") long lastActivityTime,
                @JsonProperty("hostName") String hostName) {
        this.roomId = roomId;
        this.userVotes = userVotes != null ? userVotes : new ConcurrentHashMap<>();
        this.userNames = userNames != null ? userNames : new ConcurrentHashMap<>();
        this.revealed = revealed;
        this.lastActivityTime = lastActivityTime;
        this.hostName = hostName;
    }

    public Room() {
        this.userVotes = new ConcurrentHashMap<>();
        this.userNames = new ConcurrentHashMap<>();
    }

    public boolean canAddUser() {
        return this.userNames.size() < MAX_USERS;
    }

    // Update methods to handle Vote objects
    public void addOrUpdateVote(String userId, String voteValue) {
        this.userVotes.put(userId, new Vote(userId, voteValue, System.currentTimeMillis()));
        updateLastActivityTime();
    }
    @JsonIgnore
    public Map<String, String> getVotesAsStrings() {
        Map<String, String> result = new ConcurrentHashMap<>();
        userVotes.forEach((userId, vote) -> result.put(userId, vote.getValue()));
        return result;
    }

    public void addUserName(String userId, String name) {
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
     * Validates and sanitizes user name input.
     * - Ensures name is not null or empty (defaults to "Anonymous")
     * - Limits length to 20 characters
     * - Removes control characters (newlines, tabs, null bytes, etc.)
     * - Escapes HTML characters to prevent XSS
     * 
     * @param name Raw name input
     * @return Validated and sanitized name
     */
    private String validateAndSanitizeName(String name) {
        // Handle null or empty input
        if (name == null || name.trim().isEmpty()) {
            return "Anonymous";
        }
        
        // Trim whitespace
        String trimmed = name.trim();
        
        // Limit length to 20 characters (matching frontend validation)
        if (trimmed.length() > 20) {
            trimmed = trimmed.substring(0, 20);
        }
        
        // Remove control characters (0x00-0x1F, 0x7F) that could cause issues
        // This includes: null bytes, newlines, tabs, carriage returns, etc.
        trimmed = trimmed.replaceAll("[\\x00-\\x1F\\x7F]", "");
        
        // Basic XSS prevention - escape HTML characters
        // Note: If names are displayed in HTML, they should be escaped in the template
        // This is a secondary defense
        trimmed = trimmed.replace("<", "&lt;")
                         .replace(">", "&gt;")
                         .replace("\"", "&quot;")
                         .replace("'", "&#x27;");
        
        // If empty after sanitization, use default
        if (trimmed.isEmpty()) {
            return "Anonymous";
        }
        
        return trimmed;
    }

    private String ensureUniqueName(String name) {
        if (!userNames.containsValue(name)) {
            return name;
        }
        // Name exists, append number
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
