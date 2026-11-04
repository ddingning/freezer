package com.frostbyte.freezer.provider;

public final class FreezeState {
    public enum State {
        FROZEN,
        NOT_FROZEN,
        UNKNOWN,
        UNSUPPORTED
    }

    public final State state;
    public final String detail;

    public FreezeState(State state, String detail) {
        this.state = state;
        this.detail = detail == null ? "" : detail;
    }

    public static FreezeState frozen(String detail) {
        return new FreezeState(State.FROZEN, detail);
    }

    public static FreezeState notFrozen() {
        return new FreezeState(State.NOT_FROZEN, "未冻结");
    }

    public static FreezeState unknown(String detail) {
        return new FreezeState(State.UNKNOWN, detail);
    }

    public static FreezeState unsupported(String detail) {
        return new FreezeState(State.UNSUPPORTED, detail);
    }
}
