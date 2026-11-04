package com.frostbyte.freezer.adb;

import com.frostbyte.freezer.shell.ShellResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class AdbShell {
    private static final String EXIT_MARKER = "__FREEZER_EXIT:";
    private static final String STDERR_BEGIN = "__FREEZER_STDERR_BEGIN";
    private static final String STDERR_END = "__FREEZER_STDERR_END";

    private AdbShell() {
    }

    public static ShellResult run(AbsAdbConnectionManager manager, String command, long timeoutMs) {
        String wrapper = "__FREEZER_ERR=/data/local/tmp/.freezer_err_$$; "
                + "( " + command + " ) 2>$__FREEZER_ERR; "
                + "__FREEZER_CODE=$?; "
                + "echo \"" + EXIT_MARKER + "$__FREEZER_CODE\"; "
                + "echo \"" + STDERR_BEGIN + "\"; "
                + "cat $__FREEZER_ERR 2>/dev/null; "
                + "rm -f $__FREEZER_ERR; "
                + "echo \"" + STDERR_END + "\"";

        AdbStream stream;
        try {
            stream = manager.openStream("shell:" + wrapper);
        } catch (Exception e) {
            return ShellResult.notStarted(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        StreamWatchdog watchdog = new StreamWatchdog(stream, timeoutMs);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[4096];
            while (true) {
                int read;
                try {
                    read = stream.read(chunk, 0, chunk.length);
                } catch (IOException e) {
                    if (stream.isClosed()) {
                        break;
                    }
                    throw e;
                }
                watchdog.touch();
                if (read <= 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
        } catch (Exception e) {
            return new ShellResult(true, watchdog.isTimedOut(), -1, "", "",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            watchdog.stop();
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }

        String raw = new String(buffer.toByteArray());
        return parse(raw, watchdog.isTimedOut());
    }

    public static String exec(AbsAdbConnectionManager manager, String command, long timeoutMs) {
        return run(manager, command, timeoutMs).stdout;
    }

    private static ShellResult parse(String raw, boolean timedOut) {
        int exitIndex = raw.lastIndexOf(EXIT_MARKER);
        if (exitIndex < 0) {
            return new ShellResult(true, timedOut, -1, raw, "",
                    timedOut ? "timeout" : "missing exit marker");
        }
        int lineEnd = raw.indexOf('\n', exitIndex);
        if (lineEnd < 0) {
            lineEnd = raw.length();
        }
        String codeText = raw.substring(exitIndex + EXIT_MARKER.length(), lineEnd).trim();
        int exitCode;
        try {
            exitCode = Integer.parseInt(codeText);
        } catch (NumberFormatException e) {
            exitCode = -1;
        }

        String stdout = raw.substring(0, exitIndex);
        String stderr = "";
        int stderrBegin = raw.indexOf(STDERR_BEGIN, lineEnd);
        if (stderrBegin >= 0) {
            int contentStart = stderrBegin + STDERR_BEGIN.length();
            if (contentStart < raw.length() && raw.charAt(contentStart) == '\r') {
                contentStart++;
            }
            if (contentStart < raw.length() && raw.charAt(contentStart) == '\n') {
                contentStart++;
            }
            int stderrEnd = raw.indexOf(STDERR_END, contentStart);
            stderr = stderrEnd < 0 ? raw.substring(contentStart) : raw.substring(contentStart, stderrEnd);
        }
        return new ShellResult(true, timedOut, exitCode,
                stripTrailingNewline(stdout), stripTrailingNewline(stderr), null);
    }

    private static String stripTrailingNewline(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static final class StreamWatchdog {
        private final AdbStream stream;
        private final long timeoutMs;
        private final Thread thread;
        private volatile long lastActivity = System.currentTimeMillis();
        private volatile boolean timedOut = false;

        StreamWatchdog(AdbStream stream, long timeoutMs) {
            this.stream = stream;
            this.timeoutMs = timeoutMs;
            this.thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (System.currentTimeMillis() - lastActivity > StreamWatchdog.this.timeoutMs) {
                            timedOut = true;
                            try {
                                stream.close();
                            } catch (IOException ignored) {
                            }
                            return;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "adb-shell-watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        boolean isTimedOut() {
            return timedOut;
        }

        void stop() {
            thread.interrupt();
        }
    }
}
