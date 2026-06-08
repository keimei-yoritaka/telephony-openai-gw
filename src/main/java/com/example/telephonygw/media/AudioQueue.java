package com.example.telephonygw.media;

import com.example.telephonygw.logging.GatewayEventLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioQueue {
    private static final System.Logger LOG = System.getLogger(AudioQueue.class.getName());

    private final String name;
    private final BlockingQueue<AudioFrame> frames;
    private final AtomicLong offeredFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    public AudioQueue(String name, int capacity) {
        this.name = name;
        this.frames = new ArrayBlockingQueue<>(capacity);
    }

    public boolean offer(AudioFrame frame) {
        long offered = offeredFrames.incrementAndGet();
        boolean accepted = frames.offer(frame);
        if (!accepted) {
            long dropped = droppedFrames.incrementAndGet();
            if (dropped == 1 || dropped % 50 == 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "Audio queue dropped frame: queue={0}, offered={1}, dropped={2}, depth={3}",
                        name, offered, dropped, frames.size());
                GatewayEventLogger.warning(LOG, "audio_queue_frame_dropped",
                        "queue", name,
                        "offered", offered,
                        "dropped", dropped,
                        "depth", frames.size());
            }
            return false;
        }

        if (offered == 1 || offered % 250 == 0) {
            LOG.log(System.Logger.Level.INFO,
                    "Audio queue accepted frame: queue={0}, offered={1}, dropped={2}, depth={3}",
                    name, offered, droppedFrames.get(), frames.size());
        }
        return true;
    }

    public AudioFrame poll() {
        return frames.poll();
    }

    public AudioFrame poll(long timeoutMillis) throws InterruptedException {
        return frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public int depth() {
        return frames.size();
    }

    public long offeredFrames() {
        return offeredFrames.get();
    }

    public long droppedFrames() {
        return droppedFrames.get();
    }

    public void clear() {
        frames.clear();
    }
}
