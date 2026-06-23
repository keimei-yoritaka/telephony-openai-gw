package com.example.telephonygw.config;

import com.example.telephonygw.media.CodecConfig;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record GatewayConfig(
        SipConfig sip,
        RegistrationConfig registration,
        OpenAiConfig openAi,
        BotConfig bot,
        LoggingConfig logging,
        MonitorConfig monitor
) {
    private static final Set<String> SUPPORTED_REALTIME_VOICES = Set.of(
            "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar");

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
            require("sip.backend", backend);
            if (!"placeholder".equalsIgnoreCase(backend) && !"pjsua2".equalsIgnoreCase(backend)) {
                throw new IllegalArgumentException("sip.backend must be placeholder or pjsua2: " + backend);
            }
            require("sip.bindAddress", bindAddress);
            requireRange("sip.port", port, 1, 65535);
            requireEquals("sip.transport", transport, "UDP");
            requireEquals("sip.ipVersion", ipVersion, "IPv4");
            requireSupportedCodec("sip.codec", codec);
            requireSupportedCodec("sip.preferredCodec", preferredCodec);
            if (codecs == null || codecs.isEmpty()) {
                throw new IllegalArgumentException("sip.codecs must include at least one codec");
            }
            for (String supportedCodec : codecs) {
                requireSupportedCodec("sip.codecs", supportedCodec);
            }
            if (!codecs.contains(preferredCodec)) {
                throw new IllegalArgumentException(
                        "sip.preferredCodec must be included in sip.codecs: " + preferredCodec);
            }
            requireRange("sip.rtpPortStart", rtpPortStart, 1024, 65534);
            requireRange("sip.rtpPortEnd", rtpPortEnd, 1024, 65534);
            requireEven("sip.rtpPortStart", rtpPortStart);
            requireEven("sip.rtpPortEnd", rtpPortEnd);
            if (rtpPortStart >= rtpPortEnd) {
                throw new IllegalArgumentException(
                        "sip.rtpPortStart must be smaller than sip.rtpPortEnd: "
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
            require("registration.domain", domain);
            require("registration.userName", userName);
            require("registration.password", password);
            require("registration.sipAddress", sipAddress);
            require("registration.registryServerAddress", registryServerAddress);
            requireRange("registration.registryServerPort", registryServerPort, 1, 65535);
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
            require("openai.apiKey", apiKey);
            require("openai.realtimeModel", realtimeModel);
            require("openai.voice", voice);
            if (!SUPPORTED_REALTIME_VOICES.contains(voice.toLowerCase())) {
                throw new IllegalArgumentException(
                        "openai.voice must be one of: " + String.join(", ", SUPPORTED_REALTIME_VOICES) + ": " + voice);
            }
            requireMaxOutputTokens(maxOutputTokens);
            require("openai.turnDetectionType", turnDetectionType);
            if (!"server_vad".equalsIgnoreCase(turnDetectionType)
                    && !"semantic_vad".equalsIgnoreCase(turnDetectionType)) {
                throw new IllegalArgumentException(
                        "openai.turnDetectionType must be server_vad or semantic_vad: " + turnDetectionType);
            }
            require("openai.turnDetectionEagerness", turnDetectionEagerness);
            require("openai.inputTranscriptionModel", inputTranscriptionModel);
        }
    }

    public record BotConfig(String systemInstructions, String initialGreeting) {
        public void validate() {
            require("bot.systemInstructions", systemInstructions);
            require("bot.initialGreeting", initialGreeting);
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

    public record MonitorConfig(boolean enabled, String bindAddress, int port, int maxEvents) {
        public void validate() {
            require("monitor.bindAddress", bindAddress);
            requireRange("monitor.port", port, 1, 65535);
            requireRange("monitor.maxEvents", maxEvents, 1, 10000);
        }
    }

    public void validate() {
        sip.validate();
        registration.validate();
        openAi.validate();
        bot.validate();
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

    private static void requireMaxOutputTokens(String value) {
        require("openai.maxOutputTokens", value);
        if ("inf".equalsIgnoreCase(value)) {
            return;
        }
        try {
            requireRange("openai.maxOutputTokens", Integer.parseInt(value), 1, 4096);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "openai.maxOutputTokens must be an integer between 1 and 4096 or inf: " + value);
        }
    }
}
