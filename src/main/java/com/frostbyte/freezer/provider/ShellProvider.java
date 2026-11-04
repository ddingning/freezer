package com.frostbyte.freezer.provider;

import android.content.Context;

import com.frostbyte.freezer.AppInfo;
import com.frostbyte.freezer.shell.ShellExecutor;
import com.frostbyte.freezer.shell.ShellResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public abstract class ShellProvider extends PrivilegeProvider {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    protected final Context context;
    private final ShellExecutor executor;

    protected ShellProvider(Context context, ShellExecutor executor) {
        this.context = context.getApplicationContext();
        this.executor = executor;
    }

    @Override
    public EnumSet<Capability> getCapabilities() {
        return EnumSet.of(
                Capability.SHELL,
                Capability.FREEZE,
                Capability.UNFREEZE,
                Capability.UNINSTALL,
                Capability.RUNNING_STATE,
                Capability.FROZEN_STATE);
    }

    @Override
    public ShellResult execute(String command, long timeoutMs) {
        return executor.run(command, timeoutMs);
    }

    @Override
    public OperationResult freeze(String packageName, boolean systemApp) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }
        ShellResult first = execute("/system/bin/pm disable-user --user 0 " + packageName, 20000L);
        FreezeState state = queryFrozen(packageName);
        if (state.state == FreezeState.State.FROZEN) {
            return OperationResult.success("已冻结");
        }
        ShellResult second = execute("/system/bin/pm disable " + packageName, 20000L);
        state = queryFrozen(packageName);
        if (state.state == FreezeState.State.FROZEN) {
            return OperationResult.success("已冻结");
        }
        String verification = state.state == FreezeState.State.UNKNOWN
                ? "；无法验证状态：" + state.detail
                : "";
        return OperationResult.failure(
                "冻结失败" + verification + "\n" + combine(first, second), second);
    }

    @Override
    public OperationResult unfreeze(String packageName) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }
        ShellResult first = execute("/system/bin/pm enable --user 0 " + packageName, 20000L);
        FreezeState state = queryFrozen(packageName);
        if (state.state == FreezeState.State.NOT_FROZEN) {
            return OperationResult.success("已解冻");
        }
        ShellResult second = execute("/system/bin/pm enable " + packageName, 20000L);
        state = queryFrozen(packageName);
        if (state.state == FreezeState.State.NOT_FROZEN) {
            return OperationResult.success("已解冻");
        }
        String verification = state.state == FreezeState.State.UNKNOWN
                ? "；无法验证状态：" + state.detail
                : "";
        return OperationResult.failure(
                "解冻失败" + verification + "\n" + combine(first, second), second);
    }

    @Override
    public OperationResult uninstall(String packageName, boolean systemApp) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }
        ShellResult first;
        ShellResult second;
        if (systemApp) {
            first = execute("/system/bin/pm uninstall --user 0 " + packageName, 30000L);
            second = execute("/system/bin/pm uninstall " + packageName, 30000L);
        } else {
            first = execute("/system/bin/pm uninstall " + packageName, 30000L);
            second = execute("/system/bin/pm uninstall --user 0 " + packageName, 30000L);
        }

        Boolean installed = isPackageInstalled(packageName);
        if (Boolean.FALSE.equals(installed)) {
            return OperationResult.success("已卸载");
        }
        String verification = installed == null
                ? "；无法验证安装状态"
                : "；应用仍然存在";
        return OperationResult.failure(
                "卸载失败" + verification + "\n" + combine(first, second), second);
    }

    @Override
    public RunningState queryRunning(String packageName) {
        if (!isValidPackage(packageName)) {
            return RunningState.unknown("非法包名");
        }
        ShellResult pidof = execute("/system/bin/pidof " + packageName, 5000L);
        if (pidof.isExitSuccess()) {
            List<String> pidofPids = new ArrayList<>();
            for (String token : pidof.stdout.trim().split("\\s+")) {
                if (token.matches("[0-9]+")) {
                    pidofPids.add(token);
                }
            }
            if (!pidofPids.isEmpty()) {
                return RunningState.running("PID " + join(pidofPids));
            }
        }

        ShellResult result = execute("/system/bin/ps -A -o PID,NAME", 8000L);
        if (!result.isExitSuccess() || result.stdout.trim().isEmpty()) {
            ShellResult fallback = execute("/system/bin/ps -A", 8000L);
            if (fallback.isExitSuccess() && !fallback.stdout.trim().isEmpty()) {
                result = fallback;
            }
        }
        if (!result.started) {
            return RunningState.unknown("无法执行进程查询：" + result.describe());
        }
        if (result.timedOut) {
            return RunningState.unknown("进程查询超时");
        }
        if (result.containsDenied()) {
            return RunningState.unknown("权限不足，无法读取进程列表");
        }
        if (!result.isExitSuccess()) {
            return RunningState.unknown("进程查询失败：" + result.describe());
        }
        List<String> pids = findProcessIds(packageName, result.stdout);
        if (pids.isEmpty()) {
            return RunningState.notRunning();
        }
        return RunningState.running("PID " + join(pids));
    }

    @Override
    public FreezeState queryFrozen(String packageName) {
        Set<String> disabled = queryDisabledPackages();
        if (disabled == null) {
            return FreezeState.unknown("无法读取冻结包列表");
        }
        return disabled.contains(packageName)
                ? FreezeState.frozen("pm 包状态为 disabled")
                : FreezeState.notFrozen();
    }

    @Override
    public Set<String> queryFrozenPackages(List<AppInfo> apps) {
        return queryDisabledPackages();
    }

    private Set<String> queryDisabledPackages() {
        ShellResult result = execute("/system/bin/pm list packages -d", 12000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        Set<String> packages = new HashSet<>();
        String[] lines = result.stdout.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package:")) {
                String name = trimmed.substring("package:".length()).trim();
                if (!name.isEmpty()) {
                    packages.add(name);
                }
            }
        }
        return packages;
    }

    private Boolean isPackageInstalled(String packageName) {
        ShellResult result = execute("/system/bin/pm list packages " + packageName, 12000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        String[] lines = result.stdout.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("package:" + packageName)) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    public static boolean isValidPackage(String packageName) {
        return packageName != null && PACKAGE_PATTERN.matcher(packageName).matches();
    }

    private static List<String> findProcessIds(String packageName, String output) {
        List<String> pids = new ArrayList<>();
        String[] lines = output.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String first = parts[0];
            if (first.equalsIgnoreCase("PID") || first.equalsIgnoreCase("USER")
                    || first.equalsIgnoreCase("NAME")) {
                continue;
            }
            String name = parts[parts.length - 1];
            if (!name.equals(packageName) && !name.startsWith(packageName + ":")) {
                continue;
            }
            String pid = "";
            for (String part : parts) {
                if (part.matches("[0-9]+")) {
                    pid = part;
                    break;
                }
            }
            if (!pid.isEmpty() && !pids.contains(pid)) {
                pids.add(pid);
            }
        }
        return pids;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String combine(ShellResult first, ShellResult second) {
        StringBuilder builder = new StringBuilder();
        String a = first == null ? "" : first.combinedOutput().trim();
        String b = second == null ? "" : second.combinedOutput().trim();
        if (!a.isEmpty()) {
            builder.append(a);
        }
        if (!b.isEmpty()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(b);
        }
        if (builder.length() == 0) {
            builder.append("命令执行失败");
        }
        return builder.toString();
    }

    protected static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.US);
    }
}
