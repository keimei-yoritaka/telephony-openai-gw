package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventPublisher;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Dedicated continuous interpretation protocol, independent of Realtime and Live turns. */
public final class TranslationSession implements VoiceSession {
    private static final System.Logger LOG = System.getLogger(TranslationSession.class.getName());
    private static final int FRAME_BYTES = 960; // 20 ms of PCM16 mono at 24 kHz.
    private final String id;
    private final OpenAiConfig config;
    private final AudioBridge bridge;
    private final BooleanSupplier callActive;
    private final ConversationEventPublisher publisher;
    private final String generation = java.util.UUID.randomUUID().toString();
    private final Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector;
    private final Duration closeTimeout;
    private final boolean automaticClock;
    private final StreamingAudioDecoder decoder;
    private final TranslationTranscriptAssembler transcripts;
    private final CompletableFuture<Void> created = new CompletableFuture<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> finalized = new CompletableFuture<>();
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean alive = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger pendingSends = new AtomicInteger();
    private final Object eventLock = new Object();
    private final Object inputLock = new Object();
    private final Object sendLock = new Object();
    private final ArrayDeque<byte[]> inputFrames = new ArrayDeque<>();
    private final ByteArrayOutputStream inputChunk = new ByteArrayOutputStream(9600);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "openai-translation-clock"); t.setDaemon(true); return t;
    });
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private volatile WebSocket socket;
    private long startedNanos;
    private long lastTickNanos;
    private long lastAudioNanos;
    private boolean firstAudio;
    private long syntheticFrames;

    public TranslationSession(String id, OpenAiConfig config, AudioBridge bridge,
            ConversationEventPublisher publisher, HttpClient client, BooleanSupplier callActive) {
        this(id, config, bridge, publisher, callActive,
                listener -> client.newWebSocketBuilder().header("Authorization", "Bearer " + config.apiKey())
                        .connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(
                                "wss://api.openai.com/v1/realtime/translations?model="
                                        + URLEncoder.encode(config.translationModel(), StandardCharsets.UTF_8)), listener),
                Duration.ofSeconds(15), true);
    }

    // The manual clock and injectable transport keep protocol tests offline and deterministic.
    TranslationSession(String id, OpenAiConfig config, AudioBridge bridge, ConversationEventPublisher publisher,
            BooleanSupplier callActive, Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector,
            Duration closeTimeout, boolean automaticClock) {
        this.id = id; this.config = config; this.bridge = bridge; this.callActive = callActive;
        this.publisher = publisher;
        this.connector = connector; this.closeTimeout = closeTimeout; this.automaticClock = automaticClock;
        this.decoder = new StreamingAudioDecoder(bridge, id);
        this.transcripts = new TranslationTranscriptAssembler(id, config.translationTargetLanguage(), publisher);
    }

    @Override public void open() {
        if (!opened.compareAndSet(false, true)) throw new IllegalStateException("Translation session already opened");
        alive.set(true);
        startedNanos = System.nanoTime();
        try {
            // Also release a socket that finishes connecting after startup has already been cancelled.
            socket = connector.apply(new Listener()).thenApply(ws -> {
                if (!alive.get() || closing.get() || !callActive.getAsBoolean()) { ws.abort(); throw new IllegalStateException("Call ended"); }
                socket = ws;
                return ws;
            }).get(10, TimeUnit.SECONDS);
            created.get(10, TimeUnit.SECONDS);
            if (!callActive.getAsBoolean() || closing.get()) throw new IllegalStateException("Call ended");
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("output", Map.of("language", config.translationTargetLanguage()));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("transcription", config.translationSourceTranscriptEnabled()
                    ? Map.of("model", config.translationTranscriptionModel()) : null);
            audio.put("input", input);
            send(Map.of("type", "session.update", "event_id", "translation-config", "session", Map.of("audio", audio)));
            ready.get(10, TimeUnit.SECONDS);
            if (!canStream()) throw new IllegalStateException("Translation ended during startup");
            status("接続済み：日本語から" + config.translationTargetLanguage() + "へ翻訳中");
            lastTickNanos = System.nanoTime();
            // 60 ms gives the native callback/forwarder a small jitter allowance before silence synthesis.
            if (automaticClock) timer.scheduleAtFixedRate(this::tick, 60, 20, TimeUnit.MILLISECONDS);
            GatewayEventLogger.info(LOG, "openai_translation_session_started", "sessionId", id,
                    "model", config.translationModel(), "targetLanguage", config.translationTargetLanguage());
        } catch (Exception error) {
            fail("startup_failed");
            throw new IllegalStateException("Translation startup failed; inspect openai_translation_error codes", error);
        }
    }

    @Override public boolean appendInputAudio(AudioFrame frame) {
        if (!canStream()) return false;
        try {
            if (!"pcm16".equals(frame.codec()) || frame.durationMs() != 20
                    || (frame.sampleRateHz() != 8000 && frame.sampleRateHz() != 16000)
                    || (frame.payload().length != 0 && frame.payload().length != frame.sampleRateHz() / 50 * 2)) {
                throw new IllegalArgumentException("Invalid translation input frame");
            }
            byte[] pcm = frame.payload().length == 0 ? new byte[FRAME_BYTES]
                    : Pcm16Resampler.upsample(frame.payload(), frame.sampleRateHz(), 24000);
            synchronized (inputLock) {
                if (!canStream()) return false;
                if (inputFrames.size() >= 100) throw new IllegalStateException("Translation input queue overflow");
                inputFrames.add(pcm);
            }
            return alive.get();
        } catch (RuntimeException error) { fail("input_failed"); return false; }
    }

    void tick() {
        if (!canStream()) return;
        try {
            long now = System.nanoTime();
            if (automaticClock && now - lastTickNanos > 1_000_000_000L) throw new IllegalStateException("Input clock stalled");
            lastTickNanos = now;
            synchronized (inputLock) {
                if (!canStream()) return;
                byte[] frame = inputFrames.poll();
                if (frame == null) { frame = new byte[FRAME_BYTES]; syntheticFrames++; }
                inputChunk.writeBytes(frame);
                if (inputChunk.size() >= 9600) flushInput();
            }
            synchronized (eventLock) {
                if (canStream() && lastAudioNanos > 0 && now - lastAudioNanos >= 100_000_000L) decoder.flush();
            }
        } catch (RuntimeException error) { fail("stream_failed"); }
    }

    private void flushInput() {
        if (inputChunk.size() == 0) return;
        send(Map.of("type", "session.input_audio_buffer.append", "audio",
                Base64.getEncoder().encodeToString(inputChunk.toByteArray())));
        inputChunk.reset();
    }

    @Override public void startInitialGreeting(String greeting) { /* Translation has no assistant greeting. */ }
    @Override public boolean isHealthy() { return alive.get() && !closing.get(); }
    @Override public boolean isIdle(long nowNanos, Duration idleTimeout) { return false; }

    private boolean canStream() {
        return alive.get() && !closing.get() && callActive.getAsBoolean() && ready.isDone() && !ready.isCompletedExceptionally();
    }

    private void send(Map<String, Object> event) {
        if (pendingSends.incrementAndGet() > 100) { pendingSends.decrementAndGet(); throw new IllegalStateException("Translation send backlog"); }
        String payload = JsonCodec.encode(event);
        synchronized (sendLock) {
            sendChain = sendChain.thenCompose(ignored -> socket.sendText(payload, true))
                    .orTimeout(5, TimeUnit.SECONDS).thenAccept(ignored -> { });
            sendChain.whenComplete((ignored, error) -> {
                pendingSends.decrementAndGet();
                if (error != null) fail("send_failed");
            });
        }
    }

    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        timer.shutdownNow();
        boolean wasReady = ready.isDone() && !ready.isCompletedExceptionally();
        if (!wasReady) ready.completeExceptionally(new IllegalStateException("Call ended during startup"));
        if (!created.isDone()) created.completeExceptionally(new IllegalStateException("Call ended during startup"));
        try {
            if (socket != null && alive.get() && wasReady) {
                synchronized (inputLock) {
                    while (!inputFrames.isEmpty()) {
                        inputChunk.writeBytes(inputFrames.remove());
                        if (inputChunk.size() >= 9600) flushInput();
                    }
                    flushInput();
                    send(Map.of("type", "session.close", "event_id", "translation-close"));
                }
                finalized.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception error) {
            GatewayEventLogger.warning(LOG, "openai_translation_finalization_unconfirmed", "sessionId", id);
        } finally {
            alive.set(false);
            synchronized (inputLock) { inputFrames.clear(); inputChunk.reset(); }
            synchronized (eventLock) { decoder.clear(); }
            WebSocket ws = socket;
            if (ws != null) {
                if (finalized.isDone() && !finalized.isCompletedExceptionally()) ws.sendClose(1000, "call ended");
                else ws.abort();
            }
            GatewayEventLogger.info(LOG, "openai_translation_session_closed", "sessionId", id,
                    "confirmed", finalized.isDone() && !finalized.isCompletedExceptionally(), "syntheticFrames", syntheticFrames);
        }
    }

    private void fail(String code) {
        if (!alive.getAndSet(false)) return;
        created.completeExceptionally(new IllegalStateException(code));
        ready.completeExceptionally(new IllegalStateException(code));
        finalized.completeExceptionally(new IllegalStateException(code));
        timer.shutdownNow();
        GatewayEventLogger.warning(LOG, "openai_translation_transport_failed", "sessionId", id, "reason", code);
        if (callActive.getAsBoolean()) status("翻訳接続が切れました。再接続待ち（この間の音声は翻訳されません）");
        WebSocket ws = socket;
        if (ws != null) ws.abort();
    }

    private void status(String text) {
        publisher.publishTranslationSnapshot(id, "status", config.translationTargetLanguage(),
                "translation-status-" + generation, "", text, java.time.Instant.now(), -1);
    }

    private void receive(JsonObject event) {
        String type = JsonCodec.string(event, "type");
        if (type.equals("session.created")) { created.complete(null); return; }
        if (type.equals("session.updated")) {
            JsonObject session = event.getAsJsonObject("session");
            String language = session.getAsJsonObject("audio").getAsJsonObject("output").get("language").getAsString();
            if (!config.translationTargetLanguage().equals(language)) { fail("target_language_mismatch"); return; }
            ready.complete(null); return;
        }
        if (type.equals("session.closed")) {
            if (!ready.isDone()) { fail("closed_before_ready"); return; }
            if (callActive.getAsBoolean()) decoder.flush();
            finalized.complete(null); alive.set(false); timer.shutdownNow();
            if (!closing.get() && callActive.getAsBoolean()) status("翻訳接続が終了しました。再接続待ち（この間の音声は翻訳されません）");
            return;
        }
        if (type.equals("error")) {
            GatewayEventLogger.warning(LOG, "openai_translation_error", "sessionId", id,
                    "code", JsonCodec.string(event.getAsJsonObject("error"), "code"));
            fail("api_error"); return;
        }
        // Drain subtitles during graceful close, but never accept callbacks from an ended generation.
        if (!alive.get() || !ready.isDone() || ready.isCompletedExceptionally()) return;
        if (!closing.get() && !callActive.getAsBoolean()) return;
        if (type.equals("session.output_audio.delta")) {
            if ((event.has("sample_rate") && event.get("sample_rate").getAsInt() != 24000)
                    || (event.has("channels") && event.get("channels").getAsInt() != 1)
                    || (event.has("format") && !JsonCodec.string(event, "format").equals("pcm16"))) {
                fail("unsupported_output_format"); return;
            }
            if (!callActive.getAsBoolean()) return;
            if (!firstAudio) {
                firstAudio = true;
                GatewayEventLogger.info(LOG, "openai_translation_first_audio", "sessionId", id,
                        "fromConnectMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            }
            lastAudioNanos = System.nanoTime();
            bridge.markContinuousAudioReceived(id, lastAudioNanos);
            decoder.append(JsonCodec.string(event, "delta"));
        } else if (type.equals("session.input_transcript.delta") || type.equals("session.output_transcript.delta")) {
            String kind = type.equals("session.input_transcript.delta") ? "source" : "translation";
            if (kind.equals("source") && !config.translationSourceTranscriptEnabled()) return;
            String text = JsonCodec.string(event, "delta");
            long elapsed = event.has("elapsed_ms") && !event.get("elapsed_ms").isJsonNull() ? event.get("elapsed_ms").getAsLong() : -1;
            transcripts.append(kind, JsonCodec.string(event, "event_id"), text, elapsed);
            if (config.transcriptLoggingEnabled()) LOG.log(System.Logger.Level.INFO,
                    "CALL_TRANSCRIPT sessionId={0} backend=translation kind={1} final=false text={2}", id, kind, JsonCodec.encode(text));
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            synchronized (eventLock) {
                if (fragments.length() + data.length() > 8 * 1024 * 1024) fail("event_too_large");
                else {
                    fragments.append(data);
                    if (last) {
                        try { receive(JsonCodec.decode(fragments.toString())); }
                        catch (RuntimeException error) { fail("invalid_event"); }
                        fragments.setLength(0);
                    }
                }
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (!finalized.isDone()) fail("closed_without_confirmation");
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) { fail("websocket_error"); }
    }
}
