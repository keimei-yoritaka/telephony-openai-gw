package com.example.telephonygw.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GatewayConfigLoader {
    private GatewayConfigLoader() {
    }

    public static GatewayConfig load(Path path) throws IOException {
        Map<String, String> values = parseSimpleYaml(path);
        GatewayConfig config = new GatewayConfig(
                new GatewayConfig.SipConfig(
                        value(values, "sip.backend"),
                        value(values, "sip.bindAddress"),
                        intValue(values, "sip.port"),
                        value(values, "sip.transport"),
                        value(values, "sip.ipVersion"),
                        value(values, "sip.codec"),
                        optionalValue(values, "sip.publicContactAddress")
                ),
                new GatewayConfig.RegistrationConfig(
                        value(values, "registration.domain"),
                        value(values, "registration.userName"),
                        value(values, "registration.password"),
                        value(values, "registration.sipAddress"),
                        value(values, "registration.registryServerAddress"),
                        intValue(values, "registration.registryServerPort")
                ),
                new GatewayConfig.OpenAiConfig(
                        value(values, "openai.apiKey"),
                        value(values, "openai.realtimeModel"),
                        optionalValue(values, "openai.voice", "shimmer"),
                        optionalValue(values, "openai.maxOutputTokens", "inf"),
                        optionalValue(values, "openai.turnDetectionType", "semantic_vad"),
                        optionalValue(values, "openai.turnDetectionEagerness", "low")
                ),
                new GatewayConfig.BotConfig(
                        value(values, "bot.systemInstructions"),
                        optionalValue(values, "bot.initialGreeting", "こちらはAI電話受付です。ご用件をお話しください。")
                ),
                new GatewayConfig.LoggingConfig(value(values, "logging.level"))
        );
        config.validate();
        return config;
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
}
