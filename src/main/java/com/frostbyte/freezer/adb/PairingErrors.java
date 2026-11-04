package com.frostbyte.freezer.adb;

import java.net.ConnectException;
import java.util.Locale;

public final class PairingErrors {
    private PairingErrors() {
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        if (error instanceof ConnectException) {
            return "无法连接配对端口，请确认无线调试配对对话框仍打开";
        }
        String raw = error.getMessage() == null ? "" : error.getMessage().trim();
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("exchange") || lower.contains("cipher") || lower.contains("password")) {
            return "配对码错误或已过期";
        }
        if (lower.contains("keying material")) {
            return "TLS 密钥导出失败（Conscrypt 不可用）";
        }
        if (lower.contains("tls") || lower.contains("ssl")) {
            return "TLS 握手失败";
        }
        if (lower.contains("timeout")) {
            return "连接超时";
        }
        if (!raw.isEmpty()) {
            return raw;
        }
        return error.getClass().getSimpleName();
    }
}
