package com.example.telephonygw.sip;

import com.example.telephonygw.config.GatewayConfig.RegistrationConfig;
import com.example.telephonygw.config.GatewayConfig.LoggingConfig;
import com.example.telephonygw.config.GatewayConfig.SipConfig;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.session.CallSessionManager;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PjsipEndpoint {
    private final SipEndpointAdapter adapter;

    public PjsipEndpoint(
            SipConfig sipConfig,
            RegistrationConfig registrationConfig,
            LoggingConfig loggingConfig,
            CallSessionManager sessionManager,
            AudioBridge audioBridge
    ) {
        if ("pjsua2".equalsIgnoreCase(sipConfig.backend())) {
            this.adapter = new Pjsua2SipEndpoint(sipConfig, registrationConfig, loggingConfig, sessionManager, audioBridge);
        } else {
            this.adapter = new PlaceholderSipEndpoint(sipConfig, registrationConfig, sessionManager);
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

    private final SipConfig sipConfig;
    private final RegistrationConfig registrationConfig;
    private final CallSessionManager sessionManager;
    private final AtomicBoolean started = new AtomicBoolean(false);

    PlaceholderSipEndpoint(
            SipConfig sipConfig,
            RegistrationConfig registrationConfig,
            CallSessionManager sessionManager
    ) {
        this.sipConfig = sipConfig;
        this.registrationConfig = registrationConfig;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO,
                "Starting placeholder PJSIP endpoint on {0}:{1}/{2} {3}",
                sipConfig.bindAddress(), sipConfig.port(), sipConfig.transport(), sipConfig.ipVersion());
        LOG.log(System.Logger.Level.INFO,
                "Codec policy is fixed to {0}", sipConfig.codec());
    }

    @Override
    public void register() {
        ensureStarted();
        LOG.log(System.Logger.Level.INFO,
                "Registering SIP address {0} to {1}:{2} for domain {3}",
                registrationConfig.sipAddress(),
                registrationConfig.registryServerAddress(),
                registrationConfig.registryServerPort(),
                registrationConfig.domain());
        LOG.log(System.Logger.Level.INFO,
                "SIP Registration is placeholder until PJSUA2 Java binding is wired");
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        sessionManager.closeAll("sip_endpoint_stop");
        LOG.log(System.Logger.Level.INFO, "Stopped placeholder PJSIP endpoint");
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("PJSIP endpoint is not started");
        }
    }
}
