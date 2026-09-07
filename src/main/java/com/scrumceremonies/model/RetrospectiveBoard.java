package com.scrumceremonies.model;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
@Setter
public class RetrospectiveBoard implements Serializable {
    private static final int MAX_USERS = 10;  // Maximum users per retro board
    private String roomId;
    private ConcurrentMap<String, String> userNames = new ConcurrentHashMap<>();
    private ConcurrentMap<String, List<RetroCard>> columns = new ConcurrentHashMap<>();
    private ConcurrentMap<String, Integer> userVoteCounts = new ConcurrentHashMap<>();
    private long lastActivityTime;
    private long timerStartedAt; // 0 = not started
    private long timerDurationMs = 600000; // 10 minutes default
    private String hostName; // Name of the first user who joined (the host)

    private static final int MAX_VOTES_PER_USER = 5;

    public RetrospectiveBoard() {
        columns.put("wentWell", new ArrayList<>());
        columns.put("toImprove", new ArrayList<>());
        columns.put("actionItems", new ArrayList<>());
    }

    public boolean canAddUser() {
        return this.userNames.size() < MAX_USERS;
    }

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

    private static final int MAX_CARD_TEXT_LENGTH = 180;

    public RetroCard addCard(String columnKey, String text, String authorId, String authorName) {
        // Truncate oversized card text to prevent memory exhaustion
        String safeText = (text != null && text.length() > MAX_CARD_TEXT_LENGTH)
                ? text.substring(0, MAX_CARD_TEXT_LENGTH)
                : text;
        RetroCard card = new RetroCard(UUID.randomUUID().toString().substring(0, 8), safeText, authorId, authorName, System.currentTimeMillis());
        columns.computeIfAbsent(columnKey, k -> new ArrayList<>()).add(card);
        updateLastActivityTime();
        return card;
    }

    /**
     * Delete a card. Only the card author or the board host can delete.
     * @return true if deleted, false if not authorized
     */
    public boolean deleteCard(String columnKey, String cardId, String requestingUserId) {
        List<RetroCard> list = columns.get(columnKey);
        if (list == null) return false;

        RetroCard target = list.stream().filter(c -> c.getId().equals(cardId)).findFirst().orElse(null);
        if (target == null) return false;

        // Only author or host can delete
        String authorId = target.getAuthorId();
        String hostUserId = getHostUserId();
        boolean isAuthor = authorId != null && authorId.equals(requestingUserId);
        boolean isHost = hostUserId != null && hostUserId.equals(requestingUserId);
        if (!isAuthor && !isHost) return false;

        list.removeIf(c -> c.getId().equals(cardId));
        updateLastActivityTime();
        return true;
    }

    /**
     * Check if user can still vote (max 5 votes per user).
     */
    public boolean canVote(String userId) {
        return userVoteCounts.getOrDefault(userId, 0) < MAX_VOTES_PER_USER;
    }

    public int getRemainingVotes(String userId) {
        return MAX_VOTES_PER_USER - userVoteCounts.getOrDefault(userId, 0);
    }

    public boolean upvoteCard(String columnKey, String cardId, String userId) {
        if (!canVote(userId)) return false;

        List<RetroCard> list = columns.get(columnKey);
        if (list == null) return false;

        var card = list.stream().filter(c -> c.getId().equals(cardId)).findFirst();
        if (card.isPresent()) {
            card.get().upvote();
            userVoteCounts.merge(userId, 1, Integer::sum);
            updateLastActivityTime();
            return true;
        }
        return false;
    }

    private String getHostUserId() {
        if (hostName == null) return null;
        return userNames.entrySet().stream()
                .filter(e -> hostName.equals(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    public boolean isHost(String userId) {
        String hostUserId = getHostUserId();
        return hostUserId != null && hostUserId.equals(userId);
    }

    public void updateLastActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }
}
