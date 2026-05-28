package com.example.telephonygw.openai;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RealtimeSession implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RealtimeSession.class.getName());

    private final String callSessionId;
    private final String model;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public RealtimeSession(String callSessionId, String model) {
        this.callSessionId = callSessionId;
        this.model = model;
        LOG.log(System.Logger.Level.INFO,
                "Opened placeholder Realtime session for call {0} with model {1}",
                callSessionId, model);
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            LOG.log(System.Logger.Level.INFO,
                    "Closed placeholder Realtime session for call {0}",
                    callSessionId);
        }
    }
}

