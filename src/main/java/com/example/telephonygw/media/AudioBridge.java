package com.example.telephonygw.media;

import com.example.telephonygw.logging.GatewayEventLogger;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioBridge {
    private static final System.Logger LOG = System.getLogger(AudioBridge.class.getName());
    private static final int DEFAULT_QUEUE_CAPACITY = 250;
    private static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 1000;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AudioQueue inboundQueue = new AudioQueue("inbound", DEFAULT_QUEUE_CAPACITY);
    private final Map<String, AudioQueue> outboundQueues = new ConcurrentHashMap<>();
    private final Set<String> outboundCompleteSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong inboundSequence = new AtomicLong();
    private final AtomicLong outboundSequence = new AtomicLong();

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO,
                    "Initialized audio bridge with inbound queue capacity={0}",
                    DEFAULT_QUEUE_CAPACITY);
            GatewayEventLogger.info(LOG, "audio_bridge_initialized",
                    "inboundQueueCapacity", DEFAULT_QUEUE_CAPACITY,
                    "outboundQueueCapacity", DEFAULT_OUTBOUND_QUEUE_CAPACITY);
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
        return inboundQueue.offer(frame);
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

    public AudioFrame pollOutbound(String sessionId) {
        AudioQueue queue = outboundQueues.get(sessionId);
        return queue == null ? null : queue.poll();
    }

    public AudioQueue inboundQueue() {
        return inboundQueue;
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
            for (AudioQueue queue : outboundQueues.values()) {
                outboundOffered += queue.offeredFrames();
                outboundDropped += queue.droppedFrames();
                outboundDepth += queue.depth();
                queue.clear();
            }
            LOG.log(System.Logger.Level.INFO,
                    "Stopped audio bridge: inboundOffered={0}, inboundDropped={1}, inboundDepth={2}, outboundOffered={3}, outboundDropped={4}, outboundDepth={5}",
                    inboundQueue.offeredFrames(), inboundQueue.droppedFrames(), inboundQueue.depth(),
                    outboundOffered, outboundDropped, outboundDepth);
            GatewayEventLogger.info(LOG, "audio_bridge_stopped",
                    "inboundOffered", inboundQueue.offeredFrames(),
                    "inboundDropped", inboundQueue.droppedFrames(),
                    "inboundDepth", inboundQueue.depth(),
                    "outboundOffered", outboundOffered,
                    "outboundDropped", outboundDropped,
                    "outboundDepth", outboundDepth);
            inboundQueue.clear();
            outboundQueues.clear();
            outboundCompleteSessions.clear();
        }
    }

    private AudioQueue outboundQueue(String sessionId) {
        return outboundQueues.computeIfAbsent(
                sessionId,
                id -> new AudioQueue("outbound-" + id.substring(0, Math.min(8, id.length())),
                        DEFAULT_OUTBOUND_QUEUE_CAPACITY));
    }
}
