package com.example.telephonygw.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GatewayConfigLoader {
    private GatewayConfigLoader() {
    }

    public static GatewayConfig load(Path path) throws IOException {
        Map<String, String> values = parseSimpleYaml(path);
        List<GatewayConfig.SessionSlotConfig> sessions = new ArrayList<>();
        for (String slotId : stringListValue(values, "gateway.sessionIds", "")) {
            sessions.add(sessionSlot(values, slotId));
        }
        GatewayConfig config = new GatewayConfig(
                List.copyOf(sessions),
                new GatewayConfig.MediaConfig(
                        intValue(values, "media.inboundQueueCapacity", 500),
                        intValue(values, "media.outboundQueueCapacity", 10000)
                ),
                new GatewayConfig.LoggingConfig(value(values, "logging.level")),
                new GatewayConfig.MonitorConfig(
                        booleanValue(values, "monitor.enabled", false),
                        optionalValue(values, "monitor.bindAddress", "127.0.0.1"),
                        intValue(values, "monitor.port", 8080),
                        intValue(values, "monitor.maxEvents", 500),
                        intValue(values, "monitor.sessionHistoryDepth", 10)
                )
        );
        config.validate();
        return config;
    }

    private static GatewayConfig.SessionSlotConfig sessionSlot(Map<String, String> values, String slotId) {
        String prefix = "session." + slotId + ".";
        String codec = optionalValue(values, prefix + "sip.codec", "PCMU").toUpperCase(Locale.ROOT);
        return new GatewayConfig.SessionSlotConfig(
                slotId,
                new GatewayConfig.SipConfig(
                        value(values, prefix + "sip.backend"),
                        value(values, prefix + "sip.bindAddress"),
                        intValue(values, prefix + "sip.port"),
                        value(values, prefix + "sip.transport"),
                        value(values, prefix + "sip.ipVersion"),
                        codec,
                        optionalValue(values, prefix + "sip.preferredCodec", codec).toUpperCase(Locale.ROOT),
                        listValue(values, prefix + "sip.codecs", codec),
                        optionalValue(values, prefix + "sip.publicContactAddress"),
                        intValue(values, prefix + "sip.rtpPortStart", 40000),
                        intValue(values, prefix + "sip.rtpPortEnd", 41000)
                ),
                new GatewayConfig.RegistrationConfig(
                        value(values, prefix + "registration.domain"),
                        value(values, prefix + "registration.userName"),
                        value(values, prefix + "registration.password"),
                        value(values, prefix + "registration.sipAddress"),
                        value(values, prefix + "registration.registryServerAddress"),
                        intValue(values, prefix + "registration.registryServerPort")
                ),
                new GatewayConfig.OpenAiConfig(
                        value(values, prefix + "openai.apiKey"),
                        value(values, prefix + "openai.realtimeModel"),
                        optionalValue(values, prefix + "openai.voice", "shimmer"),
                        optionalValue(values, prefix + "openai.maxOutputTokens", "inf"),
                        optionalValue(values, prefix + "openai.turnDetectionType", "semantic_vad"),
                        optionalValue(values, prefix + "openai.turnDetectionEagerness", "low"),
                        booleanValue(values, prefix + "openai.transcriptLoggingEnabled", true),
                        optionalValue(values, prefix + "openai.inputTranscriptionModel", "gpt-realtime-whisper"),
                        optionalValue(values, prefix + "openai.inputTranscriptionLanguage", "ja")
                ),
                new GatewayConfig.BotConfig(
                        value(values, prefix + "bot.systemInstructions"),
                        optionalValue(values, prefix + "bot.initialGreeting", "こちらはAI電話受付です。ご用件をお話しください。")
                )
        );
    }

    private static Map<String, String> parseSimpleYaml(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        String section = "";

        for (String rawLine : Files.readAllLines(path)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }

            if (!line.startsWith(" ") && line.endsWith(":")) {
                section = line.substring(0, line.length() - 1).trim();
                continue;
            }

            String trimmed = line.trim();
            int separator = trimmed.indexOf(':');
            if (separator < 0 || section.isBlank()) {
                throw new IllegalArgumentException("Unsupported config line: " + rawLine);
            }

            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            values.put(section + "." + key, resolveValue(unquote(value)));
        }

        return values;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String resolveValue(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            String envName = value.substring(2, value.length() - 1);
            String envValue = System.getenv(envName);
            return envValue == null ? value : envValue;
        }
        return value;
    }

    private static String value(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required config: " + key);
        }
        return value;
    }

    private static String optionalValue(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private static String optionalValue(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intValue(Map<String, String> values, String key) {
        try {
            return Integer.parseInt(value(values, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static int intValue(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static List<String> listValue(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            value = defaultValue;
        }
        List<String> entries = new ArrayList<>();
        for (String rawEntry : value.split(",")) {
            String entry = rawEntry.trim();
            if (!entry.isBlank()) {
                entries.add(entry.toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(entries);
    }

    private static List<String> stringListValue(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            value = defaultValue;
        }
        List<String> entries = new ArrayList<>();
        for (String rawEntry : value.split(",")) {
            String entry = rawEntry.trim();
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static boolean booleanValue(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
