package com.example.telephonygw.openai;

import java.time.Instant;

public record RealtimeEvent(String type, String payload, Instant receivedAt) {
}

