package com.example.telephonygw.sip;

import org.pjsip.pjsua2.AudioMediaPort;
import org.pjsip.pjsua2.MediaFormatAudio;
import org.pjsip.pjsua2.MediaFrame;
import org.pjsip.pjsua2.pjmedia_format_id;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

final class Pjsua2AudioBridgePort extends AudioMediaPort {
    private static final System.Logger LOG = System.getLogger(Pjsua2AudioBridgePort.class.getName());
    private static final long LOG_EVERY_FRAMES = 50;

    private final String sessionId;
    private final int callId;
    private final AtomicLong inboundFrames = new AtomicLong();
    private volatile long firstFrameNanos;
    private volatile long previousFrameNanos;

    Pjsua2AudioBridgePort(String sessionId, int callId) throws Exception {
        super();
        this.sessionId = sessionId;
        this.callId = callId;
        createPort(portName(sessionId, callId), mediaFormat());
    }

    @Override
    public void onFrameReceived(MediaFrame frame) {
        long now = System.nanoTime();
        long count = inboundFrames.incrementAndGet();
        if (firstFrameNanos == 0L) {
            firstFrameNanos = now;
        }

        long previous = previousFrameNanos;
        previousFrameNanos = now;

        if (count == 1 || count % LOG_EVERY_FRAMES == 0) {
            long deltaMillis = previous == 0L ? 0L : Duration.ofNanos(now - previous).toMillis();
            LOG.log(System.Logger.Level.INFO,
                    "Observed inbound audio frame: sessionId={0}, callId={1}, frames={2}, bytes={3}, type={4}, deltaMs={5}",
                    sessionId, callId, count, frame.getSize(), frame.getType(), deltaMillis);
        }
    }

    long inboundFrameCount() {
        return inboundFrames.get();
    }

    long elapsedMillis() {
        long first = firstFrameNanos;
        if (first == 0L) {
            return 0L;
        }
        return Duration.ofNanos(System.nanoTime() - first).toMillis();
    }

    private static MediaFormatAudio mediaFormat() {
        MediaFormatAudio format = new MediaFormatAudio();
        format.init(pjmedia_format_id.PJMEDIA_FORMAT_PCM, 8000, 1, 20000, 16, 128000, 128000);
        return format;
    }

    private static String portName(String sessionId, int callId) {
        return "gateway-audio-" + callId + "-" + sessionId.substring(0, Math.min(8, sessionId.length()));
    }
}
