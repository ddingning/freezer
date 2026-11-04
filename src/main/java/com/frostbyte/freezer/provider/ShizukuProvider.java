package com.frostbyte.freezer.provider;

import android.content.Context;
import android.content.pm.PackageManager;

import com.frostbyte.freezer.shell.ShizukuShell;
import com.frostbyte.freezer.shell.ShellResult;

import rikka.shizuku.Shizuku;

public final class ShizukuProvider extends ShellProvider {
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final int REQUEST_PERMISSION = 2301;

    public ShizukuProvider(Context context) {
        super(context, ShizukuShell.EXECUTOR);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.SHIZUKU;
    }

    @Override
    public ProviderStatus checkStatus() {
        if (!isInstalled()) {
            return ProviderStatus.of(ProviderStatus.State.NOT_INSTALLED,
                    "未安装 Shizuku");
        }

        boolean binderAlive;
        try {
            binderAlive = Shizuku.pingBinder();
        } catch (Throwable e) {
            return ProviderStatus.of(ProviderStatus.State.ERROR,
                    "Shizuku Binder 异常：" + e.getClass().getSimpleName());
        }
        if (!binderAlive) {
            return ProviderStatus.of(ProviderStatus.State.NOT_RUNNING,
                    "Shizuku 服务未运行，请先启动 Shizuku");
        }

        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ProviderStatus.of(ProviderStatus.State.NOT_AUTHORIZED,
                        "Shizuku 未授权");
            }
        } catch (Throwable e) {
            return ProviderStatus.of(ProviderStatus.State.ERROR,
                    "无法检查 Shizuku 授权：" + e.getClass().getSimpleName());
        }

        ShellResult id = execute("id -u", 5000L);
        if (id.isExitSuccess() && id.stdout.trim().equals("2000")) {
            return ProviderStatus.ready("Shizuku Shell 已授权（UID 2000）");
        }
        return ProviderStatus.of(ProviderStatus.State.ERROR,
                "Shizuku Shell 未返回 UID 2000：" + id.describe());
    }

    public boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }
}
