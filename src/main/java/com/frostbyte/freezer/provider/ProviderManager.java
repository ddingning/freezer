package com.frostbyte.freezer.provider;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.frostbyte.freezer.AppInfo;
import com.frostbyte.freezer.admin.FreezerDeviceAdminReceiver;
import com.frostbyte.freezer.adb.AdbConnectionManager;
import com.frostbyte.freezer.shell.ShellResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProviderManager {
    public interface Listener {
        void onStatus(ProviderType type, ProviderStatus status);

        void onAllComplete();
    }

    private static volatile ProviderManager instance;

    private final Context context;
    private final List<PrivilegeProvider> providers = new ArrayList<>();
    private final Map<ProviderType, ProviderStatus> statuses =
            new ConcurrentHashMap<>();
    private final ExecutorService checkExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ProviderManager(Context context) {
        this.context = context.getApplicationContext();
        providers.add(new RootProvider(this.context));
        providers.add(new AdbProvider(this.context));
        providers.add(new ShizukuProvider(this.context));
        ComponentName admin = FreezerDeviceAdminReceiver.component(this.context);
        providers.add(new DeviceOwnerProvider(this.context, admin));
        providers.add(new DeviceAdminProvider(this.context, admin));
        for (PrivilegeProvider provider : providers) {
            statuses.put(provider.getType(), ProviderStatus.checking());
        }
    }

    public static ProviderManager get(Context context) {
        ProviderManager local = instance;
        if (local == null) {
            synchronized (ProviderManager.class) {
                local = instance;
                if (local == null) {
                    local = new ProviderManager(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    public List<PrivilegeProvider> getProviders() {
        return providers;
    }

    public PrivilegeProvider getProvider(ProviderType type) {
        for (PrivilegeProvider provider : providers) {
            if (provider.getType() == type) {
                return provider;
            }
        }
        return null;
    }

    public ProviderStatus getStatus(ProviderType type) {
        ProviderStatus status = statuses.get(type);
        return status == null ? ProviderStatus.checking() : status;
    }

    public void refreshAll(final Listener listener) {
        final AtomicInteger remaining = new AtomicInteger(providers.size());
        for (PrivilegeProvider provider : providers) {
            statuses.put(provider.getType(), ProviderStatus.checking());
            if (listener != null) {
                mainHandler.post(() -> listener.onStatus(provider.getType(), ProviderStatus.checking()));
            }
            checkExecutor.execute(() -> {
                final ProviderStatus status = checkSafely(provider);
                statuses.put(provider.getType(), status);
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onStatus(provider.getType(), status);
                    }
                    if (remaining.decrementAndGet() == 0 && listener != null) {
                        listener.onAllComplete();
                    }
                });
            });
        }
    }

    public void refreshOne(final ProviderType type, final Listener listener) {
        final PrivilegeProvider provider = getProvider(type);
        if (provider == null) {
            return;
        }
        statuses.put(type, ProviderStatus.checking());
        if (listener != null) {
            mainHandler.post(() -> listener.onStatus(type, ProviderStatus.checking()));
        }
        checkExecutor.execute(() -> {
            final ProviderStatus status = checkSafely(provider);
            statuses.put(type, status);
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onStatus(type, status);
                    listener.onAllComplete();
                }
            });
        });
    }

    private static ProviderStatus checkSafely(PrivilegeProvider provider) {
        try {
            return provider.checkStatus();
        } catch (Throwable e) {
            return ProviderStatus.of(ProviderStatus.State.ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public PrivilegeProvider primaryForCapability(Capability capability) {
        for (PrivilegeProvider provider : providers) {
            if (provider.can(capability) && getStatus(provider.getType()).isReady()) {
                return provider;
            }
        }
        return null;
    }

    public ProviderType primaryDisplayType() {
        PrivilegeProvider primary = primaryForCapability(Capability.FREEZE);
        if (primary != null) {
            return primary.getType();
        }
        for (PrivilegeProvider provider : providers) {
            if (provider.getType() == ProviderType.DEVICE_ADMIN
                    && getStatus(ProviderType.DEVICE_ADMIN).isReady()) {
                return ProviderType.DEVICE_ADMIN;
            }
        }
        return null;
    }

    /**
     * Re-validates the providers in priority order and returns the first one that really works.
     * Must be called on a worker thread.
     */
    public PrivilegeProvider resolve(Capability capability) {
        for (PrivilegeProvider provider : providers) {
            if (!provider.can(capability)) {
                continue;
            }
            ProviderStatus status = checkSafely(provider);
            statuses.put(provider.getType(), status);
            if (status.isReady()) {
                return provider;
            }
        }
        return null;
    }

    public OperationResult freeze(String packageName, boolean systemApp, int userId) {
        PrivilegeProvider provider = resolve(Capability.FREEZE);
        if (provider == null) {
            return OperationResult.unsupported(noProviderMessage("冻结"));
        }
        return provider.freeze(packageName, systemApp, userId);
    }

    public OperationResult unfreeze(String packageName, int userId) {
        PrivilegeProvider provider = resolve(Capability.UNFREEZE);
        if (provider == null) {
            return OperationResult.unsupported(noProviderMessage("解冻"));
        }
        return provider.unfreeze(packageName, userId);
    }

    public OperationResult uninstall(String packageName, boolean systemApp, int userId) {
        PrivilegeProvider provider = resolve(Capability.UNINSTALL);
        if (provider == null) {
            return OperationResult.unsupported(noProviderMessage("卸载"));
        }
        return provider.uninstall(packageName, systemApp, userId);
    }

    public Set<String> getRunningPackages(List<AppInfo> apps) {
        PrivilegeProvider provider = resolve(Capability.RUNNING_STATE);
        if (provider == null) {
            return null;
        }
        return provider.queryRunningPackages(apps);
    }

    public RunningState queryRunning(String packageName, int userId) {
        PrivilegeProvider provider = resolve(Capability.RUNNING_STATE);
        if (provider == null) {
            return RunningState.unsupported("当前权限模式无法读取其他应用进程");
        }
        return provider.queryRunning(packageName, userId);
    }

    public FreezeState queryFrozen(String packageName, int userId) {
        PrivilegeProvider provider = resolve(Capability.FROZEN_STATE);
        if (provider == null) {
            return FreezeState.unsupported("当前权限模式无法确认冻结状态");
        }
        return provider.queryFrozen(packageName, userId);
    }

    public Set<String> getFrozenPackages(List<AppInfo> apps) {
        PrivilegeProvider provider = primaryForCapability(Capability.FROZEN_STATE);
        if (provider == null) {
            return null;
        }
        return provider.queryFrozenPackages(apps);
    }

    /**
     * Runs before the first PackageManager query so OEM package-list restrictions can be fixed
     * without poisoning the process visibility cache. Only cheaply-checkable providers are used.
     */
    public PrivilegeProvider startupShellProvider() {
        PrivilegeProvider root = getProvider(ProviderType.ROOT);
        if (root != null) {
            ProviderStatus status = checkSafely(root);
            statuses.put(ProviderType.ROOT, status);
            if (status.isReady()) {
                return root;
            }
        }

        ShizukuProvider shizuku = (ShizukuProvider) getProvider(ProviderType.SHIZUKU);
        if (shizuku != null && shizuku.isInstalled() && shizuku.isBinderAlive()) {
            ProviderStatus status = checkSafely(shizuku);
            statuses.put(ProviderType.SHIZUKU, status);
            if (status.isReady()) {
                return shizuku;
            }
        }

        PrivilegeProvider adb = getProvider(ProviderType.ADB_WIRELESS);
        if (adb != null) {
            ProviderStatus status = checkSafely(adb);
            statuses.put(ProviderType.ADB_WIRELESS, status);
            if (status.isReady()) {
                return adb;
            }
        }
        return null;
    }

    public boolean fixPackageVisibility() {
        return fixPackageVisibility(resolve(Capability.SHELL));
    }

    public boolean fixPackageVisibility(PrivilegeProvider provider) {
        if (provider == null) {
            return false;
        }
        String packageName = context.getPackageName();
        String command = "/system/bin/cmd appops set " + packageName + " GET_INSTALLED_APP allow;"
                + "/system/bin/cmd appops set " + packageName + " GET_APP_LIST allow;"
                + "/system/bin/cmd appops set " + packageName + " READ_APP_LIST allow";
        ShellResult result = provider.execute(command, 20000L);
        return result.started && !result.timedOut;
    }

    public boolean restartApp() {
        PrivilegeProvider provider = resolve(Capability.SHELL);
        if (provider == null) {
            return false;
        }
        String packageName = context.getPackageName();
        String component = packageName + "/" + packageName + ".MainActivity";
        String command;
        if (provider.getType() == ProviderType.ROOT) {
            command = "setsid sh -c 'sleep 2; /system/bin/am start -n " + component
                    + "' >/dev/null 2>&1 & ; /system/bin/am force-stop " + packageName;
        } else {
            command = "/system/bin/am force-stop " + packageName
                    + "; /system/bin/am start -n " + component;
        }
        ShellResult result = provider.execute(command, 15000L);
        return result.started && !result.timedOut;
    }

    private String noProviderMessage(String action) {
        StringBuilder builder = new StringBuilder("没有可用于").append(action).append("的权限模式");
        for (PrivilegeProvider provider : providers) {
            ProviderStatus status = getStatus(provider.getType());
            if (status.state != ProviderStatus.State.CHECKING) {
                builder.append("\n· ").append(displayName(provider.getType()))
                        .append("：").append(status.message);
            }
        }
        return builder.toString();
    }

    public static String displayName(ProviderType type) {
        switch (type) {
            case ROOT:
                return "Root";
            case ADB_WIRELESS:
                return "无线调试 (ADB)";
            case SHIZUKU:
                return "Shizuku";
            case DEVICE_OWNER:
                return "设备所有者";
            case DEVICE_ADMIN:
                return "设备管理员";
            default:
                return type.name();
        }
    }

    public static String capabilityNames(EnumSet<Capability> capabilities) {
        StringBuilder builder = new StringBuilder();
        append(builder, capabilities, Capability.FREEZE, "冻结");
        append(builder, capabilities, Capability.UNFREEZE, "解冻");
        append(builder, capabilities, Capability.UNINSTALL, "卸载");
        append(builder, capabilities, Capability.RUNNING_STATE, "运行状态");
        append(builder, capabilities, Capability.FROZEN_STATE, "冻结状态");
        return builder.length() == 0 ? "仅检测" : builder.toString();
    }

    private static void append(StringBuilder builder, EnumSet<Capability> capabilities,
                               Capability capability, String label) {
        if (capabilities.contains(capability)) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(label);
        }
    }
}
