package com.frostbyte.freezer.provider;

public final class RunningState {
    public enum State {
        RUNNING,
        NOT_RUNNING,
        UNKNOWN,
        UNSUPPORTED
    }

    public final State state;
    public final String detail;

    public RunningState(State state, String detail) {
        this.state = state;
        this.detail = detail == null ? "" : detail;
    }

    public static RunningState running(String detail) {
        return new RunningState(State.RUNNING, detail);
    }

    public static RunningState notRunning() {
        return new RunningState(State.NOT_RUNNING, "未发现运行中的进程");
    }

    public static RunningState unknown(String detail) {
        return new RunningState(State.UNKNOWN, detail);
    }

    public static RunningState unsupported(String detail) {
        return new RunningState(State.UNSUPPORTED, detail);
    }
}
