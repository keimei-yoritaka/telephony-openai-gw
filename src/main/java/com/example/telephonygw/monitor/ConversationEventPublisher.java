package com.example.telephonygw.monitor;

public interface ConversationEventPublisher {
    ConversationEventPublisher NOOP = (sessionId, speaker, itemId, responseId, text) -> {
    };

    void publishTranscript(String sessionId, String speaker, String itemId, String responseId, String text);
    default void publishTranscriptSnapshot(String sessionId, String speaker, String segmentId, String sourceEventId,
            String text, java.time.Instant timestamp, long startMs, long endMs) {
    }

    default void publishTranslationSnapshot(String sessionId, String kind, String language, String segmentId,
            String sourceEventId, String text, java.time.Instant timestamp, long elapsedMs) {
    }

    default java.util.List<ConversationEvent> recentTranscripts(String sessionId) { return java.util.List.of(); }
}
