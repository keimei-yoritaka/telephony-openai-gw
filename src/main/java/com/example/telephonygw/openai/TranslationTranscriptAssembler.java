package com.example.telephonygw.openai;

import com.example.telephonygw.monitor.ConversationEventPublisher;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** Independent source/target display segments; elapsed_ms is never an event identity. */
final class TranslationTranscriptAssembler {
    private final String sessionId;
    private final String language;
    private final ConversationEventPublisher publisher;
    private final String generation = UUID.randomUUID().toString();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Map<String, Segment> segments = new HashMap<>();
    private long next;

    TranslationTranscriptAssembler(String sessionId, String language, ConversationEventPublisher publisher) {
        this.sessionId = sessionId;
        this.language = language;
        this.publisher = publisher;
    }

    void append(String kind, String eventId, String text, long elapsedMs) {
        if (!eventId.isEmpty() && !seen.add(eventId)) return;
        if (seen.size() > 4096) seen.remove(seen.iterator().next());
        if (text.isEmpty()) return;
        long now = System.nanoTime();
        Segment segment = segments.get(kind);
        if (segment == null || now - segment.createdNanos >= 4_000_000_000L
                || segment.text.length() + text.length() > 512) {
            segment = new Segment("translation-" + generation + "-" + (++next), now);
            segments.put(kind, segment);
        }
        segment.text.append(text);
        publisher.publishTranslationSnapshot(sessionId, kind, language, segment.id, eventId,
                segment.text.toString(), segment.timestamp, elapsedMs);
    }

    private static final class Segment {
        final String id;
        final long createdNanos;
        final Instant timestamp = Instant.now();
        final StringBuilder text = new StringBuilder();
        Segment(String id, long now) { this.id = id; this.createdNanos = now; }
    }
}
