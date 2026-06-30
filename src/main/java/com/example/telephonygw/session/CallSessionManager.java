package com.example.telephonygw.session;

import com.example.telephonygw.logging.GatewayEventLogger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class CallSessionManager {
    private static final System.Logger LOG = System.getLogger(CallSessionManager.class.getName());

    private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SessionCreateListener> createListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SessionCloseListener> closeListeners = new CopyOnWriteArrayList<>();

    public CallSession createSession(String slotId) {
        String id = UUID.randomUUID().toString();
        CallSession session = new CallSession(id, slotId);
        session.activate();
        sessions.put(id, session);
        LOG.log(System.Logger.Level.INFO, "Created call session {0}: slotId={1}", id, slotId);
        GatewayEventLogger.info(LOG, "call_session_created",
                "sessionId", id,
                "slotId", slotId);
        notifyCreateListeners(id, slotId, "created");
        return session;
    }

    public void closeSession(String sessionId, String reason) {
        CallSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close(reason);
            LOG.log(System.Logger.Level.INFO,
                    "Closed call session {0}: slotId={1}, reason={2}",
                    sessionId, session.slotId(), reason);
            GatewayEventLogger.info(LOG, "call_session_closed",
                    "sessionId", sessionId,
                    "slotId", session.slotId(),
                    "reason", reason);
            notifyCloseListeners(sessionId, session.slotId(), reason);
        }
    }

    public void addCloseListener(SessionCloseListener listener) {
        closeListeners.add(listener);
    }

    public void addCreateListener(SessionCreateListener listener) {
        createListeners.add(listener);
    }

    public void closeAll(String reason) {
        for (String sessionId : sessions.keySet()) {
            closeSession(sessionId, reason);
        }
    }

    private void notifyCloseListeners(String sessionId, String slotId, String reason) {
        for (SessionCloseListener listener : closeListeners) {
            try {
                listener.onSessionClosed(sessionId, slotId, reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Call session close listener failed: sessionId={0}, slotId={1}, reason={2}, error={3}",
                        sessionId, slotId, reason, e.getMessage());
            }
        }
    }

    private void notifyCreateListeners(String sessionId, String slotId, String reason) {
        for (SessionCreateListener listener : createListeners) {
            try {
                listener.onSessionCreated(sessionId, slotId, reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Call session create listener failed: sessionId={0}, slotId={1}, reason={2}, error={3}",
                        sessionId, slotId, reason, e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface SessionCreateListener {
        void onSessionCreated(String sessionId, String slotId, String reason);
    }

    @FunctionalInterface
    public interface SessionCloseListener {
        void onSessionClosed(String sessionId, String slotId, String reason);
    }
}
