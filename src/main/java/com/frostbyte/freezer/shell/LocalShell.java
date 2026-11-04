package com.frostbyte.freezer.shell;

import java.io.IOException;

public final class LocalShell {
    private static final String[] SU_BINARIES = {
            "su", "/system/bin/su", "/system/xbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
    };

    private LocalShell() {
    }

    public static ShellResult runRoot(String command, long timeoutMs) {
        IOException lastError = null;
        for (String binary : SU_BINARIES) {
            Process process;
            try {
                process = new ProcessBuilder(binary, "-c", command)
                        .redirectErrorStream(false)
                        .start();
            } catch (IOException e) {
                lastError = e;
                continue;
            }
            return ProcessUtils.collect(process, timeoutMs);
        }
        String message = lastError == null ? "su not found" : lastError.getMessage();
        return ShellResult.notStarted("su not found: " + message);
    }

    public static ShellResult runShell(String command, long timeoutMs) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            return ProcessUtils.collect(process, timeoutMs);
        } catch (IOException e) {
            return ShellResult.notStarted(e.getMessage());
        }
    }

    public static final ShellExecutor ROOT_EXECUTOR = new ShellExecutor() {
        @Override
        public ShellResult run(String command, long timeoutMs) {
            return runRoot(command, timeoutMs);
        }
    };
}
