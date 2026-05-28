package com.example.telephonygw.config;

public record GatewayConfig(
        SipConfig sip,
        RegistrationConfig registration,
        OpenAiConfig openAi,
        BotConfig bot,
        LoggingConfig logging
) {
    public record SipConfig(
            String backend,
            String bindAddress,
            int port,
            String transport,
            String ipVersion,
            String codec,
            String publicContactAddress
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
            requireEquals("sip.codec", codec, "PCMU");
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

    public record OpenAiConfig(String apiKey, String realtimeModel) {
        public void validate() {
            require("openai.apiKey", apiKey);
            require("openai.realtimeModel", realtimeModel);
        }
    }

    public record BotConfig(String systemInstructions) {
        public void validate() {
            require("bot.systemInstructions", systemInstructions);
        }
    }

    public record LoggingConfig(String level) {
        public void validate() {
            require("logging.level", level);
        }
    }

    public void validate() {
        sip.validate();
        registration.validate();
        openAi.validate();
        bot.validate();
        logging.validate();
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

    private static void requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ": " + value);
        }
    }
}
