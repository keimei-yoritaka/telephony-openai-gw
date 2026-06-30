package com.example.telephonygw.media;

import com.example.telephonygw.config.GatewayConfig.MediaConfig;
import com.example.telephonygw.logging.GatewayEventLogger;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioBridge {
    private static final System.Logger LOG = System.getLogger(AudioBridge.class.getName());

    private final int inboundQueueCapacity;
    private final int outboundQueueCapacity;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Map<String, AudioQueue> inboundQueues = new ConcurrentHashMap<>();
    private final Map<String, AudioQueue> outboundQueues = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionSampleRates = new ConcurrentHashMap<>();
    private final Set<String> outboundCompleteSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong inboundSequence = new AtomicLong();
    private final AtomicLong outboundSequence = new AtomicLong();

    public AudioBridge(MediaConfig config) {
        this.inboundQueueCapacity = config.inboundQueueCapacity();
        this.outboundQueueCapacity = config.outboundQueueCapacity();
    }

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO,
                    "Initialized audio bridge with inboundQueueCapacity={0}, outboundQueueCapacity={1}",
                    inboundQueueCapacity, outboundQueueCapacity);
            GatewayEventLogger.info(LOG, "audio_bridge_initialized",
                    "inboundQueueCapacity", inboundQueueCapacity,
                    "outboundQueueCapacity", outboundQueueCapacity);
        }
    }

    public boolean enqueueInboundPcm16(String sessionId, byte[] payload, int sampleRateHz, int durationMs) {
        AudioFrame frame = new AudioFrame(
                sessionId,
                AudioFrame.Direction.INBOUND,
                inboundSequence.incrementAndGet(),
                payload,
                "pcm16",
                sampleRateHz,
                durationMs,
                Instant.now());
        return inboundQueue(sessionId).offer(frame);
    }

    public boolean enqueueOutboundPcm16(String sessionId, byte[] payload, int sampleRateHz, int durationMs) {
        AudioFrame frame = new AudioFrame(
                sessionId,
                AudioFrame.Direction.OUTBOUND,
                outboundSequence.incrementAndGet(),
                payload,
                "pcm16",
                sampleRateHz,
                durationMs,
                Instant.now());
        return outboundQueue(sessionId).offer(frame);
    }

    public void setSessionSampleRate(String sessionId, int sampleRateHz) {
        sessionSampleRates.put(sessionId, sampleRateHz);
        LOG.log(System.Logger.Level.INFO,
                "Configured audio bridge session sample rate: sessionId={0}, sampleRateHz={1}",
                sessionId, sampleRateHz);
        GatewayEventLogger.info(LOG, "audio_bridge_session_sample_rate",
                "sessionId", sessionId,
                "sampleRateHz", sampleRateHz);
    }

    public int sessionSampleRate(String sessionId, int defaultSampleRateHz) {
        return sessionSampleRates.getOrDefault(sessionId, defaultSampleRateHz);
    }

    public AudioFrame pollOutbound(String sessionId) {
        AudioQueue queue = outboundQueues.get(sessionId);
        return queue == null ? null : queue.poll();
    }

    public AudioFrame pollInbound(String sessionId) {
        AudioQueue queue = inboundQueues.get(sessionId);
        return queue == null ? null : queue.poll();
    }

    public int inboundDepth(String sessionId) {
        AudioQueue queue = inboundQueues.get(sessionId);
        return queue == null ? 0 : queue.depth();
    }

    public int outboundDepth(String sessionId) {
        AudioQueue queue = outboundQueues.get(sessionId);
        return queue == null ? 0 : queue.depth();
    }

    public int clearOutbound(String sessionId) {
        clearOutboundComplete(sessionId);
        AudioQueue queue = outboundQueues.get(sessionId);
        if (queue == null) {
            return 0;
        }
        int depth = queue.depth();
        queue.clear();
        return depth;
    }

    public int clearInbound(String sessionId) {
        AudioQueue queue = inboundQueues.get(sessionId);
        if (queue == null) {
            return 0;
        }
        int depth = queue.depth();
        queue.clear();
        return depth;
    }

    public void removeSessionQueues(String sessionId) {
        clearOutboundComplete(sessionId);
        inboundQueues.remove(sessionId);
        outboundQueues.remove(sessionId);
    }

    public void clearSessionFormat(String sessionId) {
        sessionSampleRates.remove(sessionId);
    }

    public void markOutboundActive(String sessionId) {
        outboundCompleteSessions.remove(sessionId);
    }

    public void markOutboundComplete(String sessionId) {
        outboundCompleteSessions.add(sessionId);
    }

    public boolean isOutboundComplete(String sessionId) {
        return outboundCompleteSessions.contains(sessionId);
    }

    public void clearOutboundComplete(String sessionId) {
        outboundCompleteSessions.remove(sessionId);
    }

    public void stop() {
        if (initialized.compareAndSet(true, false)) {
            long outboundOffered = 0;
            long outboundDropped = 0;
            int outboundDepth = 0;
            long inboundOffered = 0;
            long inboundDropped = 0;
            int inboundDepth = 0;
            for (AudioQueue queue : inboundQueues.values()) {
                inboundOffered += queue.offeredFrames();
                inboundDropped += queue.droppedFrames();
                inboundDepth += queue.depth();
                queue.clear();
            }
            for (AudioQueue queue : outboundQueues.values()) {
                outboundOffered += queue.offeredFrames();
                outboundDropped += queue.droppedFrames();
                outboundDepth += queue.depth();
                queue.clear();
            }
            sessionSampleRates.clear();
            LOG.log(System.Logger.Level.INFO,
                    "Stopped audio bridge: inboundOffered={0}, inboundDropped={1}, inboundDepth={2}, outboundOffered={3}, outboundDropped={4}, outboundDepth={5}",
                    inboundOffered, inboundDropped, inboundDepth,
                    outboundOffered, outboundDropped, outboundDepth);
            GatewayEventLogger.info(LOG, "audio_bridge_stopped",
                    "inboundOffered", inboundOffered,
                    "inboundDropped", inboundDropped,
                    "inboundDepth", inboundDepth,
                    "outboundOffered", outboundOffered,
                    "outboundDropped", outboundDropped,
                    "outboundDepth", outboundDepth);
            inboundQueues.clear();
            outboundQueues.clear();
            outboundCompleteSessions.clear();
        }
    }

    private AudioQueue inboundQueue(String sessionId) {
        return inboundQueues.computeIfAbsent(
                sessionId,
                id -> createQueue("inbound", id, inboundQueueCapacity));
    }

    private AudioQueue outboundQueue(String sessionId) {
        return outboundQueues.computeIfAbsent(
                sessionId,
                id -> createQueue("outbound", id, outboundQueueCapacity));
    }

    private AudioQueue createQueue(String direction, String sessionId, int capacity) {
        String queueName = direction + "-" + sessionId.substring(0, Math.min(8, sessionId.length()));
        LOG.log(System.Logger.Level.INFO,
                "Created audio queue: sessionId={0}, direction={1}, queue={2}, capacity={3}",
                sessionId, direction, queueName, capacity);
        GatewayEventLogger.info(LOG, "audio_queue_created",
                "sessionId", sessionId,
                "direction", direction,
                "queue", queueName,
                "capacity", capacity);
        return new AudioQueue(queueName, capacity);
    }
}
