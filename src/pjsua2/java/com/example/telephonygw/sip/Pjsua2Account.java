package com.example.telephonygw.sip;

import com.example.telephonygw.session.CallSession;
import com.example.telephonygw.session.CallSessionManager;
import com.example.telephonygw.media.AudioBridge;
import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.pjsip_status_code;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Pjsua2Account extends Account {
    private static final System.Logger LOG = System.getLogger(Pjsua2Account.class.getName());

    private final CallSessionManager sessionManager;
    private final AudioBridge audioBridge;
    private final Map<Integer, Pjsua2Call> activeCalls = new ConcurrentHashMap<>();

    public Pjsua2Account(CallSessionManager sessionManager, AudioBridge audioBridge) {
        super();
        this.sessionManager = sessionManager;
        this.audioBridge = audioBridge;
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        LOG.log(System.Logger.Level.INFO,
                "SIP Registration state changed: code={0}, reason={1}, expiration={2}",
                prm.getCode(), prm.getReason(), prm.getExpiration());
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
        int callId = prm.getCallId();
        LOG.log(System.Logger.Level.INFO, "Incoming SIP INVITE received: callId={0}", callId);

        if (!activeCalls.isEmpty()) {
            answerBusy(callId);
            return;
        }

        CallSession session = sessionManager.createSession();
        Pjsua2Call call = new Pjsua2Call(this, callId, session.sessionId(), activeCalls, sessionManager, audioBridge);
        activeCalls.put(callId, call);

        try {
            CallOpParam answer = new CallOpParam(true);
            answer.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(answer);
            LOG.log(System.Logger.Level.INFO,
                    "Answered incoming SIP call: callId={0}, sessionId={1}",
                    callId, session.sessionId());
        } catch (Exception e) {
            activeCalls.remove(callId);
            sessionManager.closeSession(session.sessionId(), "answer_failed");
            LOG.log(System.Logger.Level.ERROR, "Failed to answer incoming SIP call: " + e.getMessage(), e);
        }
    }

    private void answerBusy(int callId) {
        Pjsua2Call call = new Pjsua2Call(this, callId, null, activeCalls, sessionManager, audioBridge);
        try {
            CallOpParam answer = new CallOpParam(true);
            answer.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
            call.answer(answer);
            LOG.log(System.Logger.Level.INFO, "Rejected incoming SIP call as busy: callId={0}", callId);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Failed to reject incoming SIP call: " + e.getMessage(), e);
        } finally {
            call.delete();
        }
    }
}
