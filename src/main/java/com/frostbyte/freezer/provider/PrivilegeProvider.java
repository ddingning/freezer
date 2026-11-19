package com.frostbyte.freezer.provider;

import com.frostbyte.freezer.AppInfo;
import com.frostbyte.freezer.shell.ShellResult;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public abstract class PrivilegeProvider {
    public abstract ProviderType getType();

    public abstract ProviderStatus checkStatus();

    public abstract EnumSet<Capability> getCapabilities();

    public boolean can(Capability capability) {
        return getCapabilities().contains(capability);
    }

    public ShellResult execute(String command, long timeoutMs) {
        return ShellResult.notStarted("该权限模式不支持 Shell 命令");
    }

    public OperationResult freeze(String packageName, boolean systemApp, int userId) {
        return OperationResult.unsupported("该权限模式不支持冻结应用");
    }

    public OperationResult unfreeze(String packageName, int userId) {
        return OperationResult.unsupported("该权限模式不支持解冻应用");
    }

    public OperationResult uninstall(String packageName, boolean systemApp, int userId) {
        return OperationResult.unsupported("该权限模式不支持静默卸载");
    }

    public RunningState queryRunning(String packageName, int userId) {
        return RunningState.unsupported("该权限模式无法查询其他应用进程");
    }

    public FreezeState queryFrozen(String packageName, int userId) {
        return FreezeState.unsupported("该权限模式无法查询冻结状态");
    }

    public Set<String> queryFrozenPackages(List<AppInfo> apps) {
        return null;
    }

    public Set<String> queryRunningPackages(List<AppInfo> apps) {
        return null;
    }
}
