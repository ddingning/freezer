package com.frostbyte.freezer.shell;

import java.util.Locale;

public final class ShellResult {
    public final boolean started;
    public final boolean timedOut;
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public final String error;

    public ShellResult(boolean started, boolean timedOut, int exitCode,
                       String stdout, String stderr, String error) {
        this.started = started;
        this.timedOut = timedOut;
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.error = error;
    }

    public static ShellResult notStarted(String error) {
        return new ShellResult(false, false, -1, "", "", error);
    }

    public static ShellResult error(String error) {
        return new ShellResult(true, false, -1, "", "", error);
    }

    public boolean isExitSuccess() {
        return started && !timedOut && exitCode == 0;
    }

    public String combinedOutput() {
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout + "\n" + stderr;
    }

    public boolean containsDenied() {
        String text = combinedOutput().toLowerCase(Locale.US);
        return text.contains("permission denied")
                || text.contains("not allowed")
                || text.contains("operation not permitted")
                || text.contains("access denied")
                || text.contains("securityexception");
    }

    public String describe() {
        if (!started) {
            return error == null ? "not started" : error;
        }
        if (timedOut) {
            return "timeout";
        }
        if (error != null) {
            return error;
        }
        String text = combinedOutput().trim();
        if (!text.isEmpty()) {
            return text;
        }
        return "exit " + exitCode;
    }
}
