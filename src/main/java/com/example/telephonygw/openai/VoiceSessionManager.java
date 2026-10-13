package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;
import com.example.telephonygw.config.GatewayConfig.OpenAiRuntimeConfig;
import com.example.telephonygw.config.GatewayConfig.BotConfig;
import com.example.telephonygw.config.GatewayConfig.SessionSlotConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventPublisher;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class VoiceSessionManager implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(VoiceSessionManager.class.getName());
    private static final long FORWARDER_IDLE_SLEEP_MILLIS = 20L;
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SESSION_RETRY_DELAY = Duration.ofSeconds(15);

    private final Map<String, SessionRuntimeConfig> slotConfigs;
    private final OpenAiRuntimeConfig runtimeConfig;
    private final Map<String, SessionRuntimeConfig> sessionConfigs = new ConcurrentHashMap<>();
    private final AudioBridge audioBridge;
    private final ConversationEventPublisher conversationEventPublisher;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean forwarding = new AtomicBoolean(false);
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> retryNotBeforeNanos = new ConcurrentHashMap<>();
    private final Map<String, Thread> forwardingThreads = new ConcurrentHashMap<>();
    private final java.util.Set<String> greetedCalls = ConcurrentHashMap.newKeySet();
    private final AtomicLong forwardedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final AtomicLong skippedFrames = new AtomicLong();
    private HttpClient httpClient;
    private final SessionFactory sessionFactoryOverride;

    @FunctionalInterface
    interface SessionFactory {
        VoiceSession create(String sessionId, OpenAiConfig config, BotConfig bot);
    }

    public VoiceSessionManager(
            List<SessionSlotConfig> sessionSlots,
            OpenAiRuntimeConfig runtimeConfig,
            AudioBridge audioBridge,
            ConversationEventPublisher conversationEventPublisher
    ) {
        this(sessionSlots, runtimeConfig, audioBridge, conversationEventPublisher, null);
    }

    VoiceSessionManager(List<SessionSlotConfig> sessionSlots, OpenAiRuntimeConfig runtimeConfig,
            AudioBridge audioBridge, ConversationEventPublisher conversationEventPublisher, SessionFactory sessionFactoryOverride) {
        this.sessionFactoryOverride = sessionFactoryOverride;
        this.slotConfigs = slotConfigs(sessionSlots);
        this.runtimeConfig = runtimeConfig;
        this.audioBridge = audioBridge;
        this.conversationEventPublisher = conversationEventPublisher;
    }

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            httpClient = HttpClient.newHttpClient();
            LOG.log(System.Logger.Level.INFO,
                    "Initialized OpenAI voice session manager for {0} session slot(s)",
                    slotConfigs.size());
            if (sessionFactoryOverride == null) warmUpAsync();
        }
    }

    public void startAudioForwarding() {
        if (!initialized.get()) {
            throw new IllegalStateException("Voice session manager is not initialized");
        }
        if (!forwarding.compareAndSet(false, true)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Enabled OpenAI voice session audio forwarding");
        GatewayEventLogger.info(LOG, "openai_audio_forwarding_enabled");
    }

    public VoiceSession openSession(String callSessionId) {
        if (!initialized.get()) {
            throw new IllegalStateException("Voice session manager is not initialized");
        }
        SessionRuntimeConfig runtimeConfig = requireSessionConfig(callSessionId);
        OpenAiConfig config = runtimeConfig.openAiConfig();
        BotConfig botConfig = runtimeConfig.botConfig();
        if (sessionFactoryOverride != null) {
            VoiceSession session = sessionFactoryOverride.create(callSessionId, config, botConfig);
            session.open();
            return session;
        }
        if (config.isLive()) {
            LiveSession session = new LiveSession(callSessionId, config, botConfig, audioBridge,
                    conversationEventPublisher, httpClient,
                    () -> sessionConfigs.get(callSessionId) == runtimeConfig && initialized.get());
            session.open();
            return session;
        }
        RealtimeSession session = new RealtimeSession(
                callSessionId,
                config.apiKey(),
                config.realtimeModel(),
                config.voice(),
                config.maxOutputTokens(),
                config.turnDetectionType(),
                config.turnDetectionEagerness(),
                config.turnDetectionServerVadThreshold(),
                config.turnDetectionServerVadPrefixPaddingMs(),
                config.turnDetectionServerVadSilenceDurationMs(),
                config.transcriptLoggingEnabled(),
                config.inputTranscriptionModel(),
                config.inputTranscriptionLanguage(),
                botConfig.systemInstructions(),
                this.runtimeConfig,
                audioBridge,
                conversationEventPublisher,
                httpClient);
        session.open();
        return session;
    }

    private void warmUpAsync() {
        Thread warmup = new Thread(this::warmUp, "openai-realtime-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    private void warmUp() {
        SessionRuntimeConfig runtimeConfig = slotConfigs.values().stream().filter(slot -> !slot.openAiConfig().isLive()).findFirst().orElse(null);
        if (runtimeConfig == null) {
            return;
        }
        OpenAiConfig config = runtimeConfig.openAiConfig();
        if (config.apiKey().startsWith("${")) {
            LOG.log(System.Logger.Level.INFO,
                    "Skipped OpenAI Realtime warm-up because apiKey is unresolved placeholder");
            return;
        }
        BotConfig botConfig = runtimeConfig.botConfig();
        String warmupSessionId = "warmup-" + Long.toUnsignedString(System.nanoTime());
        try (RealtimeSession session = new RealtimeSession(
                warmupSessionId,
                config.apiKey(),
                config.realtimeModel(),
                config.voice(),
                config.maxOutputTokens(),
                config.turnDetectionType(),
                config.turnDetectionEagerness(),
                config.turnDetectionServerVadThreshold(),
                config.turnDetectionServerVadPrefixPaddingMs(),
                config.turnDetectionServerVadSilenceDurationMs(),
                false,
                config.inputTranscriptionModel(),
                config.inputTranscriptionLanguage(),
                botConfig.systemInstructions(),
                this.runtimeConfig,
                audioBridge,
                ConversationEventPublisher.NOOP,
                httpClient)) {
            session.open();
            LOG.log(System.Logger.Level.INFO,
                    "Completed OpenAI Realtime warm-up: sessionId={0}",
                    warmupSessionId);
            GatewayEventLogger.info(LOG, "openai_realtime_warmup_completed",
                    "sessionId", warmupSessionId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "OpenAI Realtime warm-up failed. First call may take longer: {0}",
                    e.getMessage());
            GatewayEventLogger.warning(LOG, "openai_realtime_warmup_failed",
                    "error", e.getMessage());
        }
    }

    public void startSession(String callSessionId, String slotId, String reason) {
        if (!initialized.get()) {
            return;
        }
        SessionRuntimeConfig runtimeConfig = requireSlotConfig(slotId);
        synchronized (sessionConfigs) { sessionConfigs.put(callSessionId, runtimeConfig); }
        audioBridge.configureSessionBackend(callSessionId, runtimeConfig.openAiConfig().isLive(),
                runtimeConfig.openAiConfig().liveStartBufferFrames());
        startForwardingWorker(callSessionId, slotId);
        Thread starter = new Thread(
                () -> startSessionAsync(callSessionId, reason),
                "openai-session-starter-" + callSessionId.substring(0, Math.min(8, callSessionId.length())));
        starter.setDaemon(true);
        starter.start();
        GatewayEventLogger.info(LOG, "openai_session_start_scheduled",
                "sessionId", callSessionId,
                "slotId", slotId,
                "reason", reason);
    }

    private void startSessionAsync(String callSessionId, String reason) {
        try {
            VoiceSession session = sessionFor(callSessionId);
            SessionRuntimeConfig runtimeConfig = requireSessionConfig(callSessionId);

            LOG.log(System.Logger.Level.INFO,
                    "Started OpenAI voice session for call start: sessionId={0}, slotId={1}, reason={2}",
                    callSessionId, runtimeConfig.slotId(), reason);
            GatewayEventLogger.info(LOG, "openai_session_started",
                    "sessionId", callSessionId,
                    "slotId", runtimeConfig.slotId(),
                    "reason", reason, "backend", runtimeConfig.openAiConfig().backend());
        } catch (RuntimeException e) {
            if (sessionConfigs.containsKey(callSessionId)) markSessionRetry(callSessionId, e);
            failedFrames.incrementAndGet();
        }
    }

    public void closeSession(String callSessionId, String slotId, String reason) {
        VoiceSession session;
        synchronized (sessionConfigs) {
            sessionConfigs.remove(callSessionId);
            session = sessions.remove(callSessionId);
            greetedCalls.remove(callSessionId);
        }
        sessionLocks.remove(callSessionId);

        retryNotBeforeNanos.remove(callSessionId);
        stopForwardingWorker(callSessionId);
        int clearedFrames = audioBridge.clearOutbound(callSessionId);
        int clearedInboundFrames = audioBridge.clearInbound(callSessionId);
        if (session != null) {
            closeAsync(session);
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI voice session for call close: sessionId={0}, reason={1}, clearedInboundFrames={2}, clearedOutboundFrames={3}",
                    callSessionId, reason, clearedInboundFrames, clearedFrames);
            GatewayEventLogger.info(LOG, "openai_session_closed_for_call",
                    "sessionId", callSessionId,
                    "slotId", slotId,
                    "reason", reason,
                    "clearedInboundFrames", clearedInboundFrames,
                    "clearedOutboundFrames", clearedFrames);
        } else if (clearedInboundFrames > 0 || clearedFrames > 0) {
            LOG.log(System.Logger.Level.INFO,
                    "Cleared audio for closed call without active OpenAI session: sessionId={0}, reason={1}, clearedInboundFrames={2}, clearedOutboundFrames={3}",
                    callSessionId, reason, clearedInboundFrames, clearedFrames);
            GatewayEventLogger.info(LOG, "openai_audio_cleared_without_session",
                    "sessionId", callSessionId,
                    "slotId", slotId,
                    "reason", reason,
                    "clearedInboundFrames", clearedInboundFrames,
                    "clearedOutboundFrames", clearedFrames);
        }
        audioBridge.clearSessionFormat(callSessionId);
        audioBridge.removeSessionQueues(callSessionId);
    }

    @Override
    public void close() {
        if (initialized.compareAndSet(true, false)) {
            stopForwarding();
            closeSessions();
            httpClient = null;
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI voice session manager: forwardedFrames={0}, failedFrames={1}, skippedFrames={2}",
                    forwardedFrames.get(), failedFrames.get(), skippedFrames.get());
        }
    }

    private void runForwardingLoop(String sessionId) {
        while (forwarding.get() && sessionConfigs.containsKey(sessionId)) {
            try {
                AudioFrame frame = audioBridge.pollInbound(sessionId);
                if (frame == null) {
                    closeIdleSession(sessionId);
                    Thread.sleep(FORWARDER_IDLE_SLEEP_MILLIS);
                    continue;
                }
                forwardFrame(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                failedFrames.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING,
                        "OpenAI audio forwarding loop failed: sessionId={0}, error={1}",
                        sessionId, e.getMessage());
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

        if (audioBridge.shouldDropInboundForAssistantSpeaking(frame.sessionId())) {
            long skipped = skippedFrames.incrementAndGet();
            if (skipped == 1 || skipped % 250 == 0) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Skipped OpenAI input audio forwarding while assistant RTP playout is active: sessionId={0}, skippedFrames={1}",
                        frame.sessionId(), skipped);
            }
            return;
        }

        VoiceSession session;
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
                LOG.log(System.Logger.Level.DEBUG,
                        "Forwarded inbound audio frame to OpenAI Realtime: sessionId={0}, frames={1}",
                        frame.sessionId(), count);
            }
        } else {
            failedFrames.incrementAndGet();
            sessions.remove(frame.sessionId(), session);
            audioBridge.clearOutbound(frame.sessionId());
            audioBridge.clearInbound(frame.sessionId());
            markSessionRetry(frame.sessionId(), null);
            closeAsync(session);
        }
    }

    private VoiceSession sessionFor(String sessionId) {
        VoiceSession existing = sessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        Object lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
        synchronized (lock) {
            existing = sessions.get(sessionId);
            if (existing != null) {
                return existing;
            }
            try {
                SessionRuntimeConfig expected = requireSessionConfig(sessionId);
                VoiceSession opened = openSession(sessionId);
                synchronized (sessionConfigs) {
                    if (!initialized.get() || sessionConfigs.get(sessionId) != expected) {
                        closeAsync(opened);
                        throw new IllegalStateException("Call ended during OpenAI session startup");
                    }
                    sessions.put(sessionId, opened);
                    if (greetedCalls.add(sessionId)) opened.startInitialGreeting(expected.botConfig().initialGreeting());
                }
                retryNotBeforeNanos.remove(sessionId);
                return opened;
            } finally {
                if (!sessionConfigs.containsKey(sessionId)) sessionLocks.remove(sessionId, lock);
            }
        }
    }

    private boolean isRetryCoolingDown(String sessionId) {
        if (!sessionConfigs.containsKey(sessionId)) return true;
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
        if (!sessionConfigs.containsKey(sessionId)) return;
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

    private void startForwardingWorker(String sessionId, String slotId) {
        if (!forwarding.get()) {
            return;
        }
        Thread existing = forwardingThreads.get(sessionId);
        if (existing != null && existing.isAlive()) {
            return;
        }
        Thread worker = new Thread(
                () -> runForwardingLoop(sessionId),
                "openai-audio-forwarder-" + sessionId.substring(0, Math.min(8, sessionId.length())));
        worker.setDaemon(true);
        forwardingThreads.put(sessionId, worker);
        worker.start();
        LOG.log(System.Logger.Level.INFO,
                "Started OpenAI audio forwarding worker: sessionId={0}, slotId={1}",
                sessionId, slotId);
        GatewayEventLogger.info(LOG, "openai_audio_forwarder_started",
                "sessionId", sessionId,
                "slotId", slotId);
    }

    private void stopForwardingWorker(String sessionId) {
        Thread worker = forwardingThreads.remove(sessionId);
        if (worker == null) {
            return;
        }
        worker.interrupt();
        LOG.log(System.Logger.Level.INFO,
                "Stopped OpenAI audio forwarding worker: sessionId={0}",
                sessionId);
        GatewayEventLogger.info(LOG, "openai_audio_forwarder_stopped",
                "sessionId", sessionId);
    }

    private void stopForwarding() {
        forwarding.set(false);
        for (String sessionId : List.copyOf(forwardingThreads.keySet())) {
            stopForwardingWorker(sessionId);
        }
    }

    private void closeIdleSession(String sessionId) {
        VoiceSession session = sessions.get(sessionId);
        if (session == null || !session.isIdle(System.nanoTime(), SESSION_IDLE_TIMEOUT)) {
            return;
        }
        if (sessions.remove(sessionId, session)) {
            session.close();
        }
    }

    private static void closeAsync(VoiceSession session) {
        Thread closer = new Thread(session::close, "openai-session-closer");
        closer.setDaemon(true);
        closer.start();
    }

    private void closeSessions() {
        for (VoiceSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        greetedCalls.clear();
        sessionConfigs.clear();
        sessionLocks.clear();
        retryNotBeforeNanos.clear();
        forwardingThreads.clear();
    }

    private SessionRuntimeConfig requireSlotConfig(String slotId) {
        SessionRuntimeConfig config = slotConfigs.get(slotId);
        if (config == null) {
            throw new IllegalStateException("No OpenAI runtime config registered for slotId=" + slotId);
        }
        return config;
    }

    private SessionRuntimeConfig requireSessionConfig(String sessionId) {
        SessionRuntimeConfig config = sessionConfigs.get(sessionId);
        if (config == null) {
            throw new IllegalStateException("No OpenAI runtime config registered for sessionId=" + sessionId);
        }
        return config;
    }

    private static Map<String, SessionRuntimeConfig> slotConfigs(List<SessionSlotConfig> sessionSlots) {
        Map<String, SessionRuntimeConfig> configs = new LinkedHashMap<>();
        for (SessionSlotConfig sessionSlot : sessionSlots) {
            configs.put(sessionSlot.slotId(),
                    new SessionRuntimeConfig(sessionSlot.slotId(), sessionSlot.openAi(), sessionSlot.bot()));
        }
        return Map.copyOf(configs);
    }

    private record SessionRuntimeConfig(String slotId, OpenAiConfig openAiConfig, BotConfig botConfig) {
    }
}
