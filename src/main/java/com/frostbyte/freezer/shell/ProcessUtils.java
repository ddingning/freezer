package com.frostbyte.freezer.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class ProcessUtils {
    private ProcessUtils() {
    }

    static ShellResult collect(Process process, long timeoutMs) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outThread = copyThread(process.getInputStream(), stdout, "shell-stdout");
        Thread errThread = copyThread(process.getErrorStream(), stderr, "shell-stderr");
        outThread.start();
        errThread.start();

        boolean finished = waitFor(process, timeoutMs);
        if (!finished) {
            process.destroy();
        }
        join(outThread);
        join(errThread);

        String out = new String(stdout.toByteArray());
        String err = new String(stderr.toByteArray());
        int exitCode = -1;
        if (finished) {
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
            }
        }
        return new ShellResult(true, !finished, exitCode, out, err,
                finished ? null : "timeout");
    }

    private static Thread copyThread(final InputStream input, final ByteArrayOutputStream output,
                                     String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[4096];
                int read;
                try (InputStream in = input) {
                    while ((read = in.read(chunk)) != -1) {
                        output.write(chunk, 0, read);
                    }
                } catch (IOException ignored) {
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static boolean waitFor(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void join(Thread thread) {
        try {
            thread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
