package com.frostbyte.freezer.shell;

public interface ShellExecutor {
    ShellResult run(String command, long timeoutMs);
}
