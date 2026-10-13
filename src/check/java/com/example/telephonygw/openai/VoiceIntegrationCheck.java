package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.config.GatewayConfigLoader;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventHub;
import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline checks: no actual SIP registration or OpenAI calls. */
public final class VoiceIntegrationCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        GatewayConfig old = GatewayConfigLoader.load(Path.of("config/gateway.example.yaml"));
        GatewayConfig mixed = GatewayConfigLoader.load(Path.of("config/gateway.live.example.yaml"));
        check(old.sessions().stream().allMatch(s -> !s.openAi().isLive()), "legacy backend defaults");
        check(mixed.sessions().get(1).openAi().isLive(), "mixed Live slot");
        check(mixed.sessions().get(1).openAi().realtimeModel().isEmpty(), "Live requires no Realtime model");
        var live = mixed.sessions().get(1);
        var runtime = new GatewayConfig.OpenAiRuntimeConfig(true, true, 600, -35, 500);
        AudioBridge bridge = new AudioBridge(mixed.media(), runtime);
        bridge.configureSessionBackend("live", true, 2);
        bridge.markOutboundPlayoutActive("live");
        bridge.markOutboundPlayoutActive("rt");
        check(!bridge.shouldDropInboundForAssistantSpeaking("live"), "Live listens while speaking");
        check(bridge.shouldDropInboundForAssistantSpeaking("rt"), "Realtime drop policy preserved");
        bridge.enqueueInboundPcm16("live", new byte[320], 8000, 20);
        check(bridge.clearInboundForAssistantSpeaking("live") == 0 && bridge.inboundDepth("live") == 1, "Live retains input");

        bridge.configureSessionBackend("audio", true, 2);
        for (int rate : List.of(8000, 16000)) {
            bridge.setSessionSampleRate("audio", rate);
            LiveAudioDecoder decoder = new LiveAudioDecoder(bridge, "audio");
            byte[] source = new byte[1920];
            for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 7);
            for (int offset = 0; offset < source.length;) {
                int end = Math.min(source.length, offset + 157);
                decoder.append(Base64.getEncoder().encodeToString(Arrays.copyOfRange(source, offset, end)));
                offset = end;
            }
            for (int frameIndex = 0; frameIndex < 2; frameIndex++) {
                AudioFrame frame = bridge.pollOutbound("audio");
                byte[] expected = Pcm16Resampler.downsample(Arrays.copyOfRange(source, frameIndex * 960, (frameIndex + 1) * 960), 24000, rate);
                check(frame != null && Arrays.equals(expected, frame.payload()), "arbitrary PCM splits at " + rate);
            }
            decoder.append(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
            decoder.flush();
            check(bridge.pollOutbound("audio") != null, "partial tail padded");
            decoder.append(Base64.getEncoder().encodeToString(new byte[] {4}));
            decoder.flush();
            check(bridge.pollOutbound("audio").payload()[0] == 3, "odd sample byte retained");
        }

        ConversationEventHub hub = new ConversationEventHub(5);
        LiveTranscriptAssembler assembler = new LiveTranscriptAssembler("live", hub);
        assembler.append("caller", "c1", "予約を", 0, 300);
        assembler.append("assistant", "a1", "はい", 100, 400);
        assembler.append("caller", "c2", "変更したい", 300, 700);
        check(hub.latestEvents().size() == 2, "independent overlapping speakers");
        check(hub.latestEvents().getFirst().text().equals("予約を変更したい"), "full snapshot replaces segment");
        check(!hub.latestEvents().getFirst().finalTranscript() && hub.latestEvents().getFirst().itemId().isEmpty(), "no invented API final or item ID");
        assembler.append("caller", "c3", "別の話", 3000, 3200);
        check(hub.latestEvents().size() == 3, "display gap creates segment");

        FakeSocket fake = new FakeSocket();
        AtomicBoolean active = new AtomicBoolean(true);
        LiveSession session = new LiveSession("live", live.openAi(), live.bot(), bridge, hub, active::get,
                listener -> fake.connect(listener), Duration.ofMillis(20));
        session.open();
        JsonObject start = fake.sent.getFirst().getAsJsonObject("session");
        check(!start.has("delegation") && !start.has("tools"), "no backend or tool configuration");
        check(!start.get("store").getAsBoolean(), "storage disabled");
        check(start.getAsJsonArray("input").get(0).getAsJsonObject().has("content"), "reconnect history schema");
        check(!session.isIdle(System.nanoTime() + Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(15)), "Live survives conversation pauses");
        session.startInitialGreeting("こんにちは");
        session.startInitialGreeting("duplicate");
        check(fake.count("session.instructions.append") == 1, "greeting sent once");
        check(fake.sent.getLast().get("delegation_id").isJsonNull(), "greeting nullable ID encoded");
        check(session.appendInputAudio(new AudioFrame("live", AudioFrame.Direction.INBOUND, 1, new byte[320], "pcm16", 8000, 20, Instant.now())), "input accepted");
        check(Base64.getDecoder().decode(fake.sent.getLast().get("audio").getAsString()).length == 960, "input resampled");
        fake.emit("{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":12}}");
        fake.emit("{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":15}}");
        fake.emit("{\"type\":\"session.delegation.created\",\"delegation\":{\"id\":\"unused\",\"target\":\"client\"}}");
        check(fake.count("response.create") == 0 && fake.count("response.item.create") == 0, "unexpected delegation executes no work");
        int before = hub.latestEvents().size();
        active.set(false);
        fake.emit("{\"type\":\"session.input_transcript.delta\",\"delta\":\"late\",\"event_id\":\"late\",\"start_ms\":0,\"end_ms\":1}");
        check(before == hub.latestEvents().size(), "closed call ignores stale transcript");
        session.close();
        check(fake.count("session.close") == 1 && fake.closeSent && !fake.aborted, "graceful finalization");
        session.close();
        check(fake.count("session.close") == 1, "close idempotent");

        FakeSocket slow = new FakeSocket();
        slow.autoStart = false;
        LiveSession starting = new LiveSession("starting", live.openAi(), live.bot(), bridge, hub, () -> true,
                listener -> slow.connect(listener), Duration.ofMillis(20));
        CompletableFuture<Void> open = CompletableFuture.runAsync(() -> starting.open());
        await(() -> slow.count("session.start") == 1);
        check(!starting.appendInputAudio(new AudioFrame("starting", AudioFrame.Direction.INBOUND, 1, new byte[320], "pcm16", 8000, 20, Instant.now())), "audio waits for session.started");
        starting.close();
        try { open.join(); throw new AssertionError("startup should fail"); } catch (java.util.concurrent.CompletionException expected) { }
        check(slow.aborted, "startup cancellation releases transport");

        FakeSocket timeout = new FakeSocket();
        timeout.autoClose = false;
        LiveSession unfinished = new LiveSession("timeout", live.openAi(), live.bot(), bridge, hub, () -> true,
                listener -> timeout.connect(listener), Duration.ofMillis(5));
        unfinished.open();
        unfinished.close();
        check(timeout.aborted, "close timeout releases transport");

        FakeSocket blocked = new FakeSocket();
        LiveSession bounded = new LiveSession("bounded", live.openAi(), live.bot(), bridge, hub, () -> true,
                listener -> blocked.connect(listener), Duration.ofMillis(5));
        bounded.open();
        blocked.blockAudio = true;
        boolean rejected = false;
        for (int i = 0; i < 102; i++) {
            if (!bounded.appendInputAudio(new AudioFrame("bounded", AudioFrame.Direction.INBOUND, i, new byte[320], "pcm16", 8000, 20, Instant.now()))) { rejected = true; break; }
        }
        check(rejected && blocked.aborted, "send backlog bounded");
        bounded.close();
        bridge.removeSessionQueues("live");
        check(!bridge.enqueueOutboundPcm16("live", new byte[320], 8000, 20) && bridge.outboundDepth("live") == 0,
                "late callbacks cannot recreate closed queues");
        check(JsonCodec.decode("{\"type\":\"outer\",\"nested\":{\"type\":\"inner\"},\"text\":\"日本語\\n\\\"引用\\\"\"}").get("type").getAsString().equals("outer"), "nested JSON and Unicode");
        try { JsonCodec.decode("{type:'bad'}"); throw new AssertionError("strict JSON expected"); }
        catch (com.google.gson.JsonParseException expected) { checks++; }
        BlockingSession delayed = new BlockingSession();
        try (VoiceSessionManager manager = new VoiceSessionManager(mixed.sessions(), runtime, bridge, hub,
                (id, config, bot) -> delayed)) {
            manager.initialize();
            manager.startAudioForwarding();
            manager.startSession("closing-during-open", "session-2", "test");
            check(delayed.entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "manager begins opening");
            manager.closeSession("closing-during-open", "session-2", "test");
            delayed.release.countDown();
            check(delayed.closed.await(2, java.util.concurrent.TimeUnit.SECONDS), "late startup cannot register a ended call");
            check(!delayed.greeted, "closed call gets no greeting");
        }
        System.out.println("Voice integration checks passed: " + checks);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), "async startup event");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }

    private static final class BlockingSession implements VoiceSession {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        boolean greeted;
        @Override public void open() {
            entered.countDown();
            boolean complete = false;
            while (!complete) {
                try { release.await(); complete = true; } catch (InterruptedException ignored) { }
            }
        }
        @Override public boolean appendInputAudio(AudioFrame frame) { return true; }
        @Override public void startInitialGreeting(String text) { greeted = true; }
        @Override public boolean isIdle(long now, Duration duration) { return false; }
        @Override public void close() { closed.countDown(); }
    }

    private static final class FakeSocket implements WebSocket {
        final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        WebSocket.Listener listener;
        boolean autoStart = true;
        boolean autoClose = true;
        boolean blockAudio;
        volatile boolean aborted;
        boolean closeSent;
        CompletableFuture<WebSocket> connect(WebSocket.Listener listener) {
            this.listener = listener;
            listener.onOpen(this);
            return CompletableFuture.completedFuture(this);
        }
        void emit(String payload) {
            int middle = payload.length() / 2;
            listener.onText(this, payload.substring(0, middle), false);
            listener.onText(this, payload.substring(middle), true);
        }
        long count(String type) { return sent.stream().filter(e -> e.get("type").getAsString().equals(type)).count(); }
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            JsonObject event = JsonCodec.decode(data.toString());
            sent.add(event);
            String type = event.get("type").getAsString();
            if (autoStart && type.equals("session.start")) emit("{\"type\":\"session.started\",\"session\":{\"id\":\"test\"}}");
            if (autoClose && type.equals("session.close")) emit("{\"type\":\"session.closed\",\"usage\":{\"seconds\":15},\"reason\":\"close_requested\"}");
            if (blockAudio && type.equals("session.input_audio.append")) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int status, String reason) { closeSent = true; return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return aborted || closeSent; }
        @Override public boolean isInputClosed() { return aborted; }
        @Override public void abort() { aborted = true; }
    }
}
