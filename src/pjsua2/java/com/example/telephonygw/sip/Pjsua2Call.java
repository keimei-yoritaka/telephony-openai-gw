package com.example.telephonygw.sip;

import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.logging.GatewayEventLogger;
import com.example.telephonygw.session.CallSessionManager;
import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsua_call_media_status;

import java.util.Map;

final class Pjsua2Call extends Call {
    private static final System.Logger LOG = System.getLogger(Pjsua2Call.class.getName());

    private final String sessionId;
    private final Map<Integer, Pjsua2Call> activeCalls;
    private final CallSessionManager sessionManager;
    private final AudioBridge audioBridge;
    private Pjsua2AudioBridgePort audioBridgePort;
    private AudioMedia callAudioMedia;

    Pjsua2Call(
            Account account,
            int callId,
            String sessionId,
            Map<Integer, Pjsua2Call> activeCalls,
            CallSessionManager sessionManager,
            AudioBridge audioBridge
    ) {
        super(account, callId);
        this.sessionId = sessionId;
        this.activeCalls = activeCalls;
        this.sessionManager = sessionManager;
        this.audioBridge = audioBridge;
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        try {
            CallInfo info = getInfo();
            LOG.log(System.Logger.Level.INFO,
                    "SIP call state changed: callId={0}, state={1}, status={2}, reason={3}",
                    info.getId(), info.getStateText(), info.getLastStatusCode(), info.getLastReason());
            GatewayEventLogger.info(LOG, "sip_call_state",
                    "sessionId", sessionId,
                    "callId", info.getId(),
                    "state", info.getStateText(),
                    "status", info.getLastStatusCode(),
                    "reason", info.getLastReason());

            if (info.getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                closeAudioBridge();
                activeCalls.remove(info.getId());
                if (sessionId != null) {
                    sessionManager.closeSession(sessionId, "sip_call_disconnected");
                }
                delete();
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to process SIP call state: {0}", e.getMessage());
        }
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        try {
            CallInfo info = getInfo();
            LOG.log(System.Logger.Level.INFO,
                    "SIP call media state changed: callId={0}, mediaCount={1}",
                    info.getId(), info.getMedia().size());
            GatewayEventLogger.info(LOG, "sip_call_media_state",
                    "sessionId", sessionId,
                    "callId", info.getId(),
                    "mediaCount", info.getMedia().size());

            for (int i = 0; i < info.getMedia().size(); i++) {
                CallMediaInfo mediaInfo = info.getMedia().get(i);
                if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO
                        && mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    attachAudioBridge(i);
                    return;
                }
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to process SIP media state: {0}", e.getMessage());
        }
    }

    private void attachAudioBridge(int mediaIndex) throws Exception {
        if (audioBridgePort != null) {
            return;
        }
        if (sessionId == null) {
            LOG.log(System.Logger.Level.WARNING, "Skipping audio bridge for call without sessionId");
            return;
        }

        callAudioMedia = getAudioMedia(mediaIndex);
        audioBridgePort = new Pjsua2AudioBridgePort(sessionId, getId(), audioBridge);
        callAudioMedia.startTransmit(audioBridgePort);
        audioBridgePort.startTransmit(callAudioMedia);
        LOG.log(System.Logger.Level.INFO,
                "Attached PJSUA2 audio bridge: callId={0}, sessionId={1}, mediaIndex={2}",
                getId(), sessionId, mediaIndex);
        GatewayEventLogger.info(LOG, "rtp_audio_bridge_attached",
                "sessionId", sessionId,
                "callId", getId(),
                "mediaIndex", mediaIndex);
    }

    private void closeAudioBridge() {
        if (audioBridgePort == null) {
            return;
        }
        try {
            if (callAudioMedia != null) {
                callAudioMedia.stopTransmit(audioBridgePort);
                audioBridgePort.stopTransmit(callAudioMedia);
            }
        } catch (Exception e) {
            if (isAlreadyDisconnected(e)) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Audio bridge transmission was already disconnected: {0}",
                        e.getMessage());
            } else {
                LOG.log(System.Logger.Level.WARNING, "Failed to stop audio bridge transmission: {0}", e.getMessage());
            }
        }

        long inboundFrameCount = audioBridgePort.inboundFrameCount();
        long outboundFrameCount = audioBridgePort.outboundFrameCount();
        long outboundSilenceFrameCount = audioBridgePort.outboundSilenceFrameCount();
        long elapsedMillis = audioBridgePort.elapsedMillis();
        try {
            audioBridgePort.delete();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to delete audio bridge port: {0}", e.getMessage());
        } finally {
            audioBridgePort = null;
            callAudioMedia = null;
        }
        LOG.log(System.Logger.Level.INFO,
                "Closed PJSUA2 audio bridge: callId={0}, sessionId={1}, inboundFrames={2}, outboundFrames={3}, outboundSilenceFrames={4}, elapsedMs={5}",
                getId(), sessionId, inboundFrameCount, outboundFrameCount, outboundSilenceFrameCount, elapsedMillis);
        GatewayEventLogger.info(LOG, "rtp_audio_bridge_closed",
                "sessionId", sessionId,
                "callId", getId(),
                "inboundFrames", inboundFrameCount,
                "outboundFrames", outboundFrameCount,
                "outboundSilenceFrames", outboundSilenceFrameCount,
                "elapsedMs", elapsedMillis);
    }

    private static boolean isAlreadyDisconnected(Exception e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("PJ_EINVAL")
                || message.contains("Invalid value or argument")
                || message.contains("pjsua_conf_disconnect"));
    }
}
