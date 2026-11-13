package com.example.telephonygw.soniox;

import com.example.telephonygw.monitor.ConversationEventPublisher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Final tokens append once; the entire provisional tail is replaced on every response. */
final class SonioxTranscriptAssembler {
    private final String sessionId;
    private final ConversationEventPublisher publisher;
    private final String generation = UUID.randomUUID().toString();
    private final Map<String, Segment> segments = new LinkedHashMap<>();
    private long nextId;

    SonioxTranscriptAssembler(String sessionId, ConversationEventPublisher publisher) {
        this.sessionId = sessionId; this.publisher = publisher;
    }

    void accept(JsonArray tokens, Consumer<String> translation, Runnable endpoint) {
        for (Segment segment : segments.values()) segment.provisional.setLength(0);
        for (var value : tokens) {
            JsonObject token = value.getAsJsonObject();
            String text = SonioxSocket.string(token, "text");
            boolean confirmed = SonioxSocket.flag(token, "is_final");
            if (text.equals("<end>") || text.equals("<fin>")) {
                if (confirmed) endpoint.run();
                continue;
            }
            String status = SonioxSocket.string(token, "translation_status");
            String kind = status.equals("translation") ? "translation" : "source";
            if (text.isEmpty()) continue;
            Segment segment = segments.computeIfAbsent(kind, ignored -> new Segment(kind));
            // Rollover only at a confirmed boundary; revisions never become immutable history.
            if (confirmed && (segment.committed.length() >= 512
                    || (!segment.committed.isEmpty() && System.nanoTime() - segment.created >= 4_000_000_000L))) {
                publish(kind, segment);
                segment = new Segment(kind);
                segments.put(kind, segment);
            }
            if (confirmed) {
                segment.committed.append(text);
                if (kind.equals("translation") && SonioxSocket.string(token, "language").equals("en")) translation.accept(text);
            } else segment.provisional.append(text);
            if (segment.committed.length() + segment.provisional.length() > 32768)
                throw new IllegalArgumentException("Transcript segment too large");
        }
        segments.forEach(this::publish);
    }

    private void publish(String kind, Segment segment) {
        String text = segment.committed.toString() + segment.provisional;
        if (text.equals(segment.published)) return;
        segment.published = text;
        publisher.publishTranslationSnapshot(sessionId, kind, kind.equals("translation") ? "en" : "",
                segment.id, "", text, segment.timestamp, -1);
    }

    private final class Segment {
        final String id;
        final Instant timestamp = Instant.now();
        final long created = System.nanoTime();
        final StringBuilder committed = new StringBuilder();
        final StringBuilder provisional = new StringBuilder();
        String published = "";
        Segment(String kind) { id = "soniox-" + generation + "-" + kind + "-" + (++nextId); }
    }
}
