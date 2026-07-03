package com.example.telephonygw.sip;

import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import org.pjsip.pjsua2.AudioMediaPort;
import org.pjsip.pjsua2.ByteVector;
import org.pjsip.pjsua2.MediaFormatAudio;
import org.pjsip.pjsua2.MediaFrame;
import org.pjsip.pjsua2.pjmedia_format_id;
import org.pjsip.pjsua2.pjmedia_frame_type;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

final class Pjsua2AudioBridgePort extends AudioMediaPort {
    private static final System.Logger LOG = System.getLogger(Pjsua2AudioBridgePort.class.getName());
    private static final long LOG_EVERY_FRAMES = 50;
    private static final int FRAME_DURATION_MS = 20;
    private static final int OUTBOUND_START_BUFFER_FRAMES = 8;

    private final String sessionId;
    private final int callId;
    private final AudioBridge audioBridge;
    private final int sampleRateHz;
    private final int frameBytes;
    private final AtomicLong inboundFrames = new AtomicLong();
    private final AtomicLong outboundFrames = new AtomicLong();
    private final AtomicLong outboundSilenceFrames = new AtomicLong();
    private volatile boolean outboundPlaying;
    private volatile long firstFrameNanos;
    private volatile long previousFrameNanos;

    Pjsua2AudioBridgePort(String sessionId, int callId, AudioBridge audioBridge, int sampleRateHz) throws Exception {
        super();
        this.sessionId = sessionId;
        this.callId = callId;
        this.audioBridge = audioBridge;
        this.sampleRateHz = sampleRateHz;
        this.frameBytes = frameBytes(sampleRateHz);
        createPort(portName(sessionId, callId), mediaFormat(sampleRateHz));
        audioBridge.setSessionSampleRate(sessionId, sampleRateHz);
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
        byte[] payload = copyPayload(frame.getBuf(), (int) frame.getSize());
        audioBridge.enqueueInboundPcm16(sessionId, payload, sampleRateHz, FRAME_DURATION_MS);

        if (count == 1 || count % LOG_EVERY_FRAMES == 0) {
            long deltaMillis = previous == 0L ? 0L : Duration.ofNanos(now - previous).toMillis();
            LOG.log(System.Logger.Level.DEBUG,
                    "Observed inbound audio frame: sessionId={0}, callId={1}, frames={2}, bytes={3}, type={4}, deltaMs={5}, queueDepth={6}",
                    sessionId, callId, count, frame.getSize(), frame.getType(), deltaMillis,
                    audioBridge.inboundDepth(sessionId));
        }
    }

    @Override
    public void onFrameRequested(MediaFrame frame) {
        int depthBeforePoll = audioBridge.outboundDepth(sessionId);
        boolean outboundComplete = audioBridge.isOutboundComplete(sessionId);
        if (!outboundPlaying && depthBeforePoll == 0) {
            provideFrame(frame, new byte[frameBytes], true);
            return;
        }
        if (!outboundPlaying && depthBeforePoll < OUTBOUND_START_BUFFER_FRAMES && !outboundComplete) {
            provideFrame(frame, new byte[frameBytes], true);
            return;
        }

        if (!outboundPlaying) {
            outboundPlaying = true;
            audioBridge.markOutboundPlayoutActive(sessionId);
            LOG.log(System.Logger.Level.INFO,
                    "Started outbound RTP audio playout: sessionId={0}, callId={1}, bufferedFrames={2}, outputComplete={3}",
                    sessionId, callId, depthBeforePoll, outboundComplete);
        }

        AudioFrame outbound = audioBridge.pollOutbound(sessionId);
        byte[] payload;
        if (outbound == null) {
            outboundPlaying = false;
            audioBridge.markOutboundPlayoutInactive(sessionId);
            if (outboundComplete) {
                audioBridge.clearOutboundComplete(sessionId);
            }
            payload = new byte[frameBytes];
            provideFrame(frame, payload, true);
        } else {
            payload = normalizeFrame(outbound.payload());
            provideFrame(frame, payload, false);
        }
    }

    private void provideFrame(MediaFrame frame, byte[] payload, boolean silence) {
        if (silence) {
            outboundSilenceFrames.incrementAndGet();
        }
        frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
        frame.setBuf(toByteVector(payload));
        frame.setSize(payload.length);

        long count = outboundFrames.incrementAndGet();
        if (count == 1 || count % LOG_EVERY_FRAMES == 0) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Provided outbound RTP audio frame: sessionId={0}, callId={1}, frames={2}, silenceFrames={3}, bytes={4}, outboundDepth={5}",
                    sessionId, callId, count, outboundSilenceFrames.get(), payload.length,
                    audioBridge.outboundDepth(sessionId));
        }
    }

    long inboundFrameCount() {
        return inboundFrames.get();
    }

    long outboundFrameCount() {
        return outboundFrames.get();
    }

    long outboundSilenceFrameCount() {
        return outboundSilenceFrames.get();
    }

    long elapsedMillis() {
        long first = firstFrameNanos;
        if (first == 0L) {
            return 0L;
        }
        return Duration.ofNanos(System.nanoTime() - first).toMillis();
    }

    void closeBridge() {
        outboundPlaying = false;
        audioBridge.markOutboundPlayoutInactive(sessionId);
    }

    private static MediaFormatAudio mediaFormat(int sampleRateHz) {
        MediaFormatAudio format = new MediaFormatAudio();
        long bitsPerSecond = (long) sampleRateHz * 16;
        format.init(pjmedia_format_id.PJMEDIA_FORMAT_PCM, sampleRateHz, 1,
                FRAME_DURATION_MS * 1000, 16, bitsPerSecond, bitsPerSecond);
        return format;
    }

    private static String portName(String sessionId, int callId) {
        return "gateway-audio-" + callId + "-" + sessionId.substring(0, Math.min(8, sessionId.length()));
    }

    private static byte[] copyPayload(ByteVector source, int frameSize) {
        int size = (int) Math.min((long) frameSize, source.size());
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (source.get(i) & 0xFF);
        }
        return payload;
    }

    private static ByteVector toByteVector(byte[] payload) {
        ByteVector vector = new ByteVector();
        vector.reserve(payload.length);
        for (byte value : payload) {
            vector.add((short) (value & 0xFF));
        }
        return vector;
    }

    private byte[] normalizeFrame(byte[] payload) {
        if (payload.length == frameBytes) {
            return payload;
        }
        byte[] normalized = new byte[frameBytes];
        System.arraycopy(payload, 0, normalized, 0, Math.min(payload.length, frameBytes));
        return normalized;
    }

    private static int frameBytes(int sampleRateHz) {
        return sampleRateHz * FRAME_DURATION_MS / 1000 * 2;
    }
}
