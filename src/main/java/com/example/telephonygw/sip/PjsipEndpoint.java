package com.example.telephonygw.sip;

import com.example.telephonygw.config.GatewayConfig.RegistrationConfig;
import com.example.telephonygw.config.GatewayConfig.SipConfig;
import com.example.telephonygw.session.CallSessionManager;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PjsipEndpoint {
    private static final System.Logger LOG = System.getLogger(PjsipEndpoint.class.getName());

    private final SipConfig sipConfig;
    private final RegistrationConfig registrationConfig;
    private final CallSessionManager sessionManager;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public PjsipEndpoint(
            SipConfig sipConfig,
            RegistrationConfig registrationConfig,
            CallSessionManager sessionManager
    ) {
        this.sipConfig = sipConfig;
        this.registrationConfig = registrationConfig;
        this.sessionManager = sessionManager;
    }

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

