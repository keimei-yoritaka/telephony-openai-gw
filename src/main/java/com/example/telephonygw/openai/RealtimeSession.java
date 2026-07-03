package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiRuntimeConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventPublisher;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RealtimeSession implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RealtimeSession.class.getName());
    private static final URI REALTIME_ENDPOINT = URI.create("wss://api.openai.com/v1/realtime");
    private static final int OPENAI_AUDIO_SAMPLE_RATE_HZ = 24000;
    private static final int DEFAULT_RTP_AUDIO_SAMPLE_RATE_HZ = 8000;
    private static final int RTP_FRAME_DURATION_MS = 20;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SEND_TIMEOUT_SECONDS = 5;

    private final String callSessionId;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String maxOutputTokens;
    private final String turnDetectionType;
    private final String turnDetectionEagerness;
    private final boolean transcriptLoggingEnabled;
    private final String inputTranscriptionModel;
    private final String inputTranscriptionLanguage;
    private final String systemInstructions;
    private final OpenAiRuntimeConfig runtimeConfig;
    private final AudioBridge audioBridge;
    private final ConversationEventPublisher conversationEventPublisher;
    private final HttpClient httpClient;
    private final Object sendLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong receivedOutputChunks = new AtomicLong();
    private final AtomicLong queuedOutputFrames = new AtomicLong();
    private final AtomicBoolean initialGreetingStarted = new AtomicBoolean(false);
    private final AtomicBoolean responseActive = new AtomicBoolean(false);
    private final AtomicBoolean bargeInCancelledForSpeech = new AtomicBoolean(false);
    private final AtomicLong userSpeechStartedNanos = new AtomicLong();
    private final AtomicLong assistantAudioStartedNanos = new AtomicLong();
    private final ByteArrayOutputStream pendingOutputPcm = new ByteArrayOutputStream();
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private volatile long lastFrameNanos;
    private volatile WebSocket webSocket;

    public RealtimeSession(
            String callSessionId,
            String apiKey,
            String model,
            String voice,
            String maxOutputTokens,
            String turnDetectionType,
            String turnDetectionEagerness,
            boolean transcriptLoggingEnabled,
            String inputTranscriptionModel,
            String inputTranscriptionLanguage,
            String systemInstructions,
            OpenAiRuntimeConfig runtimeConfig,
            AudioBridge audioBridge,
            ConversationEventPublisher conversationEventPublisher,
            HttpClient httpClient
    ) {
        this.callSessionId = Objects.requireNonNull(callSessionId, "callSessionId");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        this.turnDetectionType = Objects.requireNonNull(turnDetectionType, "turnDetectionType");
        this.turnDetectionEagerness = Objects.requireNonNull(turnDetectionEagerness, "turnDetectionEagerness");
        this.transcriptLoggingEnabled = transcriptLoggingEnabled;
        this.inputTranscriptionModel = Objects.requireNonNull(inputTranscriptionModel, "inputTranscriptionModel");
        this.inputTranscriptionLanguage = Objects.requireNonNull(inputTranscriptionLanguage, "inputTranscriptionLanguage");
        this.systemInstructions = Objects.requireNonNull(systemInstructions, "systemInstructions");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.audioBridge = Objects.requireNonNull(audioBridge, "audioBridge");
        this.conversationEventPublisher = Objects.requireNonNull(
                conversationEventPublisher, "conversationEventPublisher");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public void open() {
        if (!open.compareAndSet(false, true)) {
            return;
        }

        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .buildAsync(sessionUri(), new SessionListener(callSessionId, audioBridge, pendingOutputPcm,
                            receivedOutputChunks, queuedOutputFrames, conversationEventPublisher,
                            runtimeConfig, responseActive, userSpeechStartedNanos,
                            assistantAudioStartedNanos, bargeInCancelledForSpeech))
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sendText(sessionUpdateEvent());
            LOG.log(System.Logger.Level.INFO,
                    "Opened OpenAI Realtime session: sessionId={0}, model={1}, voice={2}, inputRateHz={3}, maxOutputTokens={4}, turnDetection={5}",
                    callSessionId, model, voice, OPENAI_AUDIO_SAMPLE_RATE_HZ, maxOutputTokens, turnDetectionType);
            GatewayEventLogger.info(LOG, "openai_realtime_session_opened",
                    "sessionId", callSessionId,
                    "model", model,
                    "voice", voice,
                    "inputRateHz", OPENAI_AUDIO_SAMPLE_RATE_HZ,
                    "maxOutputTokens", maxOutputTokens,
                    "turnDetection", turnDetectionType,
                    "transcriptLoggingEnabled", transcriptLoggingEnabled,
                    "cancelResponseOnUserSpeech", runtimeConfig.cancelResponseOnUserSpeech(),
                    "dropInputAudioWhileAssistantSpeaking", runtimeConfig.dropInputAudioWhileAssistantSpeaking(),
                    "bargeInMinSpeechMs", runtimeConfig.bargeInMinSpeechMs(),
                    "bargeInMinRmsDb", runtimeConfig.bargeInMinRmsDb(),
                    "bargeInGraceMsAfterAssistantStarts", runtimeConfig.bargeInGraceMsAfterAssistantStarts());
        } catch (Exception e) {
            open.set(false);
            throw new IllegalStateException("Failed to open OpenAI Realtime WebSocket session", e);
        }
    }

    public boolean appendInputAudio(AudioFrame frame) {
        if (!open.get() || webSocket == null) {
            return false;
        }
        if (frame.payload().length == 0) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Skipped empty audio frame for OpenAI Realtime session: sessionId={0}",
                    callSessionId);
            return false;
        }
        try {
            byte[] audio = Pcm16Resampler.upsample(frame.payload(), frame.sampleRateHz(), OPENAI_AUDIO_SAMPLE_RATE_HZ);
            if (audio.length == 0) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Skipped empty resampled audio for OpenAI Realtime session: sessionId={0}",
                        callSessionId);
                return false;
            }
            String encoded = Base64.getEncoder().encodeToString(audio);
            sendText("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + encoded + "\"}");
            sentFrames.incrementAndGet();
            sentBytes.addAndGet(audio.length);
            lastFrameNanos = System.nanoTime();
            maybeCancelResponseForUserSpeech(frame.payload(), lastFrameNanos);
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to append audio to OpenAI Realtime session: sessionId={0}, error={1}",
                    callSessionId, e.getMessage());
            return false;
        }
    }

    public void startInitialGreeting(String greeting) {
        if (!open.get() || webSocket == null || !initialGreetingStarted.compareAndSet(false, true)) {
            return;
        }
        String instructions = "通話が接続された直後の最初の発話として、次の文だけを自然に話してください: " + greeting;
        sendText("""
                {"type":"response.create","response":{"instructions":"%s","output_modalities":["audio"]}}\
                """.formatted(json(instructions)));
        LOG.log(System.Logger.Level.INFO,
                "Requested OpenAI initial greeting: sessionId={0}",
                callSessionId);
        GatewayEventLogger.info(LOG, "openai_initial_greeting_requested",
                "sessionId", callSessionId);
    }

    public boolean isIdle(long nowNanos, Duration idleTimeout) {
        long last = lastFrameNanos;
        return last > 0L && nowNanos - last >= idleTimeout.toNanos();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            WebSocket socket = webSocket;
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "call session closed");
            }
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI Realtime session: sessionId={0}, sentFrames={1}, sentBytes={2}, receivedOutputChunks={3}, queuedOutputFrames={4}",
                    callSessionId, sentFrames.get(), sentBytes.get(),
                    receivedOutputChunks.get(), queuedOutputFrames.get());
            GatewayEventLogger.info(LOG, "openai_realtime_session_closed",
                    "sessionId", callSessionId,
                    "sentFrames", sentFrames.get(),
                    "sentBytes", sentBytes.get(),
                    "receivedOutputChunks", receivedOutputChunks.get(),
                    "queuedOutputFrames", queuedOutputFrames.get());
        }
    }

    private URI sessionUri() {
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
        return URI.create(REALTIME_ENDPOINT + "?model=" + encodedModel);
    }

    private void sendText(String payload) {
        WebSocket socket = webSocket;
        if (socket == null) {
            throw new IllegalStateException("OpenAI Realtime WebSocket is not connected");
        }
        synchronized (sendLock) {
            CompletableFuture<Void> next = sendChain
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> socket.sendText(payload, true))
                    .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .thenAccept(ignored -> {
                    });
            sendChain = next.whenComplete((ignored, error) -> {
                if (error != null && open.compareAndSet(true, false)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "OpenAI Realtime send failed: sessionId={0}, error={1}",
                            callSessionId, error.getMessage());
                    GatewayEventLogger.warning(LOG, "openai_send_failed",
                            "sessionId", callSessionId,
                            "error", error.getMessage());
                    WebSocket current = webSocket;
                    if (current != null) {
                        current.abort();
                    }
                }
            });
        }
    }

    private String sessionUpdateEvent() {
        return """
                {"type":"session.update","session":{"type":"realtime","model":"%s","instructions":"%s","max_output_tokens":%s,"output_modalities":["audio"],"audio":{"input":{"format":{"type":"audio/pcm","rate":%d}%s,"turn_detection":%s},"output":{"format":{"type":"audio/pcm","rate":%d},"voice":"%s"}}}}\
                """.formatted(json(model), json(systemInstructions), maxOutputTokensJson(),
                OPENAI_AUDIO_SAMPLE_RATE_HZ, transcriptionJson(), turnDetectionJson(),
                OPENAI_AUDIO_SAMPLE_RATE_HZ, json(voice));
    }

    private String maxOutputTokensJson() {
        return "inf".equalsIgnoreCase(maxOutputTokens) ? "\"inf\"" : maxOutputTokens;
    }

    private String turnDetectionJson() {
        String interruptResponse = Boolean.toString(runtimeConfig.cancelResponseOnUserSpeech());
        if ("semantic_vad".equalsIgnoreCase(turnDetectionType)) {
            return """
                    {"type":"semantic_vad","eagerness":"%s","create_response":true,"interrupt_response":%s}\
                    """.formatted(json(turnDetectionEagerness), interruptResponse);
        }
        return """
                {"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,"silence_duration_ms":800,"create_response":true,"interrupt_response":%s}\
                """.formatted(interruptResponse);
    }

    private void maybeCancelResponseForUserSpeech(byte[] pcm16, long nowNanos) {
        if (!runtimeConfig.cancelResponseOnUserSpeech()
                || !responseActive.get()
                || !bargeInCancelledForSpeech.compareAndSet(false, true)) {
            return;
        }

        long speechStartedAt = userSpeechStartedNanos.get();
        long assistantAudioStartedAt = assistantAudioStartedNanos.get();
        double rmsDb = rmsDbfs(pcm16);
        long speechDurationMs = elapsedMillis(speechStartedAt, nowNanos);
        long assistantAudioElapsedMs = elapsedMillis(assistantAudioStartedAt, nowNanos);
        if (speechStartedAt <= 0L
                || assistantAudioStartedAt <= 0L
                || speechDurationMs < runtimeConfig.bargeInMinSpeechMs()
                || assistantAudioElapsedMs < runtimeConfig.bargeInGraceMsAfterAssistantStarts()
                || rmsDb < runtimeConfig.bargeInMinRmsDb()) {
            bargeInCancelledForSpeech.set(false);
            return;
        }

        if (!responseActive.compareAndSet(true, false)) {
            return;
        }
        int clearedFrames = audioBridge.clearOutbound(callSessionId);
        synchronized (pendingOutputPcm) {
            pendingOutputPcm.reset();
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendText("{\"type\":\"response.cancel\"}", true)
                    .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(error -> {
                        LOG.log(System.Logger.Level.DEBUG,
                                "OpenAI response cancel failed or was not needed: sessionId={0}, error={1}",
                                callSessionId, error.getMessage());
                        return null;
                    });
        }
        LOG.log(System.Logger.Level.INFO,
                "Cancelled OpenAI response because sustained user speech was detected: sessionId={0}, speechDurationMs={1}, rmsDb={2}, clearedFrames={3}",
                callSessionId, speechDurationMs, String.format("%.1f", rmsDb), clearedFrames);
        GatewayEventLogger.info(LOG, "openai_response_cancelled_for_barge_in",
                "sessionId", callSessionId,
                "speechDurationMs", speechDurationMs,
                "rmsDb", String.format("%.1f", rmsDb),
                "clearedFrames", clearedFrames,
                "bargeInMinSpeechMs", runtimeConfig.bargeInMinSpeechMs(),
                "bargeInMinRmsDb", runtimeConfig.bargeInMinRmsDb());
    }

    private String transcriptionJson() {
        if (!transcriptLoggingEnabled) {
            return "";
        }
        String languageField = inputTranscriptionLanguage.isBlank()
                ? ""
                : ",\"language\":\"" + json(inputTranscriptionLanguage) + "\"";
        return ",\"transcription\":{\"model\":\"" + json(inputTranscriptionModel) + "\"" + languageField + "}";
    }

    private static double rmsDbfs(byte[] pcm16) {
        if (pcm16.length < 2) {
            return -100.0;
        }
        long samples = 0;
        double sumSquares = 0.0;
        for (int i = 0; i + 1 < pcm16.length; i += 2) {
            int sample = (pcm16[i] & 0xff) | (pcm16[i + 1] << 8);
            sumSquares += (double) sample * sample;
            samples++;
        }
        if (samples == 0 || sumSquares <= 0.0) {
            return -100.0;
        }
        double rms = Math.sqrt(sumSquares / samples);
        return 20.0 * Math.log10(rms / 32768.0);
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        if (startNanos <= 0L || endNanos <= 0L || endNanos < startNanos) {
            return -1L;
        }
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    private static String json(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class SessionListener implements WebSocket.Listener {
        private final String callSessionId;
        private final AudioBridge audioBridge;
        private final ByteArrayOutputStream pendingOutputPcm;
        private final AtomicLong receivedOutputChunks;
        private final AtomicLong queuedOutputFrames;
        private final ConversationEventPublisher conversationEventPublisher;
        private final OpenAiRuntimeConfig runtimeConfig;
        private final AtomicBoolean responseActive;
        private final AtomicLong sharedUserSpeechStartedNanos;
        private final AtomicLong assistantAudioStartedNanos;
        private final AtomicBoolean bargeInCancelledForSpeech;
        private final StringBuilder message = new StringBuilder();
        private long speechStartedNanos;
        private long speechStoppedNanos;
        private long inputCommittedNanos;
        private long responseCreatedNanos;
        private long firstAudioDeltaNanos;
        private long outputAudioDoneNanos;
        private long currentResponseChunks;
        private long currentResponsePcm24Bytes;
        private long currentResponseQueuedFrames;
        private long currentResponseDroppedFrames;

        private SessionListener(
                String callSessionId,
                AudioBridge audioBridge,
                ByteArrayOutputStream pendingOutputPcm,
                AtomicLong receivedOutputChunks,
                AtomicLong queuedOutputFrames,
                ConversationEventPublisher conversationEventPublisher,
                OpenAiRuntimeConfig runtimeConfig,
                AtomicBoolean responseActive,
                AtomicLong sharedUserSpeechStartedNanos,
                AtomicLong assistantAudioStartedNanos,
                AtomicBoolean bargeInCancelledForSpeech
        ) {
            this.callSessionId = callSessionId;
            this.audioBridge = audioBridge;
            this.pendingOutputPcm = pendingOutputPcm;
            this.receivedOutputChunks = receivedOutputChunks;
            this.queuedOutputFrames = queuedOutputFrames;
            this.conversationEventPublisher = conversationEventPublisher;
            this.runtimeConfig = runtimeConfig;
            this.responseActive = responseActive;
            this.sharedUserSpeechStartedNanos = sharedUserSpeechStartedNanos;
            this.assistantAudioStartedNanos = assistantAudioStartedNanos;
            this.bargeInCancelledForSpeech = bargeInCancelledForSpeech;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            LOG.log(System.Logger.Level.INFO,
                    "OpenAI Realtime WebSocket connected: sessionId={0}",
                    callSessionId);
            GatewayEventLogger.info(LOG, "openai_websocket_connected",
                    "sessionId", callSessionId);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String payload = message.toString();
                message.setLength(0);
                String eventType = extractEventType(payload);
                if (eventType.equals("error")) {
                    String code = extractStringField(payload, "code", 0);
                    if (code.equals("response_cancel_not_active")) {
                        LOG.log(System.Logger.Level.DEBUG,
                                "Ignored inactive OpenAI response cancel: sessionId={0}, message={1}",
                                callSessionId,
                                extractStringField(payload, "message", 0));
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                    LOG.log(System.Logger.Level.WARNING,
                            "Received OpenAI Realtime error event: sessionId={0}, errorType={1}, code={2}, message={3}",
                            callSessionId,
                            extractStringField(payload, "type", payload.indexOf("\"error\"")),
                            code,
                            extractStringField(payload, "message", 0));
                    GatewayEventLogger.warning(LOG, "openai_error_event",
                            "sessionId", callSessionId,
                            "code", code,
                            "message", extractStringField(payload, "message", 0));
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                long eventNanos = System.nanoTime();
                if (eventType.equals("response.output_audio.delta") && responseActive.get()) {
                    if (firstAudioDeltaNanos == 0L) {
                        firstAudioDeltaNanos = eventNanos;
                        assistantAudioStartedNanos.set(eventNanos);
                        logFirstAudioLatency();
                    }
                    queueOutputAudio(payload);
                } else if (eventType.equals("input_audio_buffer.speech_started")) {
                    speechStartedNanos = eventNanos;
                    sharedUserSpeechStartedNanos.set(eventNanos);
                    bargeInCancelledForSpeech.set(false);
                    handleUserSpeechStarted(webSocket);
                } else if (eventType.equals("input_audio_buffer.speech_stopped")) {
                    speechStoppedNanos = eventNanos;
                    sharedUserSpeechStartedNanos.set(0L);
                    bargeInCancelledForSpeech.set(false);
                    GatewayEventLogger.info(LOG, "openai_user_speech_stopped",
                            "sessionId", callSessionId,
                            "speechDurationMs", elapsedMillis(speechStartedNanos, speechStoppedNanos));
                } else if (eventType.equals("input_audio_buffer.committed")) {
                    inputCommittedNanos = eventNanos;
                    GatewayEventLogger.info(LOG, "openai_input_audio_committed",
                            "sessionId", callSessionId,
                            "commitAfterSpeechStoppedMs", elapsedMillis(speechStoppedNanos, inputCommittedNanos));
                } else if (eventType.equals("conversation.item.input_audio_transcription.completed")) {
                    logTranscript(
                            "caller",
                            extractStringField(payload, "item_id", 0),
                            "",
                            extractStringField(payload, "transcript", 0));
                } else if (eventType.equals("conversation.item.input_audio_transcription.failed")) {
                    logTranscriptFailure(
                            "caller",
                            extractStringField(payload, "item_id", 0),
                            extractStringField(payload, "message", payload.indexOf("\"error\"")));
                } else if (eventType.equals("response.created")) {
                    resetCurrentResponseStats();
                    responseCreatedNanos = eventNanos;
                    responseActive.set(true);
                    assistantAudioStartedNanos.set(0L);
                    bargeInCancelledForSpeech.set(false);
                    audioBridge.markOutboundActive(callSessionId);
                    GatewayEventLogger.info(LOG, "openai_response_created",
                            "sessionId", callSessionId);
                } else if (eventType.equals("response.output_audio.done")) {
                    outputAudioDoneNanos = eventNanos;
                    audioBridge.markOutboundComplete(callSessionId);
                    LOG.log(System.Logger.Level.INFO,
                            "OpenAI output audio completed: sessionId={0}, responseChunks={1}, responsePcm24Bytes={2}, responseQueuedFrames={3}, responseDroppedFrames={4}, responseAudioMs={5}, firstAudioLatencyMs={6}, outputDurationMs={7}, pendingBytes={8}, outboundDepth={9}",
                            callSessionId, currentResponseChunks, currentResponsePcm24Bytes,
                            currentResponseQueuedFrames,
                            currentResponseDroppedFrames,
                            currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            elapsedMillis(inputCommittedNanos, firstAudioDeltaNanos),
                            elapsedMillis(firstAudioDeltaNanos, outputAudioDoneNanos),
                            pendingOutputPcm.size(), audioBridge.outboundDepth(callSessionId));
                    GatewayEventLogger.info(LOG, "openai_output_audio_done",
                            "sessionId", callSessionId,
                            "responseChunks", currentResponseChunks,
                            "responseQueuedFrames", currentResponseQueuedFrames,
                            "responseDroppedFrames", currentResponseDroppedFrames,
                            "responseAudioMs", currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            "firstAudioLatencyMs", elapsedMillis(inputCommittedNanos, firstAudioDeltaNanos),
                            "responseCreatedToFirstAudioMs", elapsedMillis(responseCreatedNanos, firstAudioDeltaNanos),
                            "outputDurationMs", elapsedMillis(firstAudioDeltaNanos, outputAudioDoneNanos),
                            "outboundDepth", audioBridge.outboundDepth(callSessionId));
                } else if (eventType.equals("response.output_audio_transcript.done")) {
                    logTranscript(
                            "assistant",
                            extractStringField(payload, "item_id", 0),
                            extractStringField(payload, "response_id", 0),
                            extractStringField(payload, "transcript", 0));
                } else if (eventType.equals("response.done")) {
                    responseActive.set(false);
                    assistantAudioStartedNanos.set(0L);
                    bargeInCancelledForSpeech.set(false);
                    audioBridge.markOutboundComplete(callSessionId);
                    LOG.log(System.Logger.Level.INFO,
                            "OpenAI response done details: sessionId={0}, status={1}, statusDetails={2}, responseQueuedFrames={3}, responseDroppedFrames={4}, responseAudioMs={5}, firstAudioLatencyMs={6}, responseTotalLatencyMs={7}, outboundDepth={8}",
                            callSessionId,
                            extractStringField(payload, "status", 0),
                            extractJsonFieldSnippet(payload, "status_details"),
                            currentResponseQueuedFrames,
                            currentResponseDroppedFrames,
                            currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            elapsedMillis(inputCommittedNanos, firstAudioDeltaNanos),
                            elapsedMillis(inputCommittedNanos, eventNanos),
                            audioBridge.outboundDepth(callSessionId));
                    GatewayEventLogger.info(LOG, "openai_response_done",
                            "sessionId", callSessionId,
                            "status", extractStringField(payload, "status", 0),
                            "responseQueuedFrames", currentResponseQueuedFrames,
                            "responseDroppedFrames", currentResponseDroppedFrames,
                            "responseAudioMs", currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            "firstAudioLatencyMs", elapsedMillis(inputCommittedNanos, firstAudioDeltaNanos),
                            "responseCreatedToFirstAudioMs", elapsedMillis(responseCreatedNanos, firstAudioDeltaNanos),
                            "responseTotalLatencyMs", elapsedMillis(inputCommittedNanos, eventNanos),
                            "outboundDepth", audioBridge.outboundDepth(callSessionId));
                }
                if (shouldLog(eventType)) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "Received OpenAI Realtime event: sessionId={0}, type={1}",
                            callSessionId, eventType);
                }
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.log(System.Logger.Level.INFO,
                    "OpenAI Realtime WebSocket closed: sessionId={0}, status={1}, reason={2}",
                    callSessionId, statusCode, reason);
            GatewayEventLogger.info(LOG, "openai_websocket_closed",
                    "sessionId", callSessionId,
                    "status", statusCode,
                    "reason", reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.log(System.Logger.Level.WARNING,
                    "OpenAI Realtime WebSocket error: sessionId={0}, error={1}",
                    callSessionId, error.getMessage());
            GatewayEventLogger.warning(LOG, "openai_websocket_error",
                    "sessionId", callSessionId,
                    "error", error.getMessage());
        }

        private static boolean shouldLog(String eventType) {
            return eventType.equals("session.created")
                    || eventType.equals("session.updated")
                    || eventType.equals("input_audio_buffer.speech_started")
                    || eventType.equals("input_audio_buffer.speech_stopped")
                    || eventType.equals("input_audio_buffer.committed")
                    || eventType.startsWith("conversation.item.input_audio_transcription")
                    || eventType.equals("response.created")
                    || eventType.equals("response.done")
                    || eventType.equals("error")
                    || eventType.startsWith("response.output_audio");
        }

        private static String extractEventType(String payload) {
            String marker = "\"type\"";
            int markerIndex = payload.indexOf(marker);
            if (markerIndex < 0) {
                return "unknown";
            }
            int colonIndex = payload.indexOf(':', markerIndex + marker.length());
            int quoteStart = payload.indexOf('"', colonIndex + 1);
            int quoteEnd = payload.indexOf('"', quoteStart + 1);
            if (colonIndex < 0 || quoteStart < 0 || quoteEnd < 0) {
                return "unknown";
            }
            return payload.substring(quoteStart + 1, quoteEnd);
        }

        private void queueOutputAudio(String payload) {
            String delta = extractStringField(payload, "delta", 0);
            if (delta.equals("unknown") || delta.isBlank()) {
                return;
            }

            byte[] pcm24 = Base64.getDecoder().decode(delta);
            int outputSampleRateHz = audioBridge.sessionSampleRate(callSessionId, DEFAULT_RTP_AUDIO_SAMPLE_RATE_HZ);
            int frameBytes = frameBytes(outputSampleRateHz);
            byte[] pcmOutput = Pcm16Resampler.downsample(pcm24, OPENAI_AUDIO_SAMPLE_RATE_HZ, outputSampleRateHz);
            receivedOutputChunks.incrementAndGet();
            currentResponseChunks++;
            currentResponsePcm24Bytes += pcm24.length;
            synchronized (pendingOutputPcm) {
                pendingOutputPcm.writeBytes(pcmOutput);
                byte[] buffered = pendingOutputPcm.toByteArray();
                int offset = 0;
                while (buffered.length - offset >= frameBytes) {
                    byte[] frame = new byte[frameBytes];
                    System.arraycopy(buffered, offset, frame, 0, frameBytes);
                    offset += frameBytes;
                    if (audioBridge.enqueueOutboundPcm16(
                            callSessionId, frame, outputSampleRateHz, RTP_FRAME_DURATION_MS)) {
                        long count = queuedOutputFrames.incrementAndGet();
                        currentResponseQueuedFrames++;
                        if (count == 1 || count % 250 == 0) {
                            LOG.log(System.Logger.Level.DEBUG,
                                    "Queued OpenAI output audio frame for RTP: sessionId={0}, frames={1}, outboundDepth={2}",
                                    callSessionId, count, audioBridge.outboundDepth(callSessionId));
                        }
                    } else {
                        currentResponseDroppedFrames++;
                        if (currentResponseDroppedFrames == 1 || currentResponseDroppedFrames % 50 == 0) {
                            LOG.log(System.Logger.Level.WARNING,
                                    "Dropped OpenAI output audio frame because outbound queue is full: sessionId={0}, responseDroppedFrames={1}, outboundDepth={2}",
                                    callSessionId, currentResponseDroppedFrames, audioBridge.outboundDepth(callSessionId));
                            GatewayEventLogger.warning(LOG, "openai_output_audio_frame_dropped",
                                    "sessionId", callSessionId,
                                    "responseDroppedFrames", currentResponseDroppedFrames,
                                    "outboundDepth", audioBridge.outboundDepth(callSessionId));
                        }
                    }
                }
                pendingOutputPcm.reset();
                if (offset < buffered.length) {
                    pendingOutputPcm.write(buffered, offset, buffered.length - offset);
                }
            }
        }

        private void resetCurrentResponseStats() {
            currentResponseChunks = 0;
            currentResponsePcm24Bytes = 0;
            currentResponseQueuedFrames = 0;
            currentResponseDroppedFrames = 0;
            responseCreatedNanos = 0L;
            firstAudioDeltaNanos = 0L;
            outputAudioDoneNanos = 0L;
            synchronized (pendingOutputPcm) {
                pendingOutputPcm.reset();
            }
        }

        private static int frameBytes(int sampleRateHz) {
            return sampleRateHz * RTP_FRAME_DURATION_MS / 1000 * 2;
        }

        private void logFirstAudioLatency() {
            long commitToAudioMs = elapsedMillis(inputCommittedNanos, firstAudioDeltaNanos);
            long responseToAudioMs = elapsedMillis(responseCreatedNanos, firstAudioDeltaNanos);
            LOG.log(System.Logger.Level.INFO,
                    "OpenAI first audio delta latency: sessionId={0}, commitToFirstAudioMs={1}, responseCreatedToFirstAudioMs={2}",
                    callSessionId, commitToAudioMs, responseToAudioMs);
            GatewayEventLogger.info(LOG, "openai_response_latency",
                    "sessionId", callSessionId,
                    "commitToFirstAudioMs", commitToAudioMs,
                    "responseCreatedToFirstAudioMs", responseToAudioMs);
        }

        private void handleUserSpeechStarted(WebSocket webSocket) {
            boolean responseWasActive = responseActive.get();
            GatewayEventLogger.info(LOG, "openai_user_speech_started",
                    "sessionId", callSessionId,
                    "cancelResponseOnUserSpeech", runtimeConfig.cancelResponseOnUserSpeech(),
                    "dropInputAudioWhileAssistantSpeaking", runtimeConfig.dropInputAudioWhileAssistantSpeaking(),
                    "responseWasActive", responseWasActive,
                    "bargeInCandidate", runtimeConfig.cancelResponseOnUserSpeech() && responseWasActive,
                    "bargeInMinSpeechMs", runtimeConfig.bargeInMinSpeechMs(),
                    "bargeInMinRmsDb", runtimeConfig.bargeInMinRmsDb());
        }

        private void logTranscript(String speaker, String itemId, String responseId, String transcript) {
            String text = transcript == null ? "" : transcript;
            LOG.log(System.Logger.Level.INFO,
                    "CALL_TRANSCRIPT sessionId={0} speaker={1} itemId={2} responseId={3} text=\"{4}\"",
                    callSessionId, speaker, itemId, responseId, escapeLogText(text));
            conversationEventPublisher.publishTranscript(callSessionId, speaker, itemId, responseId, text);
            GatewayEventLogger.info(LOG, "call_transcript_logged",
                    "sessionId", callSessionId,
                    "speaker", speaker,
                    "itemId", itemId,
                    "responseId", responseId,
                    "textLength", text.length());
        }

        private void logTranscriptFailure(String speaker, String itemId, String message) {
            LOG.log(System.Logger.Level.WARNING,
                    "CALL_TRANSCRIPT_FAILED sessionId={0} speaker={1} itemId={2} message=\"{3}\"",
                    callSessionId, speaker, itemId, escapeLogText(message));
            GatewayEventLogger.warning(LOG, "call_transcript_failed",
                    "sessionId", callSessionId,
                    "speaker", speaker,
                    "itemId", itemId,
                    "message", message);
        }

        private static String extractStringField(String payload, String fieldName, int fromIndex) {
            int startIndex = Math.max(0, fromIndex);
            String marker = "\"" + fieldName + "\"";
            int markerIndex = payload.indexOf(marker, startIndex);
            if (markerIndex < 0) {
                return "unknown";
            }
            int colonIndex = payload.indexOf(':', markerIndex + marker.length());
            int quoteStart = payload.indexOf('"', colonIndex + 1);
            int quoteEnd = quoteStart < 0 ? -1 : findStringEnd(payload, quoteStart + 1);
            if (colonIndex < 0 || quoteStart < 0 || quoteEnd < 0) {
                return "unknown";
            }
            return unescapeJsonString(payload.substring(quoteStart + 1, quoteEnd));
        }

        private static long elapsedMillis(long startNanos, long endNanos) {
            if (startNanos <= 0L || endNanos <= 0L || endNanos < startNanos) {
                return -1L;
            }
            return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
        }

        private static String escapeLogText(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        private static String extractJsonFieldSnippet(String payload, String fieldName) {
            String marker = "\"" + fieldName + "\"";
            int markerIndex = payload.indexOf(marker);
            if (markerIndex < 0) {
                return "unknown";
            }
            int colonIndex = payload.indexOf(':', markerIndex + marker.length());
            if (colonIndex < 0) {
                return "unknown";
            }
            int valueStart = skipWhitespace(payload, colonIndex + 1);
            if (valueStart >= payload.length()) {
                return "unknown";
            }
            char first = payload.charAt(valueStart);
            int valueEnd;
            if (first == '"') {
                valueEnd = findStringEnd(payload, valueStart + 1);
                if (valueEnd < 0) {
                    return "unknown";
                }
                return limitSnippet(unescapeJsonString(payload.substring(valueStart + 1, valueEnd)));
            }
            if (first == '{' || first == '[') {
                valueEnd = findJsonContainerEnd(payload, valueStart);
                if (valueEnd < 0) {
                    return "unknown";
                }
                return limitSnippet(payload.substring(valueStart, valueEnd + 1));
            }
            valueEnd = valueStart;
            while (valueEnd < payload.length()) {
                char c = payload.charAt(valueEnd);
                if (c == ',' || c == '}') {
                    break;
                }
                valueEnd++;
            }
            return limitSnippet(payload.substring(valueStart, valueEnd).trim());
        }

        private static int skipWhitespace(String value, int fromIndex) {
            int index = fromIndex;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            return index;
        }

        private static int findJsonContainerEnd(String payload, int fromIndex) {
            char open = payload.charAt(fromIndex);
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = fromIndex; i < payload.length(); i++) {
                char c = payload.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static String limitSnippet(String value) {
            String compact = value.replace('\n', ' ').replace('\r', ' ');
            int maxLength = 240;
            return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "...";
        }

        private static int findStringEnd(String payload, int fromIndex) {
            boolean escaped = false;
            for (int i = fromIndex; i < payload.length(); i++) {
                char c = payload.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
            }
            return -1;
        }

        private static String unescapeJsonString(String value) {
            StringBuilder builder = new StringBuilder(value.length());
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!escaped) {
                    if (c == '\\') {
                        escaped = true;
                    } else {
                        builder.append(c);
                    }
                    continue;
                }

                switch (c) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    default -> builder.append(c);
                }
                escaped = false;
            }
            if (escaped) {
                builder.append('\\');
            }
            return builder.toString();
        }
    }
}
