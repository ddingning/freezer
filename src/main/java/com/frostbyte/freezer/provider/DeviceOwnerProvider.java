package com.frostbyte.freezer.provider;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.frostbyte.freezer.AppInfo;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DeviceOwnerProvider extends PrivilegeProvider {
    private final Context context;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName admin;

    public DeviceOwnerProvider(Context context, ComponentName admin) {
        this.context = context.getApplicationContext();
        this.devicePolicyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.admin = admin;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.DEVICE_OWNER;
    }

    @Override
    public ProviderStatus checkStatus() {
        try {
            if (!devicePolicyManager.isDeviceOwnerApp(context.getPackageName())) {
                return ProviderStatus.of(ProviderStatus.State.NOT_AVAILABLE,
                        "当前应用不是设备所有者");
            }
            if (!devicePolicyManager.isAdminActive(admin)) {
                return ProviderStatus.of(ProviderStatus.State.ERROR,
                        "设备所有者已设置，但管理员组件未激活");
            }
            return ProviderStatus.ready("设备所有者已启用");
        } catch (Throwable e) {
            return ProviderStatus.of(ProviderStatus.State.ERROR,
                    "设备所有者检测失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public EnumSet<Capability> getCapabilities() {
        return EnumSet.of(Capability.FREEZE, Capability.UNFREEZE, Capability.FROZEN_STATE);
    }

    @Override
    public OperationResult freeze(String packageName, boolean systemApp) {
        return setHidden(packageName, true);
    }

    @Override
    public OperationResult unfreeze(String packageName) {
        return setHidden(packageName, false);
    }

    private OperationResult setHidden(String packageName, boolean hidden) {
        if (!ShellProvider.isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }
        try {
            boolean changed = devicePolicyManager.setApplicationHidden(admin, packageName, hidden);
            boolean actual = devicePolicyManager.isApplicationHidden(admin, packageName);
            if (actual == hidden) {
                return OperationResult.success(hidden ? "已冻结（隐藏）" : "已解冻（取消隐藏）");
            }
            return OperationResult.failure(
                    "DevicePolicyManager 返回 " + changed + "，但状态验证失败", null);
        } catch (SecurityException e) {
            return OperationResult.failure("没有设备所有者权限：" + e.getMessage(), null);
        } catch (Throwable e) {
            return OperationResult.failure("设置隐藏状态失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()), null);
        }
    }

    @Override
    public FreezeState queryFrozen(String packageName) {
        if (!ShellProvider.isValidPackage(packageName)) {
            return FreezeState.unknown("非法包名");
        }
        try {
            boolean hidden = devicePolicyManager.isApplicationHidden(admin, packageName);
            return hidden ? FreezeState.frozen("Device Owner 隐藏状态") : FreezeState.notFrozen();
        } catch (Throwable e) {
            return FreezeState.unknown("无法查询隐藏状态：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public Set<String> queryFrozenPackages(List<AppInfo> apps) {
        Set<String> hidden = new HashSet<>();
        try {
            for (AppInfo app : apps) {
                if (devicePolicyManager.isApplicationHidden(admin, app.packageName)) {
                    hidden.add(app.packageName);
                }
            }
        } catch (Throwable e) {
            return null;
        }
        return hidden;
    }

    @Override
    public OperationResult uninstall(String packageName, boolean systemApp) {
        return OperationResult.unsupported(
                "Device Owner 模式不支持静默卸载，请在系统设置中手动卸载");
    }

    @Override
    public RunningState queryRunning(String packageName) {
        return RunningState.unsupported(
                "Device Owner 没有进程查询能力，无法确认运行状态");
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }
}
