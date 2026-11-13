package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfigLoader;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventHub;
import com.example.telephonygw.soniox.SonioxSessionException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Verify retry policy at the shared manager boundary, without network access. */
final class SonioxManagerCheck {
    @SuppressWarnings("unchecked")
    static void run() throws Exception {
        var config = GatewayConfigLoader.load(Path.of("config/gateway.soniox.example.yaml"));
        var cooldown = VoiceSessionManager.class.getDeclaredField("retryNotBeforeNanos"); cooldown.setAccessible(true);
        var stopped = VoiceSessionManager.class.getDeclaredField("stoppedSonioxCalls"); stopped.setAccessible(true);
        var recover = VoiceSessionManager.class.getDeclaredMethod("recoverTranslation", String.class); recover.setAccessible(true);
        for (boolean permanent : new boolean[]{false, true}) {
            AtomicInteger attempts = new AtomicInteger();
            var bridge = new AudioBridge(config.media(), config.openAi());
            try (VoiceSessionManager manager = new VoiceSessionManager(config.sessions(), config.openAi(), bridge,
                    new ConversationEventHub(), (id, ai, bot) -> new VoiceSession() {
                        @Override public void open() { attempts.incrementAndGet(); throw new SonioxSessionException(!permanent); }
                        @Override public boolean appendInputAudio(AudioFrame frame) { return false; }
                        @Override public void startInitialGreeting(String greeting) { throw new AssertionError("failure must not greet"); }
                        @Override public boolean isIdle(long now, Duration timeout) { return false; }
                        @Override public void close() { }
                    })) {
                manager.initialize(); manager.startSession("sx", "session-4", "check");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                var delays = (Map<String, Long>) cooldown.get(manager);
                var stops = (java.util.Set<String>) stopped.get(manager);
                while (!(permanent ? stops.contains("sx") : delays.containsKey("sx")) && System.nanoTime() < deadline) Thread.sleep(5);
                if (permanent ? !stops.contains("sx") : !delays.containsKey("sx")) throw new AssertionError("retry policy not reached");
                if (!bridge.sessionBackend("sx").equals("soniox_translation")) throw new AssertionError("backend identity lost");
                recover.invoke(manager, "sx");
                if (attempts.get() != 1) throw new AssertionError("retry during cooldown or permanent stop");
                if (!permanent) {
                    long delay = TimeUnit.NANOSECONDS.toSeconds(delays.get("sx") - System.nanoTime());
                    if (delay < 14 || delay > 16) throw new AssertionError("initial backoff outside 15s plus jitter");
                    delays.put("sx", 0L); recover.invoke(manager, "sx");
                    if (attempts.get() != 2) throw new AssertionError("no retry during silence");
                    delay = TimeUnit.NANOSECONDS.toSeconds(delays.get("sx") - System.nanoTime());
                    if (delay < 29 || delay > 31) throw new AssertionError("exponential backoff missing");
                }
                manager.closeSession("sx", "session-4", "check");
                if (stops.contains("sx") || delays.containsKey("sx")) throw new AssertionError("hangup retained retry state");
            }
        }
        System.out.println("Soniox manager checks passed: permanent stop, silent recovery, jittered backoff, cleanup");
    }
}
