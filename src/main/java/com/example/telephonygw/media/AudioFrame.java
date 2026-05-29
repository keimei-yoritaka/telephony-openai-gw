package com.example.telephonygw.media;

import java.time.Instant;
import java.util.Arrays;

public record AudioFrame(
        String sessionId,
        Direction direction,
        long sequenceNumber,
        byte[] payload,
        String codec,
        int sampleRateHz,
        int durationMs,
        Instant capturedAt
) {
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public AudioFrame {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
