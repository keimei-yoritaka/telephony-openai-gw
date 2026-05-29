package com.example.telephonygw.media;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioBridge {
    private static final System.Logger LOG = System.getLogger(AudioBridge.class.getName());
    private static final int DEFAULT_QUEUE_CAPACITY = 250;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AudioQueue inboundQueue = new AudioQueue("inbound", DEFAULT_QUEUE_CAPACITY);
    private final AtomicLong inboundSequence = new AtomicLong();

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO,
                    "Initialized audio bridge with inbound queue capacity={0}",
                    DEFAULT_QUEUE_CAPACITY);
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

    public AudioQueue inboundQueue() {
        return inboundQueue;
    }

    public void stop() {
        if (initialized.compareAndSet(true, false)) {
            LOG.log(System.Logger.Level.INFO,
                    "Stopped audio bridge: inboundOffered={0}, inboundDropped={1}, inboundDepth={2}",
                    inboundQueue.offeredFrames(), inboundQueue.droppedFrames(), inboundQueue.depth());
            inboundQueue.clear();
        }
    }
}
