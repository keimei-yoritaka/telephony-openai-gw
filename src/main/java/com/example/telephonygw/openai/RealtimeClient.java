package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;
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

    private final OpenAiConfig config;
    private final String systemInstructions;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean forwarding = new AtomicBoolean(false);
    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong forwardedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private Thread forwardingThread;

    public RealtimeClient(OpenAiConfig config, String systemInstructions) {
        this.config = config;
        this.systemInstructions = systemInstructions;
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
                systemInstructions);
        session.open();
        return session;
    }

    @Override
    public void close() {
        if (initialized.compareAndSet(true, false)) {
            stopForwarding();
            closeSessions();
            LOG.log(System.Logger.Level.INFO,
                    "Closed OpenAI Realtime client: forwardedFrames={0}, failedFrames={1}",
                    forwardedFrames.get(), failedFrames.get());
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

        RealtimeSession session = sessions.computeIfAbsent(frame.sessionId(), this::openSession);
        if (session.appendInputAudio(frame)) {
            long count = forwardedFrames.incrementAndGet();
            if (count == 1 || count % 250 == 0) {
                LOG.log(System.Logger.Level.INFO,
                        "Forwarded inbound audio frame to OpenAI Realtime: sessionId={0}, frames={1}",
                        frame.sessionId(), count);
            }
        } else {
            failedFrames.incrementAndGet();
            sessions.remove(frame.sessionId(), session);
            session.close();
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
    }
}
