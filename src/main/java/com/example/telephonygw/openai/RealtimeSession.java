package com.example.telephonygw.openai;

import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.media.AudioBridge;

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
    private static final int RTP_AUDIO_SAMPLE_RATE_HZ = 8000;
    private static final int RTP_FRAME_DURATION_MS = 20;
    private static final int RTP_FRAME_BYTES = 320;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SEND_TIMEOUT_SECONDS = 5;

    private final String callSessionId;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String maxOutputTokens;
    private final String turnDetectionType;
    private final String turnDetectionEagerness;
    private final String systemInstructions;
    private final AudioBridge audioBridge;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong receivedOutputChunks = new AtomicLong();
    private final AtomicLong queuedOutputFrames = new AtomicLong();
    private final AtomicBoolean initialGreetingStarted = new AtomicBoolean(false);
    private final ByteArrayOutputStream pendingOutputPcm8 = new ByteArrayOutputStream();
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
            String systemInstructions,
            AudioBridge audioBridge
    ) {
        this.callSessionId = Objects.requireNonNull(callSessionId, "callSessionId");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        this.turnDetectionType = Objects.requireNonNull(turnDetectionType, "turnDetectionType");
        this.turnDetectionEagerness = Objects.requireNonNull(turnDetectionEagerness, "turnDetectionEagerness");
        this.systemInstructions = Objects.requireNonNull(systemInstructions, "systemInstructions");
        this.audioBridge = Objects.requireNonNull(audioBridge, "audioBridge");
    }

    public void open() {
        if (!open.compareAndSet(false, true)) {
            return;
        }

        try {
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .buildAsync(sessionUri(), new SessionListener(callSessionId, audioBridge, pendingOutputPcm8,
                            receivedOutputChunks, queuedOutputFrames))
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sendText(sessionUpdateEvent());
            LOG.log(System.Logger.Level.INFO,
                    "Opened OpenAI Realtime session: sessionId={0}, model={1}, voice={2}, inputRateHz={3}, maxOutputTokens={4}, turnDetection={5}",
                    callSessionId, model, voice, OPENAI_AUDIO_SAMPLE_RATE_HZ, maxOutputTokens, turnDetectionType);
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
        }
    }

    private URI sessionUri() {
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
        return URI.create(REALTIME_ENDPOINT + "?model=" + encodedModel);
    }

    private void sendText(String payload) {
        CompletableFuture<WebSocket> future = webSocket.sendText(payload, true);
        future.orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private String sessionUpdateEvent() {
        return """
                {"type":"session.update","session":{"type":"realtime","model":"%s","instructions":"%s","max_output_tokens":%s,"output_modalities":["audio"],"audio":{"input":{"format":{"type":"audio/pcm","rate":%d},"turn_detection":%s},"output":{"format":{"type":"audio/pcm","rate":%d},"voice":"%s"}}}}\
                """.formatted(json(model), json(systemInstructions), maxOutputTokensJson(),
                OPENAI_AUDIO_SAMPLE_RATE_HZ, turnDetectionJson(), OPENAI_AUDIO_SAMPLE_RATE_HZ, json(voice));
    }

    private String maxOutputTokensJson() {
        return "inf".equalsIgnoreCase(maxOutputTokens) ? "\"inf\"" : maxOutputTokens;
    }

    private String turnDetectionJson() {
        if ("semantic_vad".equalsIgnoreCase(turnDetectionType)) {
            return """
                    {"type":"semantic_vad","eagerness":"%s","create_response":true,"interrupt_response":true}\
                    """.formatted(json(turnDetectionEagerness));
        }
        return """
                {"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,"silence_duration_ms":800,"create_response":true,"interrupt_response":true}\
                """;
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
        private final ByteArrayOutputStream pendingOutputPcm8;
        private final AtomicLong receivedOutputChunks;
        private final AtomicLong queuedOutputFrames;
        private final AtomicBoolean responseActive = new AtomicBoolean(false);
        private final StringBuilder message = new StringBuilder();
        private long currentResponseChunks;
        private long currentResponsePcm24Bytes;
        private long currentResponseQueuedFrames;

        private SessionListener(
                String callSessionId,
                AudioBridge audioBridge,
                ByteArrayOutputStream pendingOutputPcm8,
                AtomicLong receivedOutputChunks,
                AtomicLong queuedOutputFrames
        ) {
            this.callSessionId = callSessionId;
            this.audioBridge = audioBridge;
            this.pendingOutputPcm8 = pendingOutputPcm8;
            this.receivedOutputChunks = receivedOutputChunks;
            this.queuedOutputFrames = queuedOutputFrames;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            LOG.log(System.Logger.Level.INFO,
                    "OpenAI Realtime WebSocket connected: sessionId={0}",
                    callSessionId);
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
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                if (eventType.equals("response.output_audio.delta") && responseActive.get()) {
                    queueOutputAudio(payload);
                } else if (eventType.equals("input_audio_buffer.speech_started")) {
                    handleUserSpeechStarted(webSocket);
                } else if (eventType.equals("response.created")) {
                    responseActive.set(true);
                    audioBridge.markOutboundActive(callSessionId);
                    resetCurrentResponseStats();
                } else if (eventType.equals("response.output_audio.done")) {
                    audioBridge.markOutboundComplete(callSessionId);
                    LOG.log(System.Logger.Level.INFO,
                            "OpenAI output audio completed: sessionId={0}, responseChunks={1}, responsePcm24Bytes={2}, responseQueuedFrames={3}, responseAudioMs={4}, pendingBytes={5}, outboundDepth={6}",
                            callSessionId, currentResponseChunks, currentResponsePcm24Bytes,
                            currentResponseQueuedFrames,
                            currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            pendingOutputPcm8.size(), audioBridge.outboundDepth(callSessionId));
                } else if (eventType.equals("response.done")) {
                    responseActive.set(false);
                    audioBridge.markOutboundComplete(callSessionId);
                    LOG.log(System.Logger.Level.INFO,
                            "OpenAI response done details: sessionId={0}, status={1}, statusDetails={2}, responseQueuedFrames={3}, responseAudioMs={4}, outboundDepth={5}",
                            callSessionId,
                            extractStringField(payload, "status", 0),
                            extractJsonFieldSnippet(payload, "status_details"),
                            currentResponseQueuedFrames,
                            currentResponseQueuedFrames * RTP_FRAME_DURATION_MS,
                            audioBridge.outboundDepth(callSessionId));
                }
                if (shouldLog(eventType)) {
                    LOG.log(System.Logger.Level.INFO,
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
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.log(System.Logger.Level.WARNING,
                    "OpenAI Realtime WebSocket error: sessionId={0}, error={1}",
                    callSessionId, error.getMessage());
        }

        private static boolean shouldLog(String eventType) {
            return eventType.equals("session.created")
                    || eventType.equals("session.updated")
                    || eventType.equals("input_audio_buffer.speech_started")
                    || eventType.equals("input_audio_buffer.speech_stopped")
                    || eventType.equals("input_audio_buffer.committed")
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
            byte[] pcm8 = Pcm16Resampler.downsample(pcm24, OPENAI_AUDIO_SAMPLE_RATE_HZ, RTP_AUDIO_SAMPLE_RATE_HZ);
            receivedOutputChunks.incrementAndGet();
            currentResponseChunks++;
            currentResponsePcm24Bytes += pcm24.length;
            synchronized (pendingOutputPcm8) {
                pendingOutputPcm8.writeBytes(pcm8);
                byte[] buffered = pendingOutputPcm8.toByteArray();
                int offset = 0;
                while (buffered.length - offset >= RTP_FRAME_BYTES) {
                    byte[] frame = new byte[RTP_FRAME_BYTES];
                    System.arraycopy(buffered, offset, frame, 0, RTP_FRAME_BYTES);
                    offset += RTP_FRAME_BYTES;
                    if (audioBridge.enqueueOutboundPcm16(callSessionId, frame, RTP_AUDIO_SAMPLE_RATE_HZ, RTP_FRAME_DURATION_MS)) {
                        long count = queuedOutputFrames.incrementAndGet();
                        currentResponseQueuedFrames++;
                        if (count == 1 || count % 250 == 0) {
                            LOG.log(System.Logger.Level.INFO,
                                    "Queued OpenAI output audio frame for RTP: sessionId={0}, frames={1}, outboundDepth={2}",
                                    callSessionId, count, audioBridge.outboundDepth(callSessionId));
                        }
                    }
                }
                pendingOutputPcm8.reset();
                if (offset < buffered.length) {
                    pendingOutputPcm8.write(buffered, offset, buffered.length - offset);
                }
            }
        }

        private void resetCurrentResponseStats() {
            currentResponseChunks = 0;
            currentResponsePcm24Bytes = 0;
            currentResponseQueuedFrames = 0;
            synchronized (pendingOutputPcm8) {
                pendingOutputPcm8.reset();
            }
        }

        private void handleUserSpeechStarted(WebSocket webSocket) {
            boolean wasResponseActive = responseActive.compareAndSet(true, false);
            int clearedFrames = 0;
            if (wasResponseActive) {
                clearedFrames = audioBridge.clearOutbound(callSessionId);
                synchronized (pendingOutputPcm8) {
                    pendingOutputPcm8.reset();
                }
                webSocket.sendText("{\"type\":\"response.cancel\"}", true)
                        .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(error -> {
                            LOG.log(System.Logger.Level.DEBUG,
                                    "OpenAI response cancel failed or was not needed: sessionId={0}, error={1}",
                                    callSessionId, error.getMessage());
                            return null;
                        });
            }
            if (clearedFrames > 0) {
                LOG.log(System.Logger.Level.INFO,
                        "Cleared outbound audio because user speech started: sessionId={0}, clearedFrames={1}",
                        callSessionId, clearedFrames);
            }
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
