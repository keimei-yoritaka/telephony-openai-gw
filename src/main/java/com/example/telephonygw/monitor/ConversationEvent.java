package com.example.telephonygw.monitor;

import java.time.Instant;
import java.util.Objects;

public record ConversationEvent(
        long id,
        String sessionId,
        String speaker,
        String text,
        Instant timestamp,
        String itemId,
        String responseId,
        boolean finalTranscript
) {
    public ConversationEvent {
        if (id < 1L) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }
        sessionId = require("sessionId", sessionId);
        speaker = require("speaker", speaker);
        text = text == null ? "" : text;
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        itemId = itemId == null ? "" : itemId;
        responseId = responseId == null ? "" : responseId;
        if (!speaker.equals("caller") && !speaker.equals("assistant")) {
            throw new IllegalArgumentException("speaker must be caller or assistant: " + speaker);
        }
    }

    private static String require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
