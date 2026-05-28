package com.example.telephonygw.media;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioBridge {
    private static final System.Logger LOG = System.getLogger(AudioBridge.class.getName());

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO, "Initialized placeholder audio bridge");
        }
    }

    public void stop() {
        if (initialized.compareAndSet(true, false)) {
            LOG.log(System.Logger.Level.INFO, "Stopped placeholder audio bridge");
        }
    }
}

