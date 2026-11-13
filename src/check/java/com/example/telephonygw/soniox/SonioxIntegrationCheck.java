package com.example.telephonygw.soniox;

import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.config.GatewayConfigLoader;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventHub;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Offline contract checks with independent fake STT/TTS transports. */
public final class SonioxIntegrationCheck {
    private static int checks;
    private static GatewayConfig config;

    public static void run() throws Exception {
        config = GatewayConfigLoader.load(Path.of("config/gateway.soniox.example.yaml"));
        configChecks();
        transcriptAndAudio();
        failuresAndLifecycle();
        boundedQueues();
        additionalContracts();
        englishToJapanese();
        System.out.println("Soniox integration checks passed: " + checks);
    }

    private static void configChecks() throws Exception {
        check(config.sessions().stream().map(GatewayConfig.SessionSlotConfig::backend).toList()
                .equals(List.of("realtime", "live", "translation", "soniox_translation")), "four independent backends");
        var soniox = config.sessions().get(3);
        check(soniox.isSoniox() && soniox.openAi().apiKey().isEmpty() && soniox.bot().systemInstructions().isEmpty(),
                "Soniox needs neither OpenAI key nor instructions");
        check(soniox.selectedModel().equals("stt-rt-v6") && soniox.selectedVoice().equals("Adrian"), "selected Soniox model and voice");
        String yaml = Files.readString(Path.of("config/gateway.soniox.example.yaml"));
        Path temp = Files.createTempFile(Path.of("build"), "soniox-config-", ".yaml");
        try {
            for (String[] change : List.of(
                    new String[]{"voice.backend: soniox_translation", "voice.backend: unknown"},
                    new String[]{"voice.backend: soniox_translation", "voice.backend: soniox_translation\n  openai.backend: live"},
                    new String[]{"soniox.apiKey: ${SONIOX_API_KEY}", "soniox.apiKey: \"\""},
                    new String[]{"soniox.translationTargetLanguage: en", "soniox.translationTargetLanguage: ja"},
                    new String[]{"soniox.languageHints: ja", "soniox.languageHints: en"},
                    new String[]{"soniox.translationTargetLanguage: en", "soniox.translationTargetLanguage: fr"},
                    new String[]{"soniox.languageHints: ja", "soniox.languageHints: ja,en"},
                    new String[]{"soniox.maxEndpointDelayMs: 1000", "soniox.maxEndpointDelayMs: 499"},
                    new String[]{"soniox.ttsSpeed: 1.0", "soniox.ttsSpeed: NaN"},
                    new String[]{"soniox.ttsSpeed: 1.0", "soniox.ttsSpeed: 1.4"})) {
                Files.writeString(temp, yaml.replace(change[0], change[1]));
                try { GatewayConfigLoader.load(temp); throw new AssertionError("invalid Soniox config accepted"); }
                catch (IllegalArgumentException expected) { checks++; }
            }
            Files.writeString(temp, yaml.replace("  openai.backend: live", "  voice.backend: live"));
            check(GatewayConfigLoader.load(temp).sessions().get(1).openAi().isLive(), "new selector works with existing Live");
            Files.writeString(temp, yaml.replace("  openai.backend: live", "  openai.backend: live\n  voice.backend: live"));
            check(GatewayConfigLoader.load(temp).sessions().get(1).backend().equals("live"), "matching selectors accepted");
            String only = yaml.replace("sessionIds: session-1,session-2,session-3,session-4", "sessionIds: session-4");
            Files.writeString(temp, only);
            check(GatewayConfigLoader.load(temp).sessions().size() == 1, "Soniox-only deployment accepted");
        } finally { Files.deleteIfExists(temp); }
    }

    private static void transcriptAndAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open();
            JsonObject start = f.stt.messages.getFirst();
            check(start.get("audio_format").getAsString().equals("pcm_s16le") && start.get("sample_rate").getAsInt() == 16000,
                    "raw mono PCM16 STT config");
            check(!start.has("api_key") && !start.has("type") && !start.has("instructions") && !start.has("tools"), "no OpenAI protocol or credentials in JSON");
            check(start.getAsJsonObject("translation").get("type").getAsString().equals("one_way"), "one-way translation");
            f.session.startInitialGreeting("must not speak");
            check(f.tts.messages.isEmpty(), "no assistant greeting");
            f.bridge.markOutboundPlayoutActive("sx");
            check(!f.bridge.shouldDropInboundForAssistantSpeaking("sx") && !f.bridge.isLiveSession("sx"), "continuous input without Live disguise");
            for (int rate : List.of(8000, 16000)) {
                check(f.session.appendInputAudio(frame("sx", rate)), "input accepted");
                check(f.stt.audio.getLast().length == 640, "20ms resampled to 16kHz binary");
            }
            int audioCount = f.stt.audio.size();
            check(f.session.appendInputAudio(new AudioFrame("sx", AudioFrame.Direction.INBOUND, 1, new byte[0],
                    "pcm16", 8000, 20, Instant.now())) && f.stt.audio.size() == audioCount,
                    "empty native callback retains session without sending synthetic audio");
            check(!f.session.isIdle(Long.MAX_VALUE, Duration.ofSeconds(15)), "long silence does not idle-close");
            f.stt.tokens(token("仮", false, "original"), token("maybe", false, "translation"));
            check(f.tts.texts().isEmpty(), "provisional text never spoken");
            String targetId = f.hub.latestEvents().stream().filter(e -> e.transcriptKind().equals("translation")).findFirst().orElseThrow().segmentId();
            f.stt.tokens(token("確定", true, "original"), token("Hello", true, "translation"), token(" world", false, "translation"));
            check(f.hub.latestEvents().stream().anyMatch(e -> e.text().equals("Hello world")), "committed plus provisional snapshot");
            f.stt.tokens();
            check(f.hub.latestEvents().stream().anyMatch(e -> e.segmentId().equals(targetId) && e.text().equals("Hello")), "provisional tail disappears");
            f.stt.tokens(token("!", true, "translation"));
            check(f.tts.texts().equals(List.of("Hello!")), "confirmed text sent exactly once");
            JsonObject ttsConfig = f.tts.messages.getFirst();
            check(ttsConfig.get("audio_format").getAsString().equals("pcm_s16le") && ttsConfig.get("sample_rate").getAsInt() == 24000,
                    "TTS returns raw PCM24");
            check(f.tts.messages.get(1).get("text_end").getAsBoolean(), "each TTS unit closes text immediately");
            f.stt.tokens(token("Hello!", true, "translation"));
            check(f.tts.texts().size() == 1, "second stream waits for terminated");
            String stream = f.tts.stream();
            byte[] pcm = new byte[1920]; Arrays.fill(pcm, (byte) 3);
            f.tts.audio(stream, Arrays.copyOfRange(pcm, 0, 157), false);
            f.tts.audio(stream, Arrays.copyOfRange(pcm, 157, pcm.length), true);
            check(f.bridge.outboundDepth("sx") == 2, "arbitrary PCM delta boundaries retain samples");
            check(f.bridge.pollOutbound("sx").payload().length == 320, "PCMU playout at 8kHz");
            f.bridge.pollOutbound("sx");
            check(f.tts.texts().size() == 1, "audio_end alone does not release stream");
            f.tts.terminate(stream);
            check(f.tts.texts().equals(List.of("Hello!", "Hello!")), "legitimate repeated text preserved");
            f.bridge.setSessionSampleRate("sx", 16000);
            stream = f.tts.stream();
            f.tts.audio(stream, new byte[103], true); f.tts.terminate(stream);
            check(f.bridge.pollOutbound("sx").payload().length == 640, "G722 tail padded to 20ms");
            f.stt.tokens(token("次", true, "translation"), token("<end>", true, "original"));
            stream = f.tts.stream();
            f.tts.audio(stream, new byte[960], true); f.tts.terminate(stream);
            check(f.bridge.outboundDepth("sx") == 1, "odd tail not carried across TTS streams");
            check(f.hub.latestEvents().stream().filter(e -> e.transcriptKind().equals("translation"))
                    .allMatch(e -> e.sourceStartMs() == -1 && e.sourceEventId().isEmpty()), "no fabricated translated timestamps or API IDs");
            check(f.hub.latestEvents().stream().noneMatch(e -> e.text().contains("<end>")), "control tokens hidden");
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.tokens(token("temporary", false, "translation")); f.stt.tokens();
            check(f.hub.latestEvents().stream().anyMatch(e -> e.transcriptKind().equals("translation") && e.text().isEmpty()), "empty revision tombstone published");
            f.stt.tokens(token("timer text", true, "translation"));
            Thread.sleep(510); f.session.tick();
            check(f.tts.texts().equals(List.of("timer text")), "500ms confirmed text timer flush");
        }
    }

    private static void failuresAndLifecycle() throws Exception {
        for (int code : List.of(400, 401, 402, 403, 429, 500, 503)) {
            try (Fixture f = new Fixture()) {
                f.open();
                f.stt.emit("{\"error_code\":" + code + ",\"error_type\":\"test\",\"error_message\":\"do not log this\"}");
                check(!f.session.isHealthy() && f.session.retryable() == (code >= 429), "API error classification " + code);
                check(f.stt.aborted && f.tts.aborted, "failure closes both sockets");
            }
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.emit("{\"finished\":true,\"tokens\":[]}");
            check(!f.session.isHealthy() && f.session.retryable(), "unexpected finished restarts stream");
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.tts.emit("{\"error_code\":401}");
            check(!f.session.isHealthy() && !f.session.retryable(), "TTS auth failure stops combined session");
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.confirmEnd = false;
            long start = System.nanoTime(); f.session.close();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "unconfirmed close bounded by timeout");
            check(f.stt.aborted && f.tts.aborted, "close timeout frees transports");
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.tokens(token("Hello!", true, "translation"));
            String stream = f.tts.stream();
            f.active.set(false); f.bridge.removeSessionQueues("sx"); f.stt.drain = true;
            f.session.close();
            check(f.tts.messages.stream().anyMatch(e -> SonioxSocket.flag(e, "cancel")), "hangup cancels TTS");
            check(f.hub.latestEvents().stream().anyMatch(e -> e.text().contains("tail")), "remaining subtitles drained after hangup");
            f.tts.audio(stream, new byte[960], true);
            check(f.bridge.outboundDepth("sx") == 0 && f.tts.texts().size() == 1, "hangup cannot restart TTS or queues");
            check(f.stt.endSent, "STT ends with empty text frame");
        }
        Fixture f = new Fixture();
        CompletableFuture<WebSocket> late = new CompletableFuture<>();
        SonioxTranslationSession starting = f.create(listener -> { f.stt.listener = listener; return late; }, false);
        CompletableFuture<Void> opening = CompletableFuture.runAsync(() -> {
            try { starting.open(); throw new AssertionError("late startup accepted"); }
            catch (SonioxSessionException expected) { }
        });
        await(() -> f.stt.listener != null);
        f.active.set(false); starting.close(); late.complete(f.stt);
        opening.get(1, TimeUnit.SECONDS);
        check(f.stt.aborted && f.tts.aborted, "socket connecting after hangup is discarded");
        try (Fixture bad = new Fixture()) {
            bad.stt.onConfig = () -> bad.stt.emit("{\"error_code\":401}");
            try { bad.open(); throw new AssertionError("startup auth accepted"); }
            catch (SonioxSessionException expected) { check(!expected.retryable(), "startup failure preserves permanent classification"); }
        }
        try (Fixture bad = new Fixture()) {
            bad.open(); bad.stt.emit("invalid json");
            check(!bad.session.isHealthy(), "invalid JSON fails safely");
        }
    }

    private static void boundedQueues() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.blockAudio = true;
            for (int n = 0; n < 101; n++) f.session.appendInputAudio(frame("sx", 8000));
            check(!f.session.isHealthy(), "binary send backlog bounded");
        }
        try (Fixture f = new Fixture()) {
            f.open();
            for (int n = 0; n < 12; n++) f.stt.tokens(token("queued.", true, "translation"));
            check(!f.session.isHealthy(), "TTS text units bounded");
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.tokens(token("Speak.", true, "translation"));
            f.tts.audio(f.tts.stream(), new byte[960 * 501], false);
            check(!f.session.isHealthy() && f.bridge.outboundDepth("sx") == 0, "playout backlog cannot grow beyond ten seconds");
        }
        try (Fixture f = new Fixture()) {
            f.open();
            var field = SonioxTranslationSession.class.getDeclaredField("started"); field.setAccessible(true);
            field.setLong(f.session, System.nanoTime() - TimeUnit.MINUTES.toNanos(300)); f.session.tick();
            check(!f.session.isHealthy(), "300-minute stream limit restarts without replay");
        }
        try (Fixture f = new Fixture()) {
            f.open();
            var sttField = SonioxTranslationSession.class.getDeclaredField("stt"); sttField.setAccessible(true);
            var send = SonioxSocket.class.getDeclaredField("lastSend"); send.setAccessible(true);
            send.setLong(sttField.get(f.session), System.nanoTime() - TimeUnit.SECONDS.toNanos(6)); f.session.tick();
            check(f.stt.messages.stream().anyMatch(e -> SonioxSocket.string(e, "type").equals("keepalive")), "keepalive without synthetic audio");
        }
    }

    private static void additionalContracts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open();
            f.stt.listener.onText(f.stt, "{\"tokens\":[", false);
            f.stt.listener.onText(f.stt, token("fragment", true, "translation") + "]}", true);
            check(f.hub.latestEvents().stream().anyMatch(e -> e.text().equals("fragment")), "fragmented JSON reassembled");
            f.stt.tokens(token("x".repeat(600), true, "translation"));
            check(f.tts.texts().getFirst().length() == 512, "long confirmed translation split into bounded TTS units");
        }
        try (Fixture f = new Fixture()) {
            SonioxTranslationSession automatic = f.create(f.stt::connect, true);
            automatic.open(); f.stt.tokens(token("automatic timer", true, "translation"));
            await(() -> !f.tts.texts().isEmpty());
            check(f.tts.texts().equals(List.of("automatic timer")), "production timer flushes TTS without new input");
            f.active.set(false); automatic.close();
        }
        try (Fixture f = new Fixture()) {
            f.open(); f.stt.blockAudio = true; f.session.appendInputAudio(frame("sx", 8000));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (f.session.isHealthy() && System.nanoTime() < deadline) Thread.sleep(10);
            check(!f.session.isHealthy() && f.session.retryable(), "asynchronous binary send timeout fails session");
        }
        try (Fixture f = new Fixture()) {
            var smallBridge = new AudioBridge(new GatewayConfig.MediaConfig(5, 1), config.openAi());
            smallBridge.configureSessionBackend("sx", "soniox_translation", 2);
            SonioxTranslationSession small = new SonioxTranslationSession("sx", config.sessions().get(3).soniox(),
                    smallBridge, f.hub, f.active::get, f.stt::connect, f.tts::connect, false, Duration.ofMillis(30));
            small.open(); f.stt.tokens(token("Small queue.", true, "translation"));
            f.tts.audio(f.tts.stream(), new byte[1920], true);
            check(!small.isHealthy() && smallBridge.outboundDepth("sx") == 0, "configured output capacity overflow cannot silently lose audio");
            small.close();
        }
        try (Fixture f = new Fixture()) {
            f.open();
            f.bridge.configureSessionBackend("other", "soniox_translation", 2);
            Socket otherStt = new Socket(false), otherTts = new Socket(true);
            SonioxTranslationSession other = new SonioxTranslationSession("other", config.sessions().get(3).soniox(),
                    f.bridge, f.hub, () -> true, otherStt::connect, otherTts::connect, false, Duration.ofMillis(30));
            other.open();
            f.stt.tokens(token("First.", true, "translation"));
            otherStt.tokens(token("Second.", true, "translation"));
            f.tts.audio(f.tts.stream(), new byte[960], true);
            check(f.bridge.outboundDepth("sx") == 1 && f.bridge.outboundDepth("other") == 0, "simultaneous calls isolate output queues");
            otherTts.audio(otherTts.stream(), new byte[960], true);
            check(f.bridge.outboundDepth("other") == 1 && otherTts.texts().equals(List.of("Second.")), "simultaneous calls isolate translations and TTS streams");
            other.close();
        }
    }

    private static void englishToJapanese() throws Exception {
        var reverse = GatewayConfigLoader.load(Path.of("config/gateway.soniox.en-ja.example.yaml")).sessions().get(3).soniox();
        check(reverse.languageHints().equals(List.of("en")) && reverse.translationTargetLanguage().equals("ja"),
                "English to Japanese config accepted");
        try (Fixture f = new Fixture(reverse)) {
            f.open();
            JsonObject start = f.stt.messages.getFirst();
            check(start.getAsJsonObject("translation").get("target_language").getAsString().equals("ja"), "STT target is Japanese");
            check(start.getAsJsonArray("language_hints").get(0).getAsString().equals("en"), "STT source hint is English");
            check(f.hub.latestEvents().stream().anyMatch(e -> e.transcriptKind().equals("status")
                    && e.targetLanguage().equals("ja") && e.text().contains("英語から日本語")), "reverse direction status");
            f.stt.tokens(token("Good morning.", true, "original", "en"), token("おはよう", false, "translation", "ja"));
            check(f.tts.texts().isEmpty(), "English source and provisional Japanese are not spoken");
            f.stt.tokens(token("おはようございます。", true, "translation", "ja"));
            check(f.tts.texts().equals(List.of("おはようございます。")), "confirmed Japanese is spoken once");
            check(f.tts.messages.getFirst().get("language").getAsString().equals("ja"), "TTS uses Japanese language");
            check(f.tts.messages.getFirst().get("voice").getAsString().equals(reverse.ttsVoice()), "configured voice retained across directions");
            check(f.hub.latestEvents().stream().anyMatch(e -> e.transcriptKind().equals("translation")
                    && e.targetLanguage().equals("ja") && e.text().equals("おはようございます。")), "Japanese subtitle metadata");
            for (int rate : List.of(8000, 16000)) {
                f.bridge.setSessionSampleRate("sx", rate);
                if (rate == 16000) f.stt.tokens(token("次の文です。", true, "translation", "ja"));
                String stream = f.tts.stream();
                f.tts.audio(stream, new byte[960], true); f.tts.terminate(stream);
                check(f.bridge.pollOutbound("sx").payload().length == rate / 50 * 2, "Japanese TTS playout at " + rate);
            }
            int spoken = f.tts.texts().size();
            f.stt.tokens(token("Wrong target.", true, "translation", "en"));
            check(f.tts.texts().size() == spoken, "mismatched language never forwarded to Japanese TTS");
        }
        try (Fixture forward = new Fixture()) {
            forward.open();
            forward.bridge.configureSessionBackend("reverse", "soniox_translation", 2);
            Socket stt = new Socket(false), tts = new Socket(true);
            SonioxTranslationSession reverseSession = new SonioxTranslationSession("reverse", reverse,
                    forward.bridge, forward.hub, forward.active::get, stt::connect, tts::connect, false, Duration.ofMillis(30));
            reverseSession.open();
            forward.stt.tokens(token("English.", true, "translation", "en"));
            stt.tokens(token("日本語です。", true, "translation", "ja"));
            check(forward.tts.texts().equals(List.of("English.")) && tts.texts().equals(List.of("日本語です。")),
                    "simultaneous opposite directions isolate TTS text");
            check(forward.tts.messages.getFirst().get("language").getAsString().equals("en")
                    && tts.messages.getFirst().get("language").getAsString().equals("ja"), "per-slot TTS language isolation");
            check(forward.hub.eventsForSession("sx").stream().anyMatch(e -> e.targetLanguage().equals("en"))
                    && forward.hub.eventsForSession("reverse").stream().anyMatch(e -> e.targetLanguage().equals("ja")),
                    "per-call subtitle target isolation");
            reverseSession.close();
        }
    }

    private static String token(String text, boolean confirmed, String status) {
        return token(text, confirmed, status, status.equals("translation") ? "en" : "ja");
    }
    private static String token(String text, boolean confirmed, String status, String language) {
        return "{\"text\":" + SonioxSocket.JSON.toJson(text) + ",\"is_final\":" + confirmed
                + ",\"translation_status\":\"" + status + "\",\"language\":\"" + language + "\"}";
    }
    private static AudioFrame frame(String id, int rate) {
        return new AudioFrame(id, AudioFrame.Direction.INBOUND, 1, new byte[rate / 50 * 2], "pcm16", rate, 20, Instant.now());
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); checks++; }
    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(predicate.getAsBoolean(), "async condition completed");
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicBoolean active = new AtomicBoolean(true);
        final ConversationEventHub hub = new ConversationEventHub();
        final AudioBridge bridge = new AudioBridge(config.media(), new GatewayConfig.OpenAiRuntimeConfig(true, true, 600, -35, 500));
        final Socket stt = new Socket(false);
        final Socket tts = new Socket(true);
        final SonioxTranslationSession session;
        final GatewayConfig.SonioxConfig selectedConfig;
        Fixture() { this(config.sessions().get(3).soniox()); }
        Fixture(GatewayConfig.SonioxConfig selectedConfig) {
            this.selectedConfig = selectedConfig;
            bridge.configureSessionBackend("sx", "soniox_translation", 2); bridge.setSessionSampleRate("sx", 8000);
            session = create(stt::connect, false);
        }
        SonioxTranslationSession create(java.util.function.Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector, boolean timer) {
            return new SonioxTranslationSession("sx", selectedConfig, bridge, hub, active::get,
                    connector, tts::connect, timer, Duration.ofMillis(30));
        }
        void open() { session.open(); }
        @Override public void close() { active.set(false); session.close(); }
    }

    private static final class Socket implements WebSocket {
        final boolean tts;
        volatile Listener listener;
        final List<JsonObject> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<byte[]> audio = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean aborted;
        boolean confirmEnd = true;
        boolean blockAudio;
        boolean drain;
        boolean endSent;
        Runnable onConfig = () -> { };
        Socket(boolean tts) { this.tts = tts; }
        CompletableFuture<WebSocket> connect(Listener target) { listener = target; target.onOpen(this); return CompletableFuture.completedFuture(this); }
        void emit(String json) { listener.onText(this, json, true); }
        void tokens(String... tokens) { emit("{\"tokens\":[" + String.join(",", tokens) + "]}"); }
        List<String> texts() { return messages.stream().filter(e -> e.has("text")).map(e -> e.get("text").getAsString()).toList(); }
        String stream() { return messages.stream().filter(e -> e.has("model")).reduce((a,b) -> b).orElseThrow().get("stream_id").getAsString(); }
        void audio(String id, byte[] bytes, boolean end) {
            emit("{\"stream_id\":\"" + id + "\",\"audio\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"audio_end\":" + end + "}");
        }
        void terminate(String id) { emit("{\"stream_id\":\"" + id + "\",\"terminated\":true}"); }
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (data.isEmpty()) {
                endSent = true;
                if (drain) tokens(token("tail", true, "translation"));
                if (confirmEnd) emit("{\"tokens\":[],\"finished\":true}");
            } else {
                JsonObject event = JsonParser.parseString(data.toString()).getAsJsonObject(); messages.add(event);
                if (!tts && event.has("model")) onConfig.run();
                if (SonioxSocket.flag(event, "cancel")) terminate(event.get("stream_id").getAsString());
            }
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer bytes, boolean last) {
            byte[] data = new byte[bytes.remaining()]; bytes.get(data); audio.add(data);
            return blockAudio ? new CompletableFuture<>() : CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int status, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isInputClosed() { return aborted; }
        @Override public boolean isOutputClosed() { return aborted; }
        @Override public void abort() { aborted = true; }
    }
}
