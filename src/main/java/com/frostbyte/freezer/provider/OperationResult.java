package com.frostbyte.freezer.provider;

import com.frostbyte.freezer.shell.ShellResult;

public final class OperationResult {
    public final boolean success;
    public final String message;
    public final ShellResult shellResult;

    public OperationResult(boolean success, String message, ShellResult shellResult) {
        this.success = success;
        this.message = message == null ? "" : message;
        this.shellResult = shellResult;
    }

    public static OperationResult success(String message) {
        return new OperationResult(true, message, null);
    }

    public static OperationResult failure(String message, ShellResult result) {
        return new OperationResult(false, message, result);
    }

    public static OperationResult unsupported(String message) {
        return new OperationResult(false, message, null);
    }
}
