package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.BotConfig;
import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventPublisher;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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

/** GPT-Live speech only. No Responses backend, function tools, or delegated task execution. */
public final class LiveSession implements VoiceSession {
    private static final System.Logger LOG = System.getLogger(LiveSession.class.getName());
    private static final URI ENDPOINT = URI.create("wss://api.openai.com/v1/live/sessions");
    private static final String NO_DELEGATION = "\n外部の委譲先や業務ツールはありません。委譲・検索・予約変更などを要求せず、"
            + "会話だけで応対してください。実際の照会や操作が必要な依頼は、この窓口では実行できないと伝えてください。";
    private final String callSessionId;
    private final OpenAiConfig config;
    private final BotConfig bot;
    private final AudioBridge bridge;
    private final ConversationEventPublisher publisher;
    private final BooleanSupplier callActive;
    private final Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector;
    private final Duration closeTimeout;
    private final LiveAudioDecoder decoder;
    private final LiveTranscriptAssembler transcripts;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> finalized = new CompletableFuture<>();
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean alive = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean greeted = new AtomicBoolean();
    private final AtomicInteger pendingSends = new AtomicInteger();
    private final Object sendLock = new Object();
    private final Object eventLock = new Object();
    private final ScheduledExecutorService timer;
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private volatile WebSocket socket;
    private volatile long lastAudioNanos;
    private volatile boolean firstAudio;
    private volatile double usageSeconds;
    private volatile long startedNanos;

    public LiveSession(String id, OpenAiConfig config, BotConfig bot, AudioBridge bridge,
            ConversationEventPublisher publisher, HttpClient client, BooleanSupplier callActive) {
        this(id, config, bot, bridge, publisher, callActive,
                listener -> client.newWebSocketBuilder().header("Authorization", "Bearer " + config.apiKey())
                        .connectTimeout(Duration.ofSeconds(10)).buildAsync(ENDPOINT, listener), Duration.ofSeconds(15));
    }

    // Injectable transport enables deterministic lifecycle tests without an API key.
    LiveSession(String id, OpenAiConfig config, BotConfig bot, AudioBridge bridge,
            ConversationEventPublisher publisher, BooleanSupplier callActive,
            Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector, Duration closeTimeout) {
        this.callSessionId = id;
        this.config = config;
        this.bot = bot;
        this.bridge = bridge;
        this.publisher = publisher;
        this.callActive = callActive;
        this.connector = connector;
        this.closeTimeout = closeTimeout;
        this.decoder = new LiveAudioDecoder(bridge, id);
        this.transcripts = new LiveTranscriptAssembler(id, publisher);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "openai-live-audio-tail");
            t.setDaemon(true);
            return t;
        });
    }

    @Override public void open() {
        if (!opened.compareAndSet(false, true)) throw new IllegalStateException("Live session already opened");
        startedNanos = System.nanoTime();
        alive.set(true);
        try {
            socket = connector.apply(new Listener()).get(10, TimeUnit.SECONDS);
            if (!callActive.getAsBoolean() || closing.get()) throw new IllegalStateException("Call already ended");
            send(startEvent());
            ready.get(10, TimeUnit.SECONDS);
            if (!canStream()) throw new IllegalStateException("Live session ended during startup");
            timer.scheduleAtFixedRate(() -> {
                synchronized (eventLock) {
                    if (canStream() && lastAudioNanos > 0 && System.nanoTime() - lastAudioNanos >= 60_000_000L) {
                        decoder.flush();
                    }
                }
            }, 20, 20, TimeUnit.MILLISECONDS);
            GatewayEventLogger.info(LOG, "openai_live_session_started", "sessionId", callSessionId,
                    "model", config.liveModel(), "voice", config.liveVoice(),
                    "startupMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                    "delegationEnabled", false);
        } catch (Exception error) {
            fail("startup_failed");
            if (socket != null) socket.abort();
            timer.shutdownNow();
            throw new IllegalStateException("GPT-Live startup failed; inspect openai_live_error codes", error);
        }
    }

    private Map<String, Object> startEvent() {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("model", config.liveModel());
        session.put("instructions", bot.systemInstructions() + NO_DELEGATION);
        session.put("audio", Map.of("format", Map.of("type", "audio/pcm", "rate", 24000),
                "output", Map.of("voice", config.liveVoice())));
        session.put("store", false);
        List<Map<String, Object>> history = new ArrayList<>();
        var events = publisher.recentTranscripts(callSessionId);
        int remainingCharacters = 2000;
        for (int i = events.size() - 1; i >= Math.max(0, events.size() - 20) && remainingCharacters > 0; i--) {
            var event = events.get(i);
            String text = event.text();
            if (text.length() > remainingCharacters) text = text.substring(text.length() - remainingCharacters);
            remainingCharacters -= text.length();
            boolean caller = "caller".equals(event.speaker());
            history.add(0, Map.of("type", "message", "role", caller ? "user" : "assistant",
                    "content", List.of(Map.of("type", caller ? "input_text" : "output_text", "text", text))));
        }
        if (!history.isEmpty()) session.put("input", history);
        // Omitted delegation selects the API's client mode; no backend is connected.
        return Map.of("type", "session.start", "event_id", "start", "session", session);
    }

    @Override public boolean appendInputAudio(AudioFrame frame) {
        if (!canStream()) return false;
        try {
            byte[] pcm = Pcm16Resampler.upsample(frame.payload(), frame.sampleRateHz(), 24000);
            if (pcm.length == 0) return true;
            send(Map.of("type", "session.input_audio.append", "audio", Base64.getEncoder().encodeToString(pcm)));
            return alive.get();
        } catch (RuntimeException error) {
            fail("input_send_failed");
            return false;
        }
    }

    @Override public void startInitialGreeting(String greeting) {
        if (!canStream() || !greeted.compareAndSet(false, true)) return;
        appendInstruction("日本語で今すぐ次の挨拶を自然に話してから、相手の発話を聞いてください: " + greeting, null);
    }

    private void appendInstruction(String content, String delegationId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "session.instructions.append");
        event.put("delegation_id", delegationId);
        event.put("content", content);
        send(event);
    }

    private boolean canStream() {
        return alive.get() && !closing.get() && callActive.getAsBoolean() && ready.isDone()
                && !ready.isCompletedExceptionally();
    }

    private void send(Map<String, Object> event) {
        if (pendingSends.incrementAndGet() > 100) {
            pendingSends.decrementAndGet();
            throw new IllegalStateException("Live send queue overflow");
        }
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

    @Override public boolean isIdle(long nowNanos, Duration idleTimeout) { return false; }

    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        timer.shutdownNow();
        if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("Call ended during startup"));
        synchronized (eventLock) { decoder.clear(); }
        try {
            if (socket != null && alive.get() && ready.isDone() && !ready.isCompletedExceptionally()) {
                send(Map.of("type", "session.close", "event_id", "close"));
                finalized.get(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception error) {
            GatewayEventLogger.warning(LOG, "openai_live_finalization_unconfirmed", "sessionId", callSessionId,
                    "observedSeconds", usageSeconds);
        } finally {
            alive.set(false);
            WebSocket current = socket;
            if (current != null) {
                if (finalized.isDone() && !finalized.isCompletedExceptionally()) current.sendClose(1000, "call ended");
                else current.abort();
            }
        }
    }

    private void fail(String reason) {
        if (alive.getAndSet(false)) {
            ready.completeExceptionally(new IllegalStateException(reason));
            finalized.completeExceptionally(new IllegalStateException(reason));
            timer.shutdownNow();
            GatewayEventLogger.warning(LOG, "openai_live_transport_failed", "sessionId", callSessionId, "reason", reason, "observedSeconds", usageSeconds, "finalUsageConfirmed", false);
            WebSocket current = socket;
            if (current != null) current.abort();
        }
    }

    private void receive(JsonObject event) {
        String type = JsonCodec.string(event, "type");
        if (type.equals("session.started")) { ready.complete(null); return; }
        if (type.equals("session.closed")) {
            usageSeconds = event.has("usage") ? event.getAsJsonObject("usage").get("seconds").getAsDouble() : usageSeconds;
            GatewayEventLogger.info(LOG, "openai_live_session_closed", "sessionId", callSessionId,
                    "reason", JsonCodec.string(event, "reason"), "seconds", usageSeconds, "finalUsageConfirmed", true);
            finalized.complete(null);
            alive.set(false);
            timer.shutdownNow();
            if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("closed before ready"));
            return;
        }
        if (type.equals("error")) {
            JsonObject error = event.getAsJsonObject("error");
            // Do not log arbitrary remote messages, which may echo input or credentials.
            GatewayEventLogger.warning(LOG, "openai_live_error", "sessionId", callSessionId,
                    "code", JsonCodec.string(error, "code"), "clientEventId", JsonCodec.string(error, "client_event_id"));
            if (!ready.isDone()) fail("start_rejected");
            return;
        }
        if (type.equals("session.usage.updated")) {
            usageSeconds = event.getAsJsonObject("usage").get("seconds").getAsDouble();
            GatewayEventLogger.info(LOG, "openai_live_usage", "sessionId", callSessionId, "seconds", usageSeconds);
            return;
        }
        if (!canStream()) return;
        if (type.equals("session.output_audio.delta")) {
            if (!firstAudio) {
                firstAudio = true;
                GatewayEventLogger.info(LOG, "openai_live_first_audio", "sessionId", callSessionId,
                        "fromConnectMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            }
            lastAudioNanos = System.nanoTime();
            bridge.markLiveAudioReceived(callSessionId, lastAudioNanos);
            decoder.append(JsonCodec.string(event, "delta"));
        } else if (type.equals("session.input_transcript.delta") || type.equals("session.output_transcript.delta")) {
            String speaker = type.equals("session.input_transcript.delta") ? "caller" : "assistant";
            String text = JsonCodec.string(event, "delta");
            transcripts.append(speaker, JsonCodec.string(event, "event_id"), text,
                    event.get("start_ms").getAsLong(), event.get("end_ms").getAsLong());
            if (config.transcriptLoggingEnabled()) {
                LOG.log(System.Logger.Level.INFO, "CALL_TRANSCRIPT sessionId={0} backend=live speaker={1} final=false text={2}",
                        callSessionId, speaker, JsonCodec.encode(text));
            }
        } else if (type.equals("session.delegation.created")) {
            GatewayEventLogger.warning(LOG, "openai_live_delegation_unavailable", "sessionId", callSessionId);
            // Acknowledge the unavailable operation without starting delegated work or calling a tool.
            appendInstruction("この窓口には委譲先がなく、その照会や操作は実行できません。"
                    + "実行したと伝えず、会話だけで応対してください。", JsonCodec.string(event.getAsJsonObject("delegation"), "id"));
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (eventLock) {
                if (fragments.length() + data.length() > 8 * 1024 * 1024) {
                    fail("event_too_large");
                } else {
                    fragments.append(data);
                    if (last) {
                        try { receive(JsonCodec.decode(fragments.toString())); }
                        catch (RuntimeException error) { fail("invalid_event"); }
                        fragments.setLength(0);
                    }
                }
            }
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            if (!finalized.isDone()) fail("closed_without_final_usage");
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { fail("websocket_error"); }
    }
}
