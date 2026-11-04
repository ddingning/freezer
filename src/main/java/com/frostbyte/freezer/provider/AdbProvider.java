package com.frostbyte.freezer.provider;

import android.content.Context;
import android.os.Build;

import com.frostbyte.freezer.adb.AdbAuthenticationFailedException;
import com.frostbyte.freezer.adb.AdbConnectionManager;
import com.frostbyte.freezer.adb.AdbKeyManager;
import com.frostbyte.freezer.adb.AdbPairingRequiredException;
import com.frostbyte.freezer.adb.AdbShell;
import com.frostbyte.freezer.adb.PairingState;
import com.frostbyte.freezer.adb.mdns.AdbMdns;
import com.frostbyte.freezer.shell.AdbShellExecutor;
import com.frostbyte.freezer.shell.ShellResult;

import java.net.ConnectException;
import java.util.Locale;

public final class AdbProvider extends ShellProvider {
    public interface PairingDiscoveryListener {
        void onDiscovered(String host, int port);

        void onLost();
    }

    private final PairingState pairingState;
    private AdbMdns pairingMdns;

    public AdbProvider(Context context) {
        super(context, new AdbShellExecutor(context));
        pairingState = new PairingState(context);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.ADB_WIRELESS;
    }

    @Override
    public ProviderStatus checkStatus() {
        if (Build.VERSION.SDK_INT < 30) {
            return ProviderStatus.of(ProviderStatus.State.NOT_AVAILABLE,
                    "无线调试需要 Android 11 及以上");
        }

        boolean hasKey = AdbKeyManager.hasStoredKey(context);
        if (!pairingState.isPaired() && !hasKey) {
            return ProviderStatus.of(ProviderStatus.State.NOT_PAIRED,
                    "尚未配对无线调试");
        }

        AdbConnectionManager manager = AdbConnectionManager.get(context);
        if (manager.isConnected()) {
            ProviderStatus verified = verifyShell(manager);
            if (verified.isReady()) {
                return verified;
            }
            try {
                manager.disconnect();
            } catch (Exception ignored) {
            }
        }

        try {
            manager.connectTls(context, 3000L);
        } catch (AdbAuthenticationFailedException | AdbPairingRequiredException e) {
            return ProviderStatus.of(ProviderStatus.State.NOT_PAIRED,
                    "adbd 拒绝了当前密钥，请重新配对");
        } catch (InterruptedException e) {
            return ProviderStatus.of(ProviderStatus.State.DISCONNECTED,
                    "未发现无线调试服务（超时）");
        } catch (Exception e) {
            return ProviderStatus.of(ProviderStatus.State.DISCONNECTED,
                    "连接失败：" + describe(e));
        }

        if (!manager.isConnected()) {
            return ProviderStatus.of(ProviderStatus.State.DISCONNECTED,
                    "无线调试连接未建立");
        }
        return verifyShell(manager);
    }

    private ProviderStatus verifyShell(AdbConnectionManager manager) {
        ShellResult id = AdbShell.run(manager, "id -u", 4000L);
        if (!id.started) {
            return ProviderStatus.of(ProviderStatus.State.DISCONNECTED,
                    "ADB 连接异常：" + id.describe());
        }
        if (id.timedOut) {
            return ProviderStatus.of(ProviderStatus.State.DISCONNECTED,
                    "ADB Shell 响应超时");
        }
        if (id.isExitSuccess() && id.stdout.trim().equals("2000")) {
            return ProviderStatus.ready("ADB Shell 已连接（UID 2000）");
        }
        return ProviderStatus.of(ProviderStatus.State.ERROR,
                "ADB Shell 未返回 UID 2000：" + id.describe());
    }

    public OperationResult pair(String host, int port, String code) {
        if (Build.VERSION.SDK_INT < 30) {
            return OperationResult.unsupported("无线调试需要 Android 11 及以上");
        }
        if (host == null || host.isEmpty() || port <= 0) {
            return OperationResult.failure("配对服务地址或端口无效", null);
        }
        if (code == null || code.trim().isEmpty()) {
            return OperationResult.failure("请输入六位配对码", null);
        }

        try {
            AdbConnectionManager manager = AdbConnectionManager.get(context);
            manager.pair(host, port, code.trim());
            pairingState.markPaired();

            String connectError = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    manager.connectTls(context, 5000L);
                    if (manager.isConnected()) {
                        connectError = null;
                        break;
                    }
                } catch (Exception e) {
                    connectError = describe(e);
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (manager.isConnected()) {
                ShellResult id = AdbShell.run(manager, "id -u", 4000L);
                if (id.isExitSuccess() && id.stdout.trim().equals("2000")) {
                    return OperationResult.success("配对成功，ADB Shell 已连接（UID 2000）");
                }
                return OperationResult.success("配对成功，但 Shell 验证失败：" + id.describe());
            }
            return OperationResult.failure(
                    "配对成功，但连接失败：" + (connectError == null ? "未发现连接服务" : connectError), null);
        } catch (Throwable e) {
            return OperationResult.failure("配对失败：" + describe(e), null);
        }
    }

    public OperationResult connectNow() {
        try {
            AdbConnectionManager manager = AdbConnectionManager.get(context);
            manager.connectTls(context, AdbConnectionManager.DEFAULT_TIMEOUT_MS);
            if (manager.isConnected()) {
                ShellResult id = AdbShell.run(manager, "id -u", 4000L);
                if (id.isExitSuccess() && id.stdout.trim().equals("2000")) {
                    return OperationResult.success("ADB Shell 已连接");
                }
                return OperationResult.failure("连接异常：" + id.describe(), id);
            }
            return OperationResult.failure("未发现无线调试连接服务", null);
        } catch (Exception e) {
            return OperationResult.failure("连接失败：" + describe(e), null);
        }
    }

    public OperationResult disconnect() {
        try {
            AdbConnectionManager manager = AdbConnectionManager.get(context);
            manager.disconnect();
            return OperationResult.success("已断开 ADB 连接");
        } catch (Exception e) {
            return OperationResult.failure("断开失败：" + describe(e), null);
        }
    }

    public boolean isPaired() {
        return pairingState.isPaired();
    }

    public void startPairingDiscovery(PairingDiscoveryListener listener) {
        stopPairingDiscovery();
        pairingMdns = new AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                (hostAddress, port) -> {
                    if (port <= 0 || hostAddress == null) {
                        if (listener != null) {
                            listener.onLost();
                        }
                        return;
                    }
                    if (listener != null) {
                        listener.onDiscovered(hostAddress.getHostAddress(), port);
                    }
                });
        pairingMdns.start();
    }

    public void stopPairingDiscovery() {
        if (pairingMdns != null) {
            pairingMdns.stop();
            pairingMdns = null;
        }
    }

    private static String describe(Throwable error) {
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
