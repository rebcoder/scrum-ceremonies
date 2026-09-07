package com.scrumceremonies.model;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Vote implements Serializable {
    private String userId;
    private String value;
    @Getter
    private long lastActivity;

    @JsonCreator
    public Vote(@JsonProperty("userId") String userId,
                @JsonProperty("value") String value,
                @JsonProperty("lastActivity") long lastActivity) {
        this.userId = userId;
        this.value = value;
        this.lastActivity = lastActivity; // Initialize last activity time
    }

    @Override
    public String toString() {
        return this.value; // Return just the vote value for display
    }

}
