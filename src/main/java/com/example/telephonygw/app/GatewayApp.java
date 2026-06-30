package com.example.telephonygw.app;

import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.monitor.ConversationEventHub;
import com.example.telephonygw.monitor.ConversationMonitorServer;
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
    private final ConversationEventHub conversationEventHub;
    private final ConversationMonitorServer conversationMonitorServer;
    private final RealtimeClient realtimeClient;
    private final PjsipEndpoint sipEndpoint;

    public GatewayApp(GatewayConfig config) {
        this.config = config;
        this.sessionManager = new CallSessionManager(config.monitor().sessionHistoryDepth());
        this.audioBridge = new AudioBridge(config.media());
        this.conversationEventHub = new ConversationEventHub(config.monitor().maxEvents());
        this.conversationMonitorServer = new ConversationMonitorServer(
                config.monitor(),
                conversationEventHub,
                sessionManager,
                config.sessions());
        this.realtimeClient = new RealtimeClient(
                config.sessions(),
                audioBridge,
                conversationEventHub);
        this.sessionManager.addCreateListener(realtimeClient::startSession);
        this.sessionManager.addCloseListener(realtimeClient::closeSession);
        this.sipEndpoint = new PjsipEndpoint(config.sessions(), config.logging(), sessionManager, audioBridge);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Starting Telephony OpenAI Gateway");
        GatewayEventLogger.info(LOG, "gateway_starting",
                "sessionSlots", config.sessions().size());
        for (GatewayConfig.SessionSlotConfig sessionSlot : config.sessions()) {
            LOG.log(System.Logger.Level.INFO,
                    "Configured session slot {0} ({1}): sipEndpoint={2}:{3}/{4}, openaiModel={5}, voice={6}",
                    sessionSlot.slotId(),
                    sessionSlot.name(),
                    sessionSlot.sip().bindAddress(),
                    sessionSlot.sip().port(),
                    sessionSlot.sip().transport(),
                    sessionSlot.openAi().realtimeModel(),
                    sessionSlot.openAi().voice());
        }

        conversationMonitorServer.start();
        realtimeClient.initialize();
        audioBridge.initialize();
        realtimeClient.startAudioForwarding();
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
        conversationMonitorServer.close();
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
