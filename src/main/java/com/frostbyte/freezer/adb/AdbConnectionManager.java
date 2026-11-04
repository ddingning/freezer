package com.frostbyte.freezer.adb;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.frostbyte.freezer.shell.ShellResult;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

public final class AdbConnectionManager extends AbsAdbConnectionManager {
    public static final String DEVICE_NAME = "freezer";
    public static final long DEFAULT_TIMEOUT_MS = 8000L;

    private static volatile AdbConnectionManager instance;

    private final AdbKeyManager keyManager;

    private AdbConnectionManager(Context context) {
        keyManager = new AdbKeyManager(context.getApplicationContext());
        setApi(Build.VERSION.SDK_INT);
        setThrowOnUnauthorised(true);
        setTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public static AdbConnectionManager get(Context context) {
        AdbConnectionManager local = instance;
        if (local == null) {
            synchronized (AdbConnectionManager.class) {
                local = instance;
                if (local == null) {
                    local = new AdbConnectionManager(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean hasLiveConnection() {
        AdbConnectionManager local = instance;
        return local != null && local.isConnected();
    }

    /**
     * Verified connectivity: the socket is alive and the remote shell really answers as UID 2000.
     */
    public static boolean healthCheck(Context context) {
        AdbConnectionManager manager = instance;
        if (manager == null || !manager.isConnected()) {
            return false;
        }
        try {
            ShellResult result = AdbShell.run(manager, "id -u", 3000L);
            if (result.isExitSuccess() && result.stdout.trim().equals("2000")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            manager.disconnect();
        } catch (Exception ignored) {
        }
        return false;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return keyManager.getPrivateKey();
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return keyManager.getCertificate();
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return DEVICE_NAME;
    }
}
