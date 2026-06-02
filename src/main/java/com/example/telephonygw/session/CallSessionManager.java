package com.example.telephonygw.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class CallSessionManager {
    private static final System.Logger LOG = System.getLogger(CallSessionManager.class.getName());

    private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<String, String>> closeListeners = new CopyOnWriteArrayList<>();

    public CallSession createSession() {
        String id = UUID.randomUUID().toString();
        CallSession session = new CallSession(id);
        session.activate();
        sessions.put(id, session);
        LOG.log(System.Logger.Level.INFO, "Created call session {0}", id);
        return session;
    }

    public void closeSession(String sessionId, String reason) {
        CallSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close(reason);
            LOG.log(System.Logger.Level.INFO, "Closed call session {0}: {1}", sessionId, reason);
            notifyCloseListeners(sessionId, reason);
        }
    }

    public void addCloseListener(BiConsumer<String, String> listener) {
        closeListeners.add(listener);
    }

    public void closeAll(String reason) {
        for (String sessionId : sessions.keySet()) {
            closeSession(sessionId, reason);
        }
    }

    private void notifyCloseListeners(String sessionId, String reason) {
        for (BiConsumer<String, String> listener : closeListeners) {
            try {
                listener.accept(sessionId, reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Call session close listener failed: sessionId={0}, reason={1}, error={2}",
                        sessionId, reason, e.getMessage());
            }
        }
    }
}
