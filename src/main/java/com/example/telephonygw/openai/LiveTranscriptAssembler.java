package com.example.telephonygw.openai;

import com.example.telephonygw.monitor.ConversationEventPublisher;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Application display segments, independent for each speaker and never API-final turns. */
final class LiveTranscriptAssembler {
    private final String sessionId;
    private final String generation = UUID.randomUUID().toString();
    private final ConversationEventPublisher publisher;
    private final Map<String, Segment> segments = new HashMap<>();
    private long nextSegment;

    LiveTranscriptAssembler(String sessionId, ConversationEventPublisher publisher) {
        this.sessionId = sessionId;
        this.publisher = publisher;
    }

    synchronized void append(String speaker, String eventId, String text, long startMs, long endMs) {
        if (text.isEmpty()) return;
        Segment segment = segments.get(speaker);
        if (segment == null || startMs - segment.endMs > 1000 || startMs - segment.startMs >= 4000
                || segment.text.length() + text.length() > 512) {
            segment = new Segment("live-" + generation + "-" + (++nextSegment), startMs);
            segments.put(speaker, segment);
        }
        segment.text.append(text);
        segment.endMs = Math.max(segment.endMs, endMs);
        publisher.publishTranscriptSnapshot(sessionId, speaker, segment.id, eventId,
                segment.text.toString(), segment.timestamp, segment.startMs, segment.endMs);
    }

    private static final class Segment {
        final String id;
        final long startMs;
        final Instant timestamp = Instant.now();
        final StringBuilder text = new StringBuilder();
        long endMs;
        Segment(String id, long startMs) { this.id = id; this.startMs = startMs; this.endMs = startMs; }
    }
}
