package com.example.telephonygw.soniox;

import com.example.telephonygw.config.GatewayConfig.SonioxConfig;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.openai.StreamingAudioDecoder;
import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** One active stream per call; generated audio is never interleaved across streams. */
final class SonioxTtsClient {
    private final String sessionId;
    private final SonioxConfig config;
    private final AudioBridge bridge;
    private final BooleanSupplier playable;
    private final SonioxSocket socket;
    private final StreamingAudioDecoder decoder;
    private final BiConsumer<String, JsonObject> failure;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final StringBuilder pendingText = new StringBuilder();
    private String streamId;
    private int queuedCharacters;
    private long pendingSince;
    private long streamSince;
    private boolean audioEnded;
    private boolean firstAudio;
    private boolean stopping;
    private boolean aborted;
    private CompletableFuture<Void> terminated = CompletableFuture.completedFuture(null);

    SonioxTtsClient(String id, SonioxConfig config, AudioBridge bridge, BooleanSupplier playable,
            Object lock, BiConsumer<String, JsonObject> failure,
            Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector) {
        this.sessionId = id; this.config = config; this.bridge = bridge; this.playable = playable; this.failure = failure;
        decoder = new StreamingAudioDecoder(bridge, id, true);
        socket = new SonioxSocket(lock, this::receive, code -> failure.accept("tts_" + code, new JsonObject()), connector);
    }

    void open() { socket.open(); }

    void append(String text) {
        if (stopping || !playable.getAsBoolean()) return;
        if (pendingText.isEmpty()) pendingSince = System.nanoTime();
        pendingText.append(text);
        while (!stopping && pendingText.length() >= 512) {
            int length = Character.isHighSurrogate(pendingText.charAt(511)) ? 511 : 512;
            String chunk = pendingText.substring(0, length);
            pendingText.delete(0, length);
            enqueue(chunk);
        }
        if (text.matches("(?s).*[.!?。！？]\\s*$")) flushText();
    }

    void flushText() {
        if (stopping || pendingText.isEmpty()) return;
        String text = pendingText.toString();
        pendingText.setLength(0);
        enqueue(text);
    }

    private void enqueue(String text) {
        if (stopping || text.isBlank()) return;
        if (queue.size() >= 10 || queuedCharacters + text.length() > 4096) {
            failure.accept("tts_text_backlog", new JsonObject()); return;
        }
        queue.add(text); queuedCharacters += text.length();
        startNext();
    }

    private void startNext() {
        if (stopping || !playable.getAsBoolean() || streamId != null || queue.isEmpty()) return;
        String text = queue.remove(); queuedCharacters -= text.length();
        streamId = UUID.randomUUID().toString();
        streamSince = System.nanoTime(); audioEnded = false;
        terminated = new CompletableFuture<>();
        decoder.clear();
        bridge.markOutboundActive(sessionId);
        socket.json(Map.of("stream_id", streamId, "model", config.ttsModel(), "language", config.translationTargetLanguage(),
                "voice", config.ttsVoice(), "speed", config.ttsSpeed(), "audio_format", "pcm_s16le", "sample_rate", 24000));
        if (!stopping) socket.json(Map.of("stream_id", streamId, "text", text, "text_end", true));
    }

    private void receive(JsonObject event) {
        if (aborted) return;
        if (event.has("error_code")) { failure.accept("tts_api_error", event); return; }
        String id = SonioxSocket.string(event, "stream_id");
        if (streamId == null || !streamId.equals(id)) return;
        if (!stopping && playable.getAsBoolean() && event.has("audio")) {
            if (audioEnded) { failure.accept("tts_audio_after_end", event); return; }
            if (!firstAudio && !SonioxSocket.string(event, "audio").isEmpty()) {
                firstAudio = true;
                com.example.telephonygw.logging.GatewayEventLogger.info(
                        System.getLogger(SonioxTtsClient.class.getName()), "soniox_first_audio", "sessionId", sessionId,
                        "fromStreamStartMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - streamSince));
            }
            bridge.markContinuousAudioReceived(sessionId, System.nanoTime());
            try { decoder.append(SonioxSocket.string(event, "audio")); }
            catch (IllegalStateException overflow) { failure.accept("tts_playout_rejected", new JsonObject()); return; }
            if (bridge.outboundDepth(sessionId) > 500) { failure.accept("tts_playout_backlog", new JsonObject()); return; }
        }
        if (SonioxSocket.flag(event, "audio_end")) {
            audioEnded = true;
            if (!stopping && playable.getAsBoolean()) {
                try { decoder.flush(); decoder.clear(); }
                catch (IllegalStateException overflow) { failure.accept("tts_playout_rejected", new JsonObject()); return; }
                bridge.markOutboundComplete(sessionId);
            }
        }
        if (SonioxSocket.flag(event, "terminated")) {
            if (!stopping && !audioEnded) { failure.accept("tts_incomplete_stream", event); return; }
            terminated.complete(null);
            streamId = null;
            if (!stopping) startNext();
        }
    }

    void tick(long now) {
        if (stopping || !playable.getAsBoolean()) return;
        if (!pendingText.isEmpty() && now - pendingSince >= TimeUnit.MILLISECONDS.toNanos(500)) flushText();
        if (streamId != null && now - streamSince >= TimeUnit.SECONDS.toNanos(60)) {
            failure.accept("tts_stream_timeout", new JsonObject()); return;
        }
        if (socket.quiet(now, TimeUnit.SECONDS.toNanos(20))) socket.json(Map.of("keep_alive", true));
        if (bridge.outboundDepth(sessionId) > 500) failure.accept("tts_playout_backlog", new JsonObject());
    }

    CompletableFuture<Void> stop() {
        stopping = true; queue.clear(); pendingText.setLength(0); decoder.clear();
        if (streamId != null && !aborted) socket.json(Map.of("stream_id", streamId, "cancel", true));
        return terminated;
    }

    void abort() {
        stopping = true; aborted = true; queue.clear(); pendingText.setLength(0); decoder.clear();
        terminated.completeExceptionally(new SonioxSessionException(true));
        socket.abort();
    }
}
