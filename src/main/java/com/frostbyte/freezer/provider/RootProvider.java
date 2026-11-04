package com.frostbyte.freezer.provider;

import android.content.Context;

import com.frostbyte.freezer.shell.LocalShell;
import com.frostbyte.freezer.shell.ShellResult;

import java.util.Locale;

public final class RootProvider extends ShellProvider {
    public RootProvider(Context context) {
        super(context, LocalShell.ROOT_EXECUTOR);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.ROOT;
    }

    @Override
    public ProviderStatus checkStatus() {
        ShellResult identity = execute("id", 15000L);
        if (!identity.started) {
            return ProviderStatus.of(ProviderStatus.State.NOT_AVAILABLE,
                    "未找到可执行的 su：" + identity.error);
        }
        if (identity.timedOut) {
            return ProviderStatus.of(ProviderStatus.State.UNKNOWN,
                    "Root 检测超时");
        }

        String output = identity.combinedOutput();
        String lower = output.toLowerCase(Locale.US);
        boolean isRoot = output.contains("uid=0") || lower.contains("uid=0(");
        if (isRoot) {
            ShellResult data = execute("ls -A /data", 10000L);
            boolean dataAccessible = data.started && data.isExitSuccess()
                    && !data.containsDenied();
            if (dataAccessible) {
                return ProviderStatus.ready("Root 已授权（uid=0，/data 可访问）");
            }
            return ProviderStatus.ready("Root 已授权（uid=0，/data 访问受限）");
        }

        if (identity.containsDenied()) {
            return ProviderStatus.of(ProviderStatus.State.DENIED,
                    "已找到 su，但 Root 授权被拒绝");
        }
        if (identity.exitCode != 0) {
            return ProviderStatus.of(ProviderStatus.State.DENIED,
                    "su 执行失败（退出码 " + identity.exitCode + "）");
        }
        if (lower.contains("uid=")) {
            return ProviderStatus.of(ProviderStatus.State.NOT_AUTHORIZED,
                    "su 返回非 root 用户：" + firstUid(output));
        }
        return ProviderStatus.of(ProviderStatus.State.UNKNOWN,
                "无法确认 Root 状态：" + identity.describe());
    }

    private static String firstUid(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("uid=")) {
                int space = trimmed.indexOf(' ');
                return space > 0 ? trimmed.substring(0, space) : trimmed;
            }
        }
        return "未知";
    }
}
