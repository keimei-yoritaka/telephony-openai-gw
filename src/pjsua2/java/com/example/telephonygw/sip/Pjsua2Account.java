package com.example.telephonygw.sip;

import com.example.telephonygw.session.CallSession;
import com.example.telephonygw.session.CallSessionManager;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.logging.GatewayEventLogger;
import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.pjsip_status_code;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Pjsua2Account extends Account {
    private static final System.Logger LOG = System.getLogger(Pjsua2Account.class.getName());
    private static final Pattern RECEIVED_PATTERN = Pattern.compile("(?i)(?:^|;)\\s*received=([^;\\s]+)");
    private static final Pattern RPORT_PATTERN = Pattern.compile("(?i)(?:^|;)\\s*rport=([0-9]+)");

    private final String slotId;
    private final CallSessionManager sessionManager;
    private final AudioBridge audioBridge;
    private final RegistrationAddressObserver registrationAddressObserver;
    private final Map<Integer, Pjsua2Call> activeCalls = new ConcurrentHashMap<>();

    public Pjsua2Account(
            String slotId,
            CallSessionManager sessionManager,
            AudioBridge audioBridge,
            RegistrationAddressObserver registrationAddressObserver
    ) {
        super();
        this.slotId = slotId;
        this.sessionManager = sessionManager;
        this.audioBridge = audioBridge;
        this.registrationAddressObserver = registrationAddressObserver;
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        LOG.log(System.Logger.Level.INFO,
                "SIP Registration state changed: slotId={0}, code={1}, reason={2}, expiration={3}",
                slotId, prm.getCode(), prm.getReason(), prm.getExpiration());
        GatewayEventLogger.info(LOG, "sip_registration_state",
                "slotId", slotId,
                "code", prm.getCode(),
                "reason", prm.getReason(),
                "expiration", prm.getExpiration());
        if (prm.getCode() >= 200 && prm.getCode() < 300) {
            extractReflexiveAddress(prm).ifPresent(address ->
                    registrationAddressObserver.onRegistrationReflexiveAddressDetected(
                            slotId, address.publicAddress(), address.publicPort()));
        }
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
        int callId = prm.getCallId();
        LOG.log(System.Logger.Level.INFO,
                "Incoming SIP INVITE received: slotId={0}, callId={1}",
                slotId, callId);
        GatewayEventLogger.info(LOG, "sip_invite_received",
                "slotId", slotId,
                "callId", callId,
                "activeCalls", activeCalls.size());

        if (!activeCalls.isEmpty()) {
            answerBusy(callId);
            return;
        }

        CallSession session = sessionManager.createSession(slotId);
        Pjsua2Call call = new Pjsua2Call(
                this, callId, session.sessionId(), slotId, activeCalls, sessionManager, audioBridge);
        activeCalls.put(callId, call);

        try {
            CallOpParam answer = new CallOpParam(true);
            answer.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(answer);
            LOG.log(System.Logger.Level.INFO,
                    "Answered incoming SIP call: slotId={0}, callId={1}, sessionId={2}",
                    slotId, callId, session.sessionId());
            GatewayEventLogger.info(LOG, "sip_call_answered",
                "sessionId", session.sessionId(),
                    "slotId", slotId,
                    "callId", callId,
                    "status", 200);
        } catch (Exception e) {
            activeCalls.remove(callId);
            sessionManager.closeSession(session.sessionId(), "answer_failed");
            LOG.log(System.Logger.Level.ERROR, "Failed to answer incoming SIP call: " + e.getMessage(), e);
            GatewayEventLogger.warning(LOG, "sip_call_answer_failed",
                    "sessionId", session.sessionId(),
                    "slotId", slotId,
                    "callId", callId,
                    "error", e.getMessage());
        }
    }

    private void answerBusy(int callId) {
        Pjsua2Call call = new Pjsua2Call(this, callId, null, slotId, activeCalls, sessionManager, audioBridge);
        try {
            CallOpParam answer = new CallOpParam(true);
            answer.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
            call.answer(answer);
            LOG.log(System.Logger.Level.INFO,
                    "Rejected incoming SIP call as busy: slotId={0}, callId={1}",
                    slotId, callId);
            GatewayEventLogger.info(LOG, "sip_call_rejected_busy",
                    "slotId", slotId,
                    "callId", callId,
                    "activeCalls", activeCalls.size());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Failed to reject incoming SIP call: " + e.getMessage(), e);
            GatewayEventLogger.warning(LOG, "sip_call_reject_failed",
                    "slotId", slotId,
                    "callId", callId,
                    "error", e.getMessage());
        } finally {
            call.delete();
        }
    }

    private static Optional<ReflexiveAddress> extractReflexiveAddress(OnRegStateParam prm) {
        try {
            String message = prm.getRdata().getWholeMsg();
            if (message == null || message.isBlank()) {
                return Optional.empty();
            }
            if (!isSuccessfulSipResponse(message)) {
                return Optional.empty();
            }
            String via = firstHeaderValue(message, "Via")
                    .or(() -> firstHeaderValue(message, "V"))
                    .orElse("");
            Matcher received = RECEIVED_PATTERN.matcher(via);
            if (!received.find()) {
                return Optional.empty();
            }
            Matcher rport = RPORT_PATTERN.matcher(via);
            int publicPort = rport.find() ? Integer.parseInt(rport.group(1)) : -1;
            return Optional.of(new ReflexiveAddress(received.group(1), publicPort));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to parse SIP Registration reflexive address from rport/received: {0}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> firstHeaderValue(String message, String headerName) {
        String prefix = headerName.toLowerCase() + ":";
        for (String line : message.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith(prefix)) {
                return Optional.of(trimmed.substring(headerName.length() + 1).trim());
            }
        }
        return Optional.empty();
    }

    private static boolean isSuccessfulSipResponse(String message) {
        String firstLine = message.lines().findFirst().orElse("").trim();
        if (!firstLine.startsWith("SIP/2.0 ")) {
            return false;
        }
        String[] parts = firstLine.split("\\s+", 3);
        if (parts.length < 2) {
            return false;
        }
        try {
            int statusCode = Integer.parseInt(parts[1]);
            return statusCode >= 200 && statusCode < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private record ReflexiveAddress(String publicAddress, int publicPort) {
    }
}
