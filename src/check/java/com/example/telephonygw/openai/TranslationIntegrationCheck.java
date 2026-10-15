package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.config.GatewayConfigLoader;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.media.AudioFrame;
import com.example.telephonygw.monitor.ConversationEventHub;
import com.google.gson.JsonObject;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated translation contract checks with an in-memory server and manual media clock. */
final class TranslationIntegrationCheck {
    private static int checks;
    static void run() throws Exception {
        var config = GatewayConfigLoader.load(Path.of("config/gateway.translation.example.yaml"));
        var slot = config.sessions().get(2);
        var ai = slot.openAi();
        check(config.sessions().get(0).openAi().backend().equals("realtime") && config.sessions().get(1).openAi().isLive()
                && ai.isTranslation(), "three independent backends");
        check(ai.selectedModel().equals("gpt-realtime-translate") && ai.selectedVoice().equals("dynamic"), "selected translation config");
        check(slot.bot().systemInstructions().isEmpty(), "translation requires no assistant instructions");
        GatewayConfig.OpenAiConfig noTranscriptConfig;
        String yaml = Files.readString(Path.of("config/gateway.translation.example.yaml"));
        Path temporary = Files.createTempFile(Path.of("build"), "translation-config-", ".yaml");
        try {
            for (String replacement : List.of("  openai.translationTargetLanguage: xx", "")) {
                Files.writeString(temporary, yaml.replace("  openai.translationTargetLanguage: en", replacement));
                try { GatewayConfigLoader.load(temporary); throw new AssertionError("bad language accepted"); }
                catch (IllegalArgumentException expected) { checks++; }
            }
            Files.writeString(temporary, yaml.replace("  openai.translationSourceTranscriptEnabled: true", "  openai.translationSourceTranscriptEnabled: false"));
            noTranscriptConfig = GatewayConfigLoader.load(temporary).sessions().get(2).openAi();
            check(!noTranscriptConfig.translationSourceTranscriptEnabled(), "source transcription optional");
        } finally { Files.deleteIfExists(temporary); }
        var runtime = new GatewayConfig.OpenAiRuntimeConfig(true, true, 600, -35, 500);
        AudioBridge bridge = new AudioBridge(config.media(), runtime);
        bridge.configureSessionBackend("tr", "translation", 2);
        bridge.setSessionSampleRate("tr", 16000);
        bridge.markOutboundPlayoutActive("tr");
        check(!bridge.isLiveSession("tr") && bridge.isContinuousSession("tr"), "translation is not Live");
        check(!bridge.shouldDropInboundForAssistantSpeaking("tr") && bridge.clearInboundForAssistantSpeaking("tr") == 0,
                "translation listens during playout");
        ConversationEventHub hub = new ConversationEventHub();
        Socket socket = new Socket();
        AtomicBoolean active = new AtomicBoolean(true);
        TranslationSession session = create(ai, bridge, hub, active, socket);
        session.open();
        JsonObject update = socket.sent.getFirst().getAsJsonObject("session");
        check(update.size() == 1 && update.has("audio"), "no instructions, voice, VAD or tools");
        check(update.getAsJsonObject("audio").getAsJsonObject("input").getAsJsonObject("transcription")
                .get("model").getAsString().equals("gpt-realtime-whisper"), "source transcript model");
        session.startInitialGreeting("must not speak");
        check(socket.sent.size() == 1, "no greeting event");
        check(!session.isIdle(Long.MAX_VALUE, Duration.ofSeconds(15)), "translation survives long silence");
        byte[] input = new byte[320];
        Arrays.fill(input, (byte) 7);
        for (int i = 0; i < 10; i++) {
            check(session.appendInputAudio(frame(input, 8000)), "input accepted while translating");
            session.tick();
        }
        var sentAudio = socket.sent.getLast();
        byte[] translatedInput = Base64.getDecoder().decode(sentAudio.get("audio").getAsString());
        check(sentAudio.get("type").getAsString().equals("session.input_audio_buffer.append") && translatedInput.length == 9600,
                "ten 20ms frames make one 200ms chunk");
        check(translatedInput[0] == 7, "input speech retained");
        for (int i = 0; i < 10; i++) session.tick();
        check(Arrays.equals(Base64.getDecoder().decode(socket.sent.getLast().get("audio").getAsString()), new byte[9600]),
                "missing callbacks synthesize continuous silence");
        for (int rate : List.of(8000, 16000)) {
            bridge.setSessionSampleRate("tr", rate);
            byte[] pcm = new byte[1920];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 3);
            socket.audio(Arrays.copyOfRange(pcm, 0, 157));
            socket.audio(Arrays.copyOfRange(pcm, 157, pcm.length));
            for (int i = 0; i < 2; i++) check(Arrays.equals(bridge.pollOutbound("tr").payload(),
                    Pcm16Resampler.downsample(Arrays.copyOfRange(pcm, i * 960, (i + 1) * 960), 24000, rate)), "arbitrary output boundary at " + rate);
        }
        socket.transcript("input", "s1", "今日は", "200");
        socket.transcript("output", "t1", "Good", "200");
        socket.transcript("output", "t2", " morning", "200");
        socket.transcript("output", "t2", " morning", "200");
        socket.transcript("input", "s2", "晴れ", "null");
        var events = hub.eventsForSession("tr");
        check(events.size() == 3, "status and independent source/target segments");
        check(events.get(1).text().equals("今日は晴れ") && events.get(2).text().equals("Good morning"), "append only deltas and event ID dedup");
        check(events.get(2).transcriptKind().equals("translation") && events.get(2).targetLanguage().equals("en")
                && !events.get(2).finalTranscript() && events.get(2).itemId().isEmpty(), "explicit translation semantics, no API final turn");
        session.appendInputAudio(frame(new byte[640], 16000));
        active.set(false);
        bridge.removeSessionQueues("tr");
        socket.drainOnClose = true;
        session.close();
        session.close();
        check(socket.count("session.close") == 1 && socket.closed && !socket.aborted, "graceful close exactly once");
        check(Base64.getDecoder().decode(socket.sent.get(socket.sent.size() - 2).get("audio").getAsString()).length == 960,
                "partial input flushed before close");
        check(hub.eventsForSession("tr").getLast().text().endsWith("!"), "final subtitle drained after SIP hangup");
        check(bridge.outboundDepth("tr") == 0, "late audio cannot recreate ended call queue");
        socket.transcript("output", "late", "stale", "400");
        check(!hub.eventsForSession("tr").getLast().text().contains("stale"), "closed generation rejects stale events");

        Socket failed = new Socket(); failed.confirmClose = false;
        var timeout = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), failed);
        timeout.open(); timeout.close();
        check(failed.aborted && !failed.closed, "close timeout aborts transport");
        Socket bad = new Socket(); bad.language = "ja";
        var mismatch = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), bad);
        try { mismatch.open(); throw new AssertionError("wrong target accepted"); } catch (IllegalStateException expected) { checks++; }
        check(bad.aborted, "target mismatch releases transport"); mismatch.close();

        Socket overflow = new Socket();
        var bounded = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), overflow);
        bounded.open();
        for (int i = 0; i < 100; i++) bounded.appendInputAudio(frame(new byte[320], 8000));
        check(!bounded.appendInputAudio(frame(new byte[320], 8000)) && overflow.aborted, "bounded incoming audio");
        bounded.close();
        Socket invalid = new Socket();
        var format = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), invalid);
        format.open(); invalid.emit("{\"type\":\"session.output_audio.delta\",\"sample_rate\":8000}");
        check(!format.isHealthy() && invalid.aborted, "unsupported output fails visibly"); format.close();

        Socket slow = new Socket(); slow.confirmUpdate = false;
        var starting = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), slow);
        var open = CompletableFuture.runAsync(starting::open);
        await(() -> slow.count("session.update") == 1);
        check(!starting.appendInputAudio(frame(new byte[320], 8000)), "no input before updated");
        starting.close();
        try { open.join(); throw new AssertionError("cancelled startup accepted"); } catch (java.util.concurrent.CompletionException expected) { checks++; }
        check(slow.aborted, "cancelled startup aborts socket");
        Socket blocked = new Socket(); blocked.blockAudio = true;
        var sending = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), blocked);
        sending.open();
        for (int i = 0; i < 1010; i++) sending.tick();
        check(!sending.isHealthy() && blocked.aborted, "bounded outgoing websocket backlog"); sending.close();

        Socket apiError = new Socket();
        var rejected = create(ai, bridge, new ConversationEventHub(), new AtomicBoolean(true), apiError);
        rejected.open(); apiError.emit("{\"type\":\"error\",\"error\":{\"code\":\"invalid_request\"}}");
        check(!rejected.isHealthy() && apiError.aborted, "API errors invalidate session"); rejected.close();

        CompletableFuture<WebSocket> lateConnection = new CompletableFuture<>();
        var late = new TranslationSession("tr", ai, bridge, new ConversationEventHub(), () -> true,
                listener -> lateConnection, Duration.ofMillis(20), false);
        var connecting = CompletableFuture.runAsync(late::open);
        // Close is allowed before the transport promise resolves.
        late.close();
        Socket lateSocket = new Socket(); lateConnection.complete(lateSocket);
        try { connecting.join(); throw new AssertionError("late transport accepted"); }
        catch (java.util.concurrent.CompletionException expected) { checks++; }
        check(lateSocket.aborted, "late connection always released");
        Socket noTranscriptSocket = new Socket();
        ConversationEventHub noTranscriptHub = new ConversationEventHub();
        var noTranscript = create(noTranscriptConfig, bridge, noTranscriptHub, new AtomicBoolean(true), noTranscriptSocket);
        noTranscript.open();
        check(noTranscriptSocket.sent.getFirst().getAsJsonObject("session").getAsJsonObject("audio")
                .getAsJsonObject("input").get("transcription").isJsonNull(), "disable transcription explicitly");
        noTranscriptSocket.transcript("input", "ignored", "unused", "200");
        check(noTranscriptHub.latestEvents().size() == 1, "disabled source transcript not published");
        noTranscript.close();

        Socket clockSocket = new Socket();
        var clocked = new TranslationSession("tr", ai, bridge, new ConversationEventHub(), () -> true,
                clockSocket::connect, Duration.ofMillis(20), true);
        clocked.open();
        await(() -> clockSocket.count("session.input_audio_buffer.append") >= 2);
        check(clocked.isHealthy(), "automatic clock streams continuous silence without SIP callbacks");
        clocked.close();
        System.out.println("Translation integration checks passed: " + checks);
    }

    private static TranslationSession create(GatewayConfig.OpenAiConfig config, AudioBridge bridge, ConversationEventHub hub,
            AtomicBoolean active, Socket socket) {
        return new TranslationSession("tr", config, bridge, hub, active::get, socket::connect, Duration.ofMillis(20), false);
    }
    private static AudioFrame frame(byte[] pcm, int rate) {
        return new AudioFrame("tr", AudioFrame.Direction.INBOUND, 1, pcm, "pcm16", rate, 20, Instant.now());
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), "async event arrived");
    }

    private static final class Socket implements WebSocket {
        final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        WebSocket.Listener listener;
        String language = "en";
        boolean confirmUpdate = true, confirmClose = true, drainOnClose, blockAudio;
        boolean aborted, closed;
        CompletableFuture<WebSocket> connect(WebSocket.Listener listener) {
            this.listener = listener; listener.onOpen(this);
            emit("{\"type\":\"session.created\"}");
            return CompletableFuture.completedFuture(this);
        }
        void emit(String text) {
            int split = text.length() / 2;
            listener.onText(this, text.substring(0, split), false);
            listener.onText(this, text.substring(split), true);
        }
        void audio(byte[] pcm) { emit(JsonCodec.encode(java.util.Map.of("type", "session.output_audio.delta", "delta", Base64.getEncoder().encodeToString(pcm)))); }
        void transcript(String direction, String id, String text, String elapsed) {
            emit("{\"type\":\"session." + direction + "_transcript.delta\",\"event_id\":\"" + id + "\",\"delta\":" + JsonCodec.encode(text) + ",\"elapsed_ms\":" + elapsed + "}");
        }
        long count(String type) { return sent.stream().filter(e -> e.get("type").getAsString().equals(type)).count(); }
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            JsonObject event = JsonCodec.decode(data.toString()); sent.add(event);
            String type = event.get("type").getAsString();
            if (type.equals("session.update") && confirmUpdate) emit("{\"type\":\"session.updated\",\"session\":{\"audio\":{\"output\":{\"language\":\"" + language + "\"}}}}");
            if (type.equals("session.close") && confirmClose) {
                if (drainOnClose) { transcript("output", "tail", "!", "400"); audio(new byte[960]); }
                emit("{\"type\":\"session.closed\"}");
            }
            if (blockAudio && type.equals("session.input_audio_buffer.append")) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int status, String reason) { closed = true; return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isInputClosed() { return aborted || closed; }
        @Override public boolean isOutputClosed() { return aborted || closed; }
        @Override public void abort() { aborted = true; }
    }
}
