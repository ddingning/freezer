package com.frostbyte.freezer.provider;

public final class ProviderStatus {
    public enum State {
        CHECKING,
        READY,
        NOT_AVAILABLE,
        NOT_INSTALLED,
        NOT_RUNNING,
        NOT_AUTHORIZED,
        DENIED,
        NOT_PAIRED,
        DISCONNECTED,
        ERROR,
        UNKNOWN
    }

    public final State state;
    public final String message;
    public final long checkedAt;

    public ProviderStatus(State state, String message) {
        this.state = state;
        this.message = message == null ? "" : message;
        this.checkedAt = System.currentTimeMillis();
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public static ProviderStatus checking() {
        return new ProviderStatus(State.CHECKING, "检测中…");
    }

    public static ProviderStatus ready(String message) {
        return new ProviderStatus(State.READY, message);
    }

    public static ProviderStatus of(State state, String message) {
        return new ProviderStatus(state, message);
    }
}
