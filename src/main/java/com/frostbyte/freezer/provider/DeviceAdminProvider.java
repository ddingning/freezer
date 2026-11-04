package com.frostbyte.freezer.provider;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import java.util.EnumSet;

public final class DeviceAdminProvider extends PrivilegeProvider {
    private final Context context;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName admin;

    public DeviceAdminProvider(Context context, ComponentName admin) {
        this.context = context.getApplicationContext();
        this.devicePolicyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.admin = admin;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.DEVICE_ADMIN;
    }

    @Override
    public ProviderStatus checkStatus() {
        try {
            if (devicePolicyManager.isDeviceOwnerApp(context.getPackageName())) {
                return ProviderStatus.of(ProviderStatus.State.NOT_AVAILABLE,
                        "已由设备所有者模式提供管理能力");
            }
            if (devicePolicyManager.isAdminActive(admin)) {
                return ProviderStatus.ready("设备管理员已激活（仅检测，无静默管理能力）");
            }
            return ProviderStatus.of(ProviderStatus.State.NOT_AVAILABLE,
                    "设备管理员未激活");
        } catch (Throwable e) {
            return ProviderStatus.of(ProviderStatus.State.ERROR,
                    "设备管理员检测失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public EnumSet<Capability> getCapabilities() {
        return EnumSet.noneOf(Capability.class);
    }

    public boolean isActive() {
        try {
            return devicePolicyManager.isAdminActive(admin);
        } catch (Throwable e) {
            return false;
        }
    }

    public ComponentName getAdminComponent() {
        return admin;
    }
}
