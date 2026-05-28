package com.example.telephonygw.openai;

import com.example.telephonygw.config.GatewayConfig.OpenAiConfig;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RealtimeClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RealtimeClient.class.getName());

    private final OpenAiConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public RealtimeClient(OpenAiConfig config) {
        this.config = config;
    }

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO,
                    "Initialized placeholder OpenAI Realtime client for model {0}",
                    config.realtimeModel());
        }
    }

    public RealtimeSession openSession(String callSessionId) {
        if (!initialized.get()) {
            throw new IllegalStateException("Realtime client is not initialized");
        }
        return new RealtimeSession(callSessionId, config.realtimeModel());
    }

    @Override
    public void close() {
        if (initialized.compareAndSet(true, false)) {
            LOG.log(System.Logger.Level.INFO, "Closed placeholder OpenAI Realtime client");
        }
    }
}

