package com.example.telephonygw.sip;

import com.example.telephonygw.config.GatewayConfig.LoggingConfig;
import com.example.telephonygw.config.GatewayConfig.SessionSlotConfig;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.session.CallSessionManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PjsipEndpoint {
    private final SipEndpointAdapter adapter;

    public PjsipEndpoint(
            List<SessionSlotConfig> sessionSlots,
            LoggingConfig loggingConfig,
            CallSessionManager sessionManager,
            AudioBridge audioBridge
    ) {
        boolean pjsua2 = sessionSlots.stream()
                .anyMatch(sessionSlot -> "pjsua2".equalsIgnoreCase(sessionSlot.sip().backend()));
        if (pjsua2) {
            this.adapter = new Pjsua2SipEndpoint(sessionSlots, loggingConfig, sessionManager, audioBridge);
        } else {
            this.adapter = new PlaceholderSipEndpoint(sessionSlots, sessionManager);
        }
    }

    public void start() {
        adapter.start();
    }

    public void register() {
        adapter.register();
    }

    public void stop() {
        adapter.stop();
    }
}

interface SipEndpointAdapter {
    void start();

    void register();

    void stop();
}

final class PlaceholderSipEndpoint implements SipEndpointAdapter {
    private static final System.Logger LOG = System.getLogger(PlaceholderSipEndpoint.class.getName());

    private final List<SessionSlotConfig> sessionSlots;
    private final CallSessionManager sessionManager;
    private final AtomicBoolean started = new AtomicBoolean(false);

    PlaceholderSipEndpoint(
            List<SessionSlotConfig> sessionSlots,
            CallSessionManager sessionManager
    ) {
        this.sessionSlots = List.copyOf(sessionSlots);
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        for (SessionSlotConfig sessionSlot : sessionSlots) {
            LOG.log(System.Logger.Level.INFO,
                    "Starting placeholder PJSIP endpoint for slot {0} on {1}:{2}/{3} {4}",
                    sessionSlot.slotId(),
                    sessionSlot.sip().bindAddress(),
                    sessionSlot.sip().port(),
                    sessionSlot.sip().transport(),
                    sessionSlot.sip().ipVersion());
            LOG.log(System.Logger.Level.INFO,
                    "Codec policy configured: slotId={0}, preferredCodec={1}, codecs={2}",
                    sessionSlot.slotId(),
                    sessionSlot.sip().preferredCodec(),
                    String.join(",", sessionSlot.sip().codecs()));
        }
    }

    @Override
    public void register() {
        ensureStarted();
        for (SessionSlotConfig sessionSlot : sessionSlots) {
            LOG.log(System.Logger.Level.INFO,
                    "Registering SIP address {0} to {1}:{2} for domain {3}: slotId={4}",
                    sessionSlot.registration().sipAddress(),
                    sessionSlot.registration().registryServerAddress(),
                    sessionSlot.registration().registryServerPort(),
                    sessionSlot.registration().domain(),
                    sessionSlot.slotId());
        }
        LOG.log(System.Logger.Level.INFO,
                "SIP Registration is placeholder until PJSUA2 Java binding is wired");
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        LOG.log(System.Logger.Level.INFO,
                "Stopped placeholder PJSIP endpoint");
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("PJSIP endpoint is not started");
        }
    }
}
