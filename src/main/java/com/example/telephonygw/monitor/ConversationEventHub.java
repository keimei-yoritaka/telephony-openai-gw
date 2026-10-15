package com.example.telephonygw.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ConversationEventHub implements ConversationEventPublisher {
    private static final System.Logger LOG = System.getLogger(ConversationEventHub.class.getName());
    private static final int DEFAULT_MAX_EVENTS = 500;

    private final int maxEvents;
    private final AtomicLong nextId = new AtomicLong(1L);
    private final List<ConversationEvent> history = new ArrayList<>();
    private final Map<String, Instant> sessionUpdatedAt = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ConversationEvent>> subscribers = new CopyOnWriteArrayList<>();

    public ConversationEventHub() {
        this(DEFAULT_MAX_EVENTS);
    }

    public ConversationEventHub(int maxEvents) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive: " + maxEvents);
        }
        this.maxEvents = maxEvents;
    }

    @Override
    public void publishTranscript(String sessionId, String speaker, String itemId, String responseId, String text) {
        String transcript = text == null ? "" : text.trim();
        if (transcript.isBlank() || transcript.equals("unknown")) {
            return;
        }
        publish(new ConversationEvent(
                nextId.getAndIncrement(),
                sessionId,
                speaker,
                transcript,
                Instant.now(),
                itemId,
                responseId,
                true));
    }

    @Override
    public void publishTranscriptSnapshot(String sessionId, String speaker, String segmentId, String sourceEventId,
            String text, Instant timestamp, long startMs, long endMs) {
        if (text.isBlank()) return;
        publish(new ConversationEvent(nextId.getAndIncrement(), sessionId, speaker, text, timestamp,
                "", "", false, segmentId, sourceEventId, startMs, endMs));
    }

    @Override
    public void publishTranslationSnapshot(String sessionId, String kind, String language, String segmentId,
            String sourceEventId, String text, Instant timestamp, long elapsedMs) {
        if (text.isBlank()) return;
        publish(new ConversationEvent(nextId.getAndIncrement(), sessionId,
                "source".equals(kind) ? "caller" : "assistant", text, timestamp, "", "", false,
                segmentId, sourceEventId, elapsedMs, elapsedMs, kind, language));
    }

    @Override
    public List<ConversationEvent> recentTranscripts(String sessionId) { return eventsForSession(sessionId); }

    public void publish(ConversationEvent event) {
        synchronized (history) {
            int existing = -1;
            if (!event.segmentId().isEmpty()) {
                for (int i = 0; i < history.size(); i++) {
                    ConversationEvent previous = history.get(i);
                    if (previous.sessionId().equals(event.sessionId()) && previous.segmentId().equals(event.segmentId())) {
                        existing = i;
                        break;
                    }
                }
            }
            if (existing >= 0) {
                if (history.get(existing).id() >= event.id()) return;
                history.set(existing, event);
            } else history.add(event);
            while (history.size() > maxEvents) {
                history.remove(0);
            }
        }
        sessionUpdatedAt.put(event.sessionId(), Instant.now());
        notifySubscribers(event);
    }

    public List<ConversationEvent> latestEvents() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public List<ConversationEvent> eventsForSession(String sessionId) {
        List<ConversationEvent> events = new ArrayList<>();
        synchronized (history) {
            for (ConversationEvent event : history) {
                if (event.sessionId().equals(sessionId)) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    public String latestSessionId() {
        String latestSessionId = "";
        Instant latestTimestamp = Instant.EPOCH;
        for (Map.Entry<String, Instant> entry : sessionUpdatedAt.entrySet()) {
            if (entry.getValue().isAfter(latestTimestamp)) {
                latestTimestamp = entry.getValue();
                latestSessionId = entry.getKey();
            }
        }
        return latestSessionId;
    }

    public List<String> sessionIds() {
        List<Map.Entry<String, Instant>> entries = new ArrayList<>(sessionUpdatedAt.entrySet());
        entries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        List<String> sessionIds = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : entries) {
            sessionIds.add(entry.getKey());
        }
        return sessionIds;
    }

    public int maxEvents() {
        return maxEvents;
    }

    public void addSubscriber(Consumer<ConversationEvent> subscriber) {
        subscribers.add(subscriber);
    }

    public void removeSubscriber(Consumer<ConversationEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    private void notifySubscribers(ConversationEvent event) {
        for (Consumer<ConversationEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Conversation event subscriber failed: sessionId={0}, eventId={1}, error={2}",
                        event.sessionId(), event.id(), e.getMessage());
            }
        }
    }
}
