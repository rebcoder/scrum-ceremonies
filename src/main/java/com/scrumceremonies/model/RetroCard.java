package com.scrumceremonies.model;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RetroCard implements Serializable {
    private String id;
    private String text;
    private String authorId;
    private String authorName;
    private int votes;
    private long createdAt;

    public RetroCard() {
    }

    public RetroCard(String id, String text, String authorId, String authorName, long createdAt) {
        this.id = id;
        this.text = text;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = createdAt;
        this.votes = 0;
    }

    public void upvote() {
        this.votes += 1;
    }
}

