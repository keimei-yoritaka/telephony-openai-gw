package com.example.telephonygw.sip;

import com.example.telephonygw.session.CallSessionManager;
import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.pjsip_inv_state;

import java.util.Map;

final class Pjsua2Call extends Call {
    private static final System.Logger LOG = System.getLogger(Pjsua2Call.class.getName());

    private final String sessionId;
    private final Map<Integer, Pjsua2Call> activeCalls;
    private final CallSessionManager sessionManager;

    Pjsua2Call(
            Account account,
            int callId,
            String sessionId,
            Map<Integer, Pjsua2Call> activeCalls,
            CallSessionManager sessionManager
    ) {
        super(account, callId);
        this.sessionId = sessionId;
        this.activeCalls = activeCalls;
        this.sessionManager = sessionManager;
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        try {
            CallInfo info = getInfo();
            LOG.log(System.Logger.Level.INFO,
                    "SIP call state changed: callId={0}, state={1}, status={2}, reason={3}",
                    info.getId(), info.getStateText(), info.getLastStatusCode(), info.getLastReason());

            if (info.getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
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
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to process SIP media state: {0}", e.getMessage());
        }
    }
}
