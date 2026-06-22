package com.example.telephonygw.app;

import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.openai.RealtimeClient;
import com.example.telephonygw.session.CallSessionManager;
import com.example.telephonygw.sip.PjsipEndpoint;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GatewayApp {
    private static final System.Logger LOG = System.getLogger(GatewayApp.class.getName());

    private final GatewayConfig config;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CallSessionManager sessionManager;
    private final AudioBridge audioBridge;
    private final RealtimeClient realtimeClient;
    private final PjsipEndpoint sipEndpoint;

    public GatewayApp(GatewayConfig config) {
        this.config = config;
        this.sessionManager = new CallSessionManager();
        this.audioBridge = new AudioBridge();
        this.realtimeClient = new RealtimeClient(
                config.openAi(),
                config.bot().systemInstructions(),
                config.bot().initialGreeting(),
                audioBridge);
        this.sessionManager.addCreateListener(realtimeClient::startSession);
        this.sessionManager.addCloseListener(realtimeClient::closeSession);
        this.sipEndpoint = new PjsipEndpoint(
                config.sip(),
                config.registration(),
                config.logging(),
                sessionManager,
                audioBridge);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Starting Telephony OpenAI Gateway");
        GatewayEventLogger.info(LOG, "gateway_starting",
                "sipBackend", config.sip().backend(),
                "sipBind", config.sip().bindAddress(),
                "sipPort", config.sip().port(),
                "sipTransport", config.sip().transport(),
                "openaiModel", config.openAi().realtimeModel(),
                "voice", config.openAi().voice());
        LOG.log(System.Logger.Level.INFO, "Configured SIP endpoint {0}:{1}/{2}",
                config.sip().bindAddress(), config.sip().port(), config.sip().transport());

        realtimeClient.initialize();
        audioBridge.initialize();
        realtimeClient.startAudioForwarding(audioBridge.inboundQueue());
        sipEndpoint.start();
        sipEndpoint.register();

        LOG.log(System.Logger.Level.INFO, "Gateway started");
        GatewayEventLogger.info(LOG, "gateway_started");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Stopping Telephony OpenAI Gateway");
        GatewayEventLogger.info(LOG, "gateway_stopping");
        sipEndpoint.stop();
        realtimeClient.close();
        audioBridge.stop();
        sessionManager.closeAll("gateway_shutdown");
        shutdownLatch.countDown();
        LOG.log(System.Logger.Level.INFO, "Gateway stopped");
        GatewayEventLogger.info(LOG, "gateway_stopped");
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }
}
