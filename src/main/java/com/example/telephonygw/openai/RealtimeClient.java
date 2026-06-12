package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.media.AudioQueue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RealtimeClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RealtimeClient.class.getName());
    private static final long POLL_TIMEOUT_MILLIS = 100L;
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SESSION_RETRY_DELAY = Duration.ofSeconds(15);

    private final OpenAiConfig config;
    private final String systemInstructions;
    private final String initialGreeting;
    private final AudioBridge audioBridge;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean forwarding = new AtomicBoolean(false);
    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> retryNotBeforeNanos = new ConcurrentHashMap<>();
    private final AtomicLong forwardedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final AtomicLong skippedFrames = new AtomicLong();
    private Thread forwardingThread;

    public RealtimeClient(
            OpenAiConfig config,
            String systemInstructions,
            String initialGreeting,
            AudioBridge audioBridge
    ) {
        this.config = config;
        this.systemInstructions = systemInstructions;
        this.initialGreeting = initialGreeting;
        this.audioBridge = audioBridge;
    }

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO,
                    "Initialized OpenAI Realtime client for model {0}",
                    config.realtimeModel());
        }
    }

    public void startAudioForwarding(AudioQueue inboundQueue) {
        if (!initialized.get()) {
            throw new IllegalStateException("Realtime client is not initialized");
        }
        if (!forwarding.compareAndSet(false, true)) {
            return;
        }

        forwardingThread = new Thread(() -> runForwardingLoop(inboundQueue), "openai-audio-forwarder");
        forwardingThread.setDaemon(true);
        forwardingThread.start();
        LOG.log(System.Logger.Level.INFO, "Started OpenAI Realtime audio forwarding worker");
    }

    public RealtimeSession openSession(String callSessionId) {
        if (!initialized.get()) {
            throw new IllegalStateException("Realtime client is not initialized");
        }
        RealtimeSession session = new RealtimeSession(
                callSessionId,
                config.apiKey(),
                config.realtimeModel(),
                config.voice(),
                config.maxOutputTokens(),
                config.turnDetectionType(),
                config.turnDetectionEagerness(),
                config.transcriptLoggingEnabled(),
                config.inputTranscriptionModel(),
                config.inputTranscriptionLanguage(),
                systemInstructions,
                audioBridge);
        session.open();
        return session;
    }

    public void startSession(String callSessionId, String reason) {
        if (!initialized.get()) {
            return;
        }
        Thread starter = new Thread(
                () -> startSessionAsync(callSessionId, reason),
                "openai-session-starter-" + callSessionId.substring(0, Math.min(8, callSessionId.length())));
        starter.setDaemon(true);
        starter.start();
        GatewayEventLogger.info(LOG, "openai_session_start_scheduled",
                "sessionId", callSessionId,
                "reason", reason);
    }

    private void startSessionAsync(String callSessionId, String reason) {
        try {
            RealtimeSession session = sessionFor(callSessionId);
            session.startInitialGreeting(initialGreeting);
            LOG.log(System.Logger.Level.INFO,
                    "Started OpenAI Realtime session for call start: sessionId={0}, reason={1}",
                    callSessionId, reason);
            GatewayEventLogger.info(LOG, "openai_session_started",
                    "sessionId", callSessionId,
                    "reason", reason);
        } catch (RuntimeException e) {
            markSessionRetry(callSessionId, e);
            failedFrames.incrementAndGet();
        }
    }

    public void closeSession(String callSessionId, String reason) {
        RealtimeSession session = sessions.remove(callSessionId);
        retryNotBeforeNanos.remove(callSessionId);
        int clearedFrames = audioBridge.clearOutbound(callSessionId);
        if (session != null) {
            session.close();
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI Realtime session for call close: sessionId={0}, reason={1}, clearedOutboundFrames={2}",
                    callSessionId, reason, clearedFrames);
            GatewayEventLogger.info(LOG, "openai_session_closed_for_call",
                    "sessionId", callSessionId,
                    "reason", reason,
                    "clearedOutboundFrames", clearedFrames);
        } else if (clearedFrames > 0) {
            LOG.log(System.Logger.Level.INFO,
                    "Cleared outbound audio for closed call without active OpenAI session: sessionId={0}, reason={1}, clearedOutboundFrames={2}",
                    callSessionId, reason, clearedFrames);
            GatewayEventLogger.info(LOG, "openai_outbound_cleared_without_session",
                    "sessionId", callSessionId,
                    "reason", reason,
                    "clearedOutboundFrames", clearedFrames);
        }
    }

    @Override
    public void close() {
        if (initialized.compareAndSet(true, false)) {
            stopForwarding();
            closeSessions();
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI Realtime client: forwardedFrames={0}, failedFrames={1}, skippedFrames={2}",
                    forwardedFrames.get(), failedFrames.get(), skippedFrames.get());
        }
    }

    private void runForwardingLoop(AudioQueue inboundQueue) {
        while (forwarding.get()) {
            try {
                AudioFrame frame = inboundQueue.poll(POLL_TIMEOUT_MILLIS);
                if (frame == null) {
                    closeIdleSessions();
                    continue;
                }
                forwardFrame(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                failedFrames.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING,
                        "OpenAI audio forwarding loop failed: {0}",
                        e.getMessage());
            }
        }
    }

    private void forwardFrame(AudioFrame frame) {
        if (!"pcm16".equals(frame.codec())) {
            failedFrames.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING,
                    "Skipping unsupported audio frame codec for OpenAI forwarding: sessionId={0}, codec={1}",
                    frame.sessionId(), frame.codec());
            return;
        }

        if (isRetryCoolingDown(frame.sessionId())) {
            skippedFrames.incrementAndGet();
            return;
        }

        RealtimeSession session;
        try {
            session = sessionFor(frame.sessionId());
        } catch (RuntimeException e) {
            markSessionRetry(frame.sessionId(), e);
            failedFrames.incrementAndGet();
            return;
        }

        if (session.appendInputAudio(frame)) {
            long count = forwardedFrames.incrementAndGet();
            if (count == 1 || count % 250 == 0) {
                LOG.log(System.Logger.Level.INFO,
                        "Forwarded inbound audio frame to OpenAI Realtime: sessionId={0}, frames={1}",
                        frame.sessionId(), count);
                GatewayEventLogger.info(LOG, "openai_inbound_audio_forwarded",
                        "sessionId", frame.sessionId(),
                        "frames", count);
            }
        } else {
            failedFrames.incrementAndGet();
            sessions.remove(frame.sessionId(), session);
            markSessionRetry(frame.sessionId(), null);
            session.close();
        }
    }

    private RealtimeSession sessionFor(String sessionId) {
        RealtimeSession existing = sessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        RealtimeSession opened = openSession(sessionId);
        RealtimeSession raced = sessions.putIfAbsent(sessionId, opened);
        if (raced != null) {
            opened.close();
            return raced;
        }
        retryNotBeforeNanos.remove(sessionId);
        return opened;
    }

    private boolean isRetryCoolingDown(String sessionId) {
        Long retryAt = retryNotBeforeNanos.get(sessionId);
        if (retryAt == null) {
            return false;
        }
        long now = System.nanoTime();
        if (now >= retryAt) {
            retryNotBeforeNanos.remove(sessionId, retryAt);
            return false;
        }
        return true;
    }

    private void markSessionRetry(String sessionId, RuntimeException error) {
        retryNotBeforeNanos.put(sessionId, System.nanoTime() + SESSION_RETRY_DELAY.toNanos());
        if (error == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "OpenAI Realtime audio append failed. Delaying reconnect: sessionId={0}, retryDelaySeconds={1}",
                    sessionId, SESSION_RETRY_DELAY.toSeconds());
            GatewayEventLogger.warning(LOG, "openai_session_retry_scheduled",
                    "sessionId", sessionId,
                    "retryDelaySeconds", SESSION_RETRY_DELAY.toSeconds(),
                    "reason", "append_failed");
        } else {
            LOG.log(System.Logger.Level.WARNING,
                    "OpenAI Realtime session open failed. Delaying reconnect: sessionId={0}, retryDelaySeconds={1}, error={2}",
                    sessionId, SESSION_RETRY_DELAY.toSeconds(), error.getMessage());
            GatewayEventLogger.warning(LOG, "openai_session_retry_scheduled",
                    "sessionId", sessionId,
                    "retryDelaySeconds", SESSION_RETRY_DELAY.toSeconds(),
                    "reason", "open_failed",
                    "error", error.getMessage());
        }
    }

    private void stopForwarding() {
        forwarding.set(false);
        if (forwardingThread != null) {
            forwardingThread.interrupt();
            try {
                forwardingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                forwardingThread = null;
            }
        }
    }

    private void closeIdleSessions() {
        long now = System.nanoTime();
        for (Map.Entry<String, RealtimeSession> entry : sessions.entrySet()) {
            RealtimeSession session = entry.getValue();
            if (session.isIdle(now, SESSION_IDLE_TIMEOUT)) {
                if (sessions.remove(entry.getKey(), session)) {
                    session.close();
                }
            }
        }
    }

    private void closeSessions() {
        for (RealtimeSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        retryNotBeforeNanos.clear();
    }
}
