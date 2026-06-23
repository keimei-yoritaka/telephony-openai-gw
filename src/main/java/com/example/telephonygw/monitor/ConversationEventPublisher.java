package com.example.telephonygw.monitor;

public interface ConversationEventPublisher {
    ConversationEventPublisher NOOP = (sessionId, speaker, itemId, responseId, text) -> {
    };

    void publishTranscript(String sessionId, String speaker, String itemId, String responseId, String text);
}
