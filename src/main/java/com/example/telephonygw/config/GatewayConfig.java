package com.example.telephonygw.config;

import com.example.telephonygw.media.CodecConfig;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record GatewayConfig(
        List<SessionSlotConfig> sessions,
        MediaConfig media,
        OpenAiRuntimeConfig openAi,
        LoggingConfig logging,
        MonitorConfig monitor
) {
    private static final Set<String> SUPPORTED_REALTIME_VOICES = Set.of(
            "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar");

    public record SessionSlotConfig(
            String slotId,
            String name,
            SipConfig sip,
            RegistrationConfig registration,
            OpenAiConfig openAi,
            BotConfig bot
    ) {
        public void validate() {
            require("session.slotId", slotId);
            require("session." + slotId + ".name", name);
            sip.validate("session." + slotId + ".sip");
            registration.validate("session." + slotId + ".registration");
            openAi.validate("session." + slotId + ".openai");
            bot.validate("session." + slotId + ".bot");
        }
    }

    public record SipConfig(
            String backend,
            String bindAddress,
            int port,
            String transport,
            String ipVersion,
            String codec,
            String preferredCodec,
            List<String> codecs,
            String publicContactAddress,
            int rtpPortStart,
            int rtpPortEnd
    ) {
        public void validate() {
            validate("sip");
        }

        public void validate(String prefix) {
            require(prefix + ".backend", backend);
            if (!"placeholder".equalsIgnoreCase(backend) && !"pjsua2".equalsIgnoreCase(backend)) {
                throw new IllegalArgumentException(prefix + ".backend must be placeholder or pjsua2: " + backend);
            }
            require(prefix + ".bindAddress", bindAddress);
            requireRange(prefix + ".port", port, 1, 65535);
            requireEquals(prefix + ".transport", transport, "UDP");
            requireEquals(prefix + ".ipVersion", ipVersion, "IPv4");
            requireSupportedCodec(prefix + ".codec", codec);
            requireSupportedCodec(prefix + ".preferredCodec", preferredCodec);
            if (codecs == null || codecs.isEmpty()) {
                throw new IllegalArgumentException(prefix + ".codecs must include at least one codec");
            }
            for (String supportedCodec : codecs) {
                requireSupportedCodec(prefix + ".codecs", supportedCodec);
            }
            if (!codecs.contains(preferredCodec)) {
                throw new IllegalArgumentException(
                        prefix + ".preferredCodec must be included in " + prefix + ".codecs: " + preferredCodec);
            }
            requireRange(prefix + ".rtpPortStart", rtpPortStart, 1024, 65534);
            requireRange(prefix + ".rtpPortEnd", rtpPortEnd, 1024, 65534);
            requireEven(prefix + ".rtpPortStart", rtpPortStart);
            requireEven(prefix + ".rtpPortEnd", rtpPortEnd);
            if (rtpPortStart >= rtpPortEnd) {
                throw new IllegalArgumentException(
                        prefix + ".rtpPortStart must be smaller than " + prefix + ".rtpPortEnd: "
                                + rtpPortStart + " >= " + rtpPortEnd);
            }
        }
    }

    public record RegistrationConfig(
            String domain,
            String userName,
            String password,
            String sipAddress,
            String registryServerAddress,
            int registryServerPort
    ) {
        public void validate() {
            validate("registration");
        }

        public void validate(String prefix) {
            require(prefix + ".domain", domain);
            require(prefix + ".userName", userName);
            require(prefix + ".password", password);
            require(prefix + ".sipAddress", sipAddress);
            require(prefix + ".registryServerAddress", registryServerAddress);
            requireRange(prefix + ".registryServerPort", registryServerPort, 1, 65535);
        }
    }

    public record OpenAiConfig(
            String apiKey,
            String realtimeModel,
            String voice,
            String maxOutputTokens,
            String turnDetectionType,
            String turnDetectionEagerness,
            boolean transcriptLoggingEnabled,
            String inputTranscriptionModel,
            String inputTranscriptionLanguage
    ) {
        public void validate() {
            validate("openai");
        }

        public void validate(String prefix) {
            require(prefix + ".apiKey", apiKey);
            require(prefix + ".realtimeModel", realtimeModel);
            require(prefix + ".voice", voice);
            if (!SUPPORTED_REALTIME_VOICES.contains(voice.toLowerCase())) {
                throw new IllegalArgumentException(
                        prefix + ".voice must be one of: "
                                + String.join(", ", SUPPORTED_REALTIME_VOICES) + ": " + voice);
            }
            requireMaxOutputTokens(prefix + ".maxOutputTokens", maxOutputTokens);
            require(prefix + ".turnDetectionType", turnDetectionType);
            if (!"server_vad".equalsIgnoreCase(turnDetectionType)
                    && !"semantic_vad".equalsIgnoreCase(turnDetectionType)) {
                throw new IllegalArgumentException(
                        prefix + ".turnDetectionType must be server_vad or semantic_vad: " + turnDetectionType);
            }
            require(prefix + ".turnDetectionEagerness", turnDetectionEagerness);
            require(prefix + ".inputTranscriptionModel", inputTranscriptionModel);
        }
    }

    public record BotConfig(String systemInstructions, String initialGreeting) {
        public void validate() {
            validate("bot");
        }

        public void validate(String prefix) {
            require(prefix + ".systemInstructions", systemInstructions);
            require(prefix + ".initialGreeting", initialGreeting);
        }
    }

    public record LoggingConfig(String level) {
        public void validate() {
            require("logging.level", level);
            if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR").contains(level.toUpperCase())) {
                throw new IllegalArgumentException(
                        "logging.level must be TRACE, DEBUG, INFO, WARN, WARNING, or ERROR: " + level);
            }
        }
    }

    public record MediaConfig(int inboundQueueCapacity, int outboundQueueCapacity) {
        public void validate() {
            requireRange("media.inboundQueueCapacity", inboundQueueCapacity, 1, 100000);
            requireRange("media.outboundQueueCapacity", outboundQueueCapacity, 1, 100000);
        }
    }

    public record OpenAiRuntimeConfig(
            boolean cancelResponseOnUserSpeech,
            int bargeInMinSpeechMs,
            double bargeInMinRmsDb,
            int bargeInGraceMsAfterAssistantStarts
    ) {
        public void validate() {
            requireRange("openai.bargeInMinSpeechMs", bargeInMinSpeechMs, 0, 5000);
            if (bargeInMinRmsDb < -100.0 || bargeInMinRmsDb > 0.0) {
                throw new IllegalArgumentException(
                        "openai.bargeInMinRmsDb must be between -100.0 and 0.0: " + bargeInMinRmsDb);
            }
            requireRange("openai.bargeInGraceMsAfterAssistantStarts", bargeInGraceMsAfterAssistantStarts, 0, 5000);
        }
    }

    public record MonitorConfig(boolean enabled, String bindAddress, int port, int maxEvents, int sessionHistoryDepth) {
        public void validate() {
            require("monitor.bindAddress", bindAddress);
            requireRange("monitor.port", port, 1, 65535);
            requireRange("monitor.maxEvents", maxEvents, 1, 10000);
            requireRange("monitor.sessionHistoryDepth", sessionHistoryDepth, 1, 1000);
        }
    }

    public void validate() {
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("gateway.sessionIds must include at least one session slot");
        }
        Set<String> slotIds = new java.util.HashSet<>();
        Set<Integer> sipPorts = new java.util.HashSet<>();
        String backend = null;
        for (SessionSlotConfig session : sessions) {
            session.validate();
            if (!slotIds.add(session.slotId())) {
                throw new IllegalArgumentException("Duplicate session slot id: " + session.slotId());
            }
            if (backend == null) {
                backend = session.sip().backend().toLowerCase(Locale.ROOT);
            } else if (!backend.equals(session.sip().backend().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("All session slots must use the same sip.backend");
            }
            if (!sipPorts.add(session.sip().port())) {
                throw new IllegalArgumentException("Duplicate SIP port across session slots: " + session.sip().port());
            }
        }
        media.validate();
        openAi.validate();
        logging.validate();
        monitor.validate();
    }

    private static void require(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config: " + key);
        }
    }

    private static void requireEquals(String key, String value, String expected) {
        require(key, value);
        if (!expected.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be " + expected + ": " + value);
        }
    }

    private static void requireSupportedCodec(String key, String value) {
        require(key, value);
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Set.of(CodecConfig.G722, CodecConfig.PCMU).contains(normalized)) {
            throw new IllegalArgumentException(key + " must be G722 or PCMU: " + value);
        }
    }

    private static void requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ": " + value);
        }
    }

    private static void requireEven(String key, int value) {
        if (value % 2 != 0) {
            throw new IllegalArgumentException(key + " must be even: " + value);
        }
    }

    private static void requireMaxOutputTokens(String key, String value) {
        require(key, value);
        if ("inf".equalsIgnoreCase(value)) {
            return;
        }
        try {
            requireRange(key, Integer.parseInt(value), 1, 4096);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    key + " must be an integer between 1 and 4096 or inf: " + value);
        }
    }
}
