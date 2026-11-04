package com.frostbyte.freezer.shell;

import android.content.Context;

import com.frostbyte.freezer.adb.AdbConnectionManager;
import com.frostbyte.freezer.adb.AdbShell;

public final class AdbShellExecutor implements ShellExecutor {
    private final Context context;

    public AdbShellExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public ShellResult run(String command, long timeoutMs) {
        AdbConnectionManager manager = AdbConnectionManager.get(context);
        if (!manager.isConnected()) {
            return ShellResult.notStarted("ADB shell 未连接");
        }
        return AdbShell.run(manager, command, timeoutMs);
    }
}
