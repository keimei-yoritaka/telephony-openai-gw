package com.example.telephonygw.openai;

import com.example.telephonygw.media.AudioBridge;
import java.util.Arrays;
import java.util.Base64;

/** Preserve PCM samples and resampling phase across arbitrary audio delta boundaries. */
final class LiveAudioDecoder {
    private static final int INPUT_FRAME_BYTES = 24000 * 20 / 1000 * 2;
    private final AudioBridge bridge;
    private final String sessionId;
    private byte[] pending = new byte[0];

    LiveAudioDecoder(AudioBridge bridge, String sessionId) {
        this.bridge = bridge;
        this.sessionId = sessionId;
    }

    synchronized void append(String base64) {
        byte[] delta = Base64.getDecoder().decode(base64);
        if (delta.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Audio delta too large");
        byte[] bytes = Arrays.copyOf(pending, pending.length + delta.length);
        System.arraycopy(delta, 0, bytes, pending.length, delta.length);
        int offset = 0;
        while (bytes.length - offset >= INPUT_FRAME_BYTES) {
            enqueue(Arrays.copyOfRange(bytes, offset, offset + INPUT_FRAME_BYTES));
            offset += INPUT_FRAME_BYTES;
        }
        pending = Arrays.copyOfRange(bytes, offset, bytes.length);
    }

    // Flush a partial frame after a short output gap; this is not a conversation-turn boundary.
    synchronized void flush() {
        int complete = pending.length - pending.length % 2;
        if (complete > 0) enqueue(Arrays.copyOf(pending, INPUT_FRAME_BYTES));
        pending = complete < pending.length ? new byte[] { pending[pending.length - 1] } : new byte[0];
    }

    synchronized void clear() { pending = new byte[0]; }

    private void enqueue(byte[] pcm24) {
        int rate = bridge.sessionSampleRate(sessionId, 8000);
        byte[] pcm = Pcm16Resampler.downsample(pcm24, 24000, rate);
        bridge.enqueueOutboundPcm16(sessionId, pcm, rate, 20);
    }
}
