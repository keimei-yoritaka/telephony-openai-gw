package com.example.telephonygw.soniox;

/** A sanitized failure that can cross the session factory boundary. */
public final class SonioxSessionException extends IllegalStateException {
    private final boolean retryable;

    public SonioxSessionException(boolean retryable) {
        super("Soniox session failed; inspect soniox_error event codes");
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
