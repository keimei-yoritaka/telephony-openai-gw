package com.example.telephonygw.soniox;

import com.example.telephonygw.config.GatewayConfig.SonioxConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventPublisher;
import com.example.telephonygw.openai.Pcm16Resampler;
import com.example.telephonygw.openai.VoiceSession;
import com.google.gson.JsonObject;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Soniox STT translation + TTS, with isolated per-call transport and playback. */
public final class SonioxTranslationSession implements VoiceSession {
    private static final System.Logger LOG = System.getLogger(SonioxTranslationSession.class.getName());
    private final Object lock = new Object();
    private final String id;
    private final String generation = UUID.randomUUID().toString();
    private final SonioxConfig config;
    private final AudioBridge bridge;
    private final ConversationEventPublisher publisher;
    private final BooleanSupplier callActive;
    private final SonioxSocket stt;
    private final SonioxTtsClient tts;
    private final SonioxTranscriptAssembler transcripts;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "soniox-session-timer"); thread.setDaemon(true); return thread;
    });
    private final boolean automaticTimer;
    private final Duration closeTimeout;
    private volatile boolean healthy;
    private volatile boolean retryable = true;
    private boolean opened;
    private boolean closing;
    private boolean ready;
    private boolean completed;
    private long started;
    private long firstInput;
    private boolean firstTranslation;
    private long statusSequence;

    public SonioxTranslationSession(String id, SonioxConfig config, AudioBridge bridge,
            ConversationEventPublisher publisher, HttpClient client, BooleanSupplier callActive) {
        this(id, config, bridge, publisher, callActive,
                SonioxSocket.connector(client, config.apiKey(), "wss://stt-rt.soniox.com/transcribe-websocket"),
                SonioxSocket.connector(client, config.apiKey(), "wss://tts-rt.soniox.com/tts-websocket"),
                true, Duration.ofSeconds(15));
    }

    // Injectable sockets and manual timer keep protocol and race checks offline.
    SonioxTranslationSession(String id, SonioxConfig config, AudioBridge bridge,
            ConversationEventPublisher publisher, BooleanSupplier callActive,
            Function<WebSocket.Listener, CompletableFuture<WebSocket>> sttConnector,
            Function<WebSocket.Listener, CompletableFuture<WebSocket>> ttsConnector,
            boolean automaticTimer, Duration closeTimeout) {
        this.id = id; this.config = config; this.bridge = bridge; this.publisher = publisher;
        this.callActive = callActive; this.automaticTimer = automaticTimer; this.closeTimeout = closeTimeout;
        stt = new SonioxSocket(lock, this::receive, code -> fail(code, new JsonObject()), sttConnector);
        tts = new SonioxTtsClient(id, config, bridge, this::canPlay, lock, this::fail, ttsConnector);
        transcripts = new SonioxTranscriptAssembler(id, config.translationTargetLanguage(), publisher);
    }

    @Override public void open() {
        synchronized (lock) {
            if (opened || closing) throw new SonioxSessionException(retryable);
            opened = true; healthy = true; started = System.nanoTime();
        }
        try {
            tts.open();
            synchronized (lock) { requireActive(); }
            stt.open();
            CompletableFuture<Void> configured;
            synchronized (lock) {
                requireActive();
                configured = stt.json(Map.of("model", config.sttModel(), "audio_format", "pcm_s16le",
                        "sample_rate", 16000, "num_channels", 1, "language_hints", config.languageHints(),
                        "enable_language_identification", true, "enable_endpoint_detection", true,
                        "max_endpoint_delay_ms", config.maxEndpointDelayMs(),
                        "translation", Map.of("type", "one_way", "target_language", config.translationTargetLanguage())));
            }
            configured.get(5, TimeUnit.SECONDS);
            synchronized (lock) {
                requireActive(); ready = true;
                status("接続済み：Sonioxで" + config.directionLabel() + "へ翻訳中（認証エラーは後着する場合があります）");
                GatewayEventLogger.info(LOG, "soniox_session_started", "sessionId", id,
                        "model", config.sttModel(), "ttsModel", config.ttsModel(), "voice", config.ttsVoice(),
                        "targetLanguage", config.translationTargetLanguage());
                if (automaticTimer) timer.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
            }
        } catch (Exception error) {
            synchronized (lock) { fail("startup_failed", new JsonObject()); }
            throw new SonioxSessionException(retryable);
        }
    }

    private void requireActive() {
        if (!healthy || closing || !callActive.getAsBoolean()) throw new SonioxSessionException(retryable);
    }

    private boolean canPlay() { return healthy && !closing && callActive.getAsBoolean(); }

    @Override public boolean appendInputAudio(AudioFrame frame) {
        synchronized (lock) {
            if (!ready || !canPlay()) return false;
            try {
                int rate = frame.sampleRateHz();
                byte[] bytes = frame.payload();
                if (!frame.sessionId().equals(id) || frame.direction() != AudioFrame.Direction.INBOUND
                        || !"pcm16".equals(frame.codec()) || frame.durationMs() != 20
                        || (rate != 8000 && rate != 16000) || (bytes.length != 0 && bytes.length != rate / 50 * 2))
                    throw new IllegalArgumentException();
                // PJSUA2 may signal no media with an empty frame; retain the STT keepalive policy.
                if (bytes.length == 0) return true;
                if (firstInput == 0) {
                    firstInput = System.nanoTime();
                    GatewayEventLogger.info(LOG, "soniox_first_input", "sessionId", id,
                            "fromConnectMs", TimeUnit.NANOSECONDS.toMillis(firstInput - started));
                }
                stt.binary(Pcm16Resampler.upsample(bytes, rate, 16000));
                return healthy;
            } catch (RuntimeException error) { fail("invalid_input_audio", new JsonObject()); return false; }
        }
    }

    private void receive(JsonObject event) {
        if (event.has("error_code")) { fail("stt_api_error", event); return; }
        if (completed) return;
        if (event.has("tokens")) {
            transcripts.accept(event.getAsJsonArray("tokens"), text -> {
                if (!firstTranslation) {
                    firstTranslation = true;
                    GatewayEventLogger.info(LOG, "soniox_first_translation", "sessionId", id,
                            "fromConnectMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                }
                if (canPlay()) tts.append(text);
            }, () -> { if (canPlay()) tts.flushText(); });
            if (config.transcriptLoggingEnabled()) {
                // Only token text is logged, never raw response bodies or credentials.
                for (var value : event.getAsJsonArray("tokens")) {
                    JsonObject token = value.getAsJsonObject();
                    if (SonioxSocket.flag(token, "is_final")) LOG.log(System.Logger.Level.INFO,
                            "CALL_TRANSCRIPT sessionId={0} backend=soniox_translation kind={1} text={2}", id,
                            SonioxSocket.string(token, "translation_status"),
                            SonioxSocket.JSON.toJson(SonioxSocket.string(token, "text")));
                }
            }
        }
        if (SonioxSocket.flag(event, "finished")) {
            completed = true; finished.complete(null); stt.abort();
            if (!closing) fail("stream_finished_reconnect", new JsonObject());
        }
    }

    void tick() {
        synchronized (lock) {
            if (!ready || !canPlay()) return;
            try {
                long now = System.nanoTime();
                if (now - started >= TimeUnit.MINUTES.toNanos(300)) { fail("max_duration_reached", new JsonObject()); return; }
                if (stt.quiet(now, TimeUnit.SECONDS.toNanos(5))) stt.json(Map.of("type", "keepalive"));
                tts.tick(now);
            } catch (RuntimeException error) { fail("timer_failed", new JsonObject()); }
        }
    }

    private void fail(String code, JsonObject event) {
        synchronized (lock) {
            if (!healthy) return;
            int errorCode = event.has("error_code") ? event.get("error_code").getAsInt() : 0;
            retryable = !(errorCode == 400 || errorCode == 401 || errorCode == 402 || errorCode == 403);
            healthy = false; ready = false;
            String request = SonioxSocket.string(event, "request_id").replaceAll("[^A-Za-z0-9_-]", "");
            if (request.length() > 128) request = request.substring(0, 128);
            GatewayEventLogger.warning(LOG, "soniox_error", "sessionId", id, "code", code,
                    "apiCode", errorCode, "requestId", request, "retryable", retryable);
            status("Soniox " + code + (retryable ? "：翻訳を再接続します。音声・未再生訳は再送しません" : "：設定・認証・残高を確認し、通話をかけ直してください"));
            finished.completeExceptionally(new SonioxSessionException(retryable));
            timer.shutdownNow(); stt.abort(); tts.abort();
            bridge.clearOutbound(id); bridge.clearInbound(id);
        }
    }

    private void status(String text) {
        publisher.publishTranslationSnapshot(id, "status", config.translationTargetLanguage(), "soniox-" + generation + "-status-" + (++statusSequence),
                "", text, Instant.now(), -1);
    }

    @Override public boolean isHealthy() { return healthy; }
    public boolean retryable() { return retryable; }
    @Override public void startInitialGreeting(String greeting) { }
    @Override public boolean isIdle(long now, Duration timeout) { return false; }

    @Override public void close() {
        CompletableFuture<Void> ttsClosed;
        CompletableFuture<Void> inputClosed;
        synchronized (lock) {
            if (closing) return;
            closing = true; ready = false; timer.shutdownNow();
            ttsClosed = tts.stop();
            inputClosed = healthy ? stt.text("") : CompletableFuture.completedFuture(null);
        }
        boolean confirmed = false;
        boolean ttsConfirmed = false;
        try {
            CompletableFuture.allOf(inputClosed, finished).get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            confirmed = true;
        } catch (Exception ignored) { }
        try { ttsClosed.get(Math.min(5000, closeTimeout.toMillis()), TimeUnit.MILLISECONDS); ttsConfirmed = true; }
        catch (Exception ignored) { }
        synchronized (lock) {
            healthy = false; stt.abort(); tts.abort();
            GatewayEventLogger.info(LOG, "soniox_session_closed", "sessionId", id,
                    "finishedConfirmed", confirmed, "ttsTerminatedConfirmed", ttsConfirmed);
        }
    }
}
