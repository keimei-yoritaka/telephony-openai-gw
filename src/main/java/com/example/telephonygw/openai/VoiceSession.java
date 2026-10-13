package com.example.telephonygw.openai;

import com.example.telephonygw.media.AudioFrame;
import java.time.Duration;

public interface VoiceSession extends AutoCloseable {
    void open();
    boolean appendInputAudio(AudioFrame frame);
    void startInitialGreeting(String greeting);
    boolean isIdle(long nowNanos, Duration idleTimeout);
    @Override void close();
}
