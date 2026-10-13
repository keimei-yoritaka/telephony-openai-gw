package com.example.telephonygw.session;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CallSession {
    public enum State {
        NEW,
        ACTIVE,
        CLOSING,
        CLOSED
    }

    private final String sessionId;
    private final String slotId;
    private final Instant startedAt;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile Instant endedAt;
    private volatile String terminationReason;

    public CallSession(String sessionId, String slotId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.slotId = Objects.requireNonNull(slotId, "slotId");
        this.startedAt = Instant.now();
    }

    public String sessionId() {
        return sessionId;
    }

    public String slotId() {
        return slotId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public State state() {
        return state.get();
    }

    public void activate() {
        state.compareAndSet(State.NEW, State.ACTIVE);
    }

    public void close(String reason) {
        State previous = state.getAndSet(State.CLOSED);
        if (previous != State.CLOSED) {
            this.endedAt = Instant.now();
            this.terminationReason = reason;
        }
    }

    public Instant endedAt() {
        return endedAt;
    }

    public String terminationReason() {
        return terminationReason;
    }
}
