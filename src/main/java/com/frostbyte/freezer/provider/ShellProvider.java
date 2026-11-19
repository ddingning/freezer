package com.frostbyte.freezer.provider;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.frostbyte.freezer.AppInfo;
import com.frostbyte.freezer.shell.ShellExecutor;
import com.frostbyte.freezer.shell.ShellResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class ShellProvider extends PrivilegeProvider {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private static final Pattern USER_PREFIX_PATTERN =
            Pattern.compile("^u(\\d+)_");

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
    public OperationResult freeze(String packageName, boolean systemApp, int userId) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }

        FreezeSettings.Mode mode = FreezeSettings.getMode(context);
        switch (mode) {
            case KILL:
                return forceStop(packageName, userId);
            case HIDE:
                return hide(packageName, userId);
            case DISABLE:
                return disable(packageName, userId);
            case DISABLE_USER:
                return disableUser(packageName, userId);
            case AUTO:
            default:
                OperationResult disableUser = disableUser(packageName, userId);
                if (disableUser.success) {
                    return disableUser;
                }
                OperationResult hide = hide(packageName, userId);
                if (hide.success) {
                    return hide;
                }
                return OperationResult.failure("自动冻结失败。\n\n"
                        + disableUser.message + "\n\n" + hide.message, null);
        }
    }

    private OperationResult disableUser(String packageName, int userId) {
        ShellResult result = execute("/system/bin/pm disable-user --user " + userId + " "
                + packageName, 20000L);
        FreezeState state = queryFrozen(packageName, userId);
        if (state.state == FreezeState.State.FROZEN) {
            return OperationResult.success("已冻结（disabled-user）" + userSuffix(userId));
        }
        return OperationResult.failure(disableFailureMessage(packageName, userId, result, state),
                result);
    }

    private OperationResult disable(String packageName, int userId) {
        ShellResult result = execute("/system/bin/pm disable --user " + userId + " "
                + packageName, 20000L);
        FreezeState state = queryFrozen(packageName, userId);
        if (state.state == FreezeState.State.FROZEN) {
            return OperationResult.success("已冻结（disabled）" + userSuffix(userId));
        }
        return OperationResult.failure(disableFailureMessage(packageName, userId, result, state),
                result);
    }

    private OperationResult hide(String packageName, int userId) {
        ShellResult first = execute("/system/bin/pm hide --user " + userId + " "
                + packageName, 20000L);
        Boolean hidden = isHidden(packageName, userId);
        if (Boolean.TRUE.equals(hidden)) {
            return OperationResult.success("已冻结（hide 隐藏）" + userSuffix(userId));
        }
        ShellResult second = execute("/system/bin/pm hide --user " + userId + " "
                + packageName, 20000L);
        hidden = isHidden(packageName, userId);
        if (Boolean.TRUE.equals(hidden)) {
            return OperationResult.success("已冻结（hide 隐藏）" + userSuffix(userId));
        }
        return OperationResult.failure(
                hideFailureMessage(packageName, userId, first, second, hidden), second);
    }

    private OperationResult forceStop(String packageName, int userId) {
        ShellResult result = execute("/system/bin/am force-stop --user " + userId + " "
                + packageName, 10000L);
        if (result.isExitSuccess() && !result.containsDenied()) {
            return OperationResult.success(
                    "已强制停止（kill 模式不持久，应用可能自动重启）" + userSuffix(userId));
        }
        return OperationResult.failure("强制停止失败\n" + result.describe(), result);
    }

    @Override
    public OperationResult unfreeze(String packageName, int userId) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }

        ShellResult unhide = execute("/system/bin/pm unhide --user " + userId + " "
                + packageName, 20000L);
        FreezeState state = queryFrozen(packageName, userId);
        if (state.state == FreezeState.State.NOT_FROZEN) {
            return OperationResult.success("已解冻" + userSuffix(userId));
        }

        ShellResult enable = execute("/system/bin/pm enable --user " + userId + " "
                + packageName, 20000L);
        state = queryFrozen(packageName, userId);
        if (state.state == FreezeState.State.NOT_FROZEN) {
            return OperationResult.success("已解冻" + userSuffix(userId));
        }

        if (userId == 0) {
            ShellResult enableAll = execute("/system/bin/pm enable " + packageName, 20000L);
            state = queryFrozen(packageName, userId);
            if (state.state == FreezeState.State.NOT_FROZEN) {
                return OperationResult.success("已解冻");
            }
            ShellResult unhideDefault = execute("/system/bin/pm unhide " + packageName, 20000L);
            state = queryFrozen(packageName, userId);
            if (state.state == FreezeState.State.NOT_FROZEN) {
                return OperationResult.success("已解冻");
            }
            String verification = state.state == FreezeState.State.UNKNOWN
                    ? "；无法验证状态：" + state.detail
                    : "";
            return OperationResult.failure(
                    "解冻失败" + verification + "\n"
                            + combineAll(unhide, enable, enableAll, unhideDefault), unhideDefault);
        }

        String verification = state.state == FreezeState.State.UNKNOWN
                ? "；无法验证状态：" + state.detail
                : "";
        return OperationResult.failure(
                "解冻失败" + verification + "\n" + combine(unhide, enable), enable);
    }

    private String disableFailureMessage(String packageName, int userId,
                                         ShellResult result, FreezeState state) {
        StringBuilder message = new StringBuilder("冻结失败").append(userSuffix(userId));
        if (state.state == FreezeState.State.UNKNOWN) {
            message.append("（无法验证状态：").append(state.detail).append("）");
        }
        message.append("\n原因：").append(analyzeDisableFailure(packageName, result));
        String output = result == null ? "" : result.combinedOutput().trim();
        if (!output.isEmpty()) {
            message.append("\n\n原始输出：\n").append(output);
        }
        message.append("\n\n可在“冻结方式”中尝试 hide（可逆隐藏）或 kill（仅强制停止）。");
        return message.toString();
    }

    private String hideFailureMessage(String packageName, int userId, ShellResult first,
                                      ShellResult second, Boolean hidden) {
        StringBuilder message = new StringBuilder("hide 冻结失败").append(userSuffix(userId));
        if (hidden == null) {
            message.append("（无法验证 hidden 状态）");
        }
        message.append("\n原因：").append(analyzeDisableFailure(packageName, first));
        message.append("\n\n原始输出：\n").append(combine(first, second));
        message.append("\n\n如果 hide 仍被拒绝，可选择 kill 模式仅强制停止进程（不会持久冻结）。");
        return message.toString();
    }

    private String analyzeDisableFailure(String packageName, ShellResult result) {
        String adminReason = activeAdminReason(packageName);
        if (adminReason != null) {
            return "该包是" + adminReason + "，系统禁止冻结；请先解除对应管理权限。";
        }

        String output = result == null ? "" : result.combinedOutput();
        String lower = output.toLowerCase(Locale.US);
        boolean protectedError = lower.contains("not allowed to disable")
                || lower.contains("protected")
                || lower.contains("disallow_disable")
                || lower.contains("securityexception");

        if (protectedError) {
            StringBuilder reason = new StringBuilder(
                    "系统保护包（protected / DISALLOW_DISABLE），PackageManager 拒绝禁用。");
            if (Build.VERSION.SDK_INT >= 34) {
                reason.append(" Android 14+ 可能由 DISALLOW_DISABLE 策略设置。");
            }
            String oem = oemFamily();
            if (oem != null) {
                reason.append(" 检测到 ").append(oem).append(" 定制系统，可能有厂商白名单保护。");
            }
            reason.append(" 本应用不会直接清理 DISALLOW_DISABLE 或修改 /data/system XML。");
            return reason.toString();
        }
        if (result != null && result.timedOut) {
            return "命令超时。";
        }
        if (result != null && result.containsDenied()) {
            return "权限不足或被系统拒绝。";
        }
        return "PackageManager 拒绝操作，请查看原始输出。";
    }

    private String activeAdminReason(String packageName) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context
                    .getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) {
                return null;
            }
            if (dpm.isDeviceOwnerApp(packageName)) {
                return "设备所有者（Device Owner）";
            }
            if (dpm.isProfileOwnerApp(packageName)) {
                return "配置文件所有者（Profile Owner）";
            }
            List<ComponentName> admins = dpm.getActiveAdmins();
            if (admins != null) {
                for (ComponentName admin : admins) {
                    if (packageName.equals(admin.getPackageName())) {
                        return "设备管理员（Device Admin）";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String oemFamily() {
        String value = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        if (value.contains("xiaomi") || value.contains("redmi") || value.contains("poco")) {
            return "Xiaomi/HyperOS";
        }
        if (value.contains("huawei") || value.contains("honor")) {
            return "EMUI/MagicOS";
        }
        if (value.contains("oppo") || value.contains("oneplus") || value.contains("realme")) {
            return "ColorOS";
        }
        if (value.contains("vivo") || value.contains("iqoo")) {
            return "OriginOS";
        }
        return null;
    }

    @Override
    public OperationResult uninstall(String packageName, boolean systemApp, int userId) {
        if (!isValidPackage(packageName)) {
            return OperationResult.failure("非法包名", null);
        }
        ShellResult first = execute("/system/bin/pm uninstall --user " + userId + " "
                + packageName, 30000L);
        ShellResult second = null;
        if (userId == 0) {
            second = execute("/system/bin/pm uninstall " + packageName, 30000L);
        }

        Boolean installed = isPackageInstalled(packageName, userId);
        if (Boolean.FALSE.equals(installed)) {
            return OperationResult.success("已卸载" + userSuffix(userId));
        }
        String verification = installed == null
                ? "；无法验证安装状态"
                : "；应用仍然存在";
        return OperationResult.failure(
                "卸载失败" + verification + userSuffix(userId) + "\n"
                        + combine(first, second), first);
    }

    @Override
    public RunningState queryRunning(String packageName, int userId) {
        if (!isValidPackage(packageName)) {
            return RunningState.unknown("非法包名");
        }
        ShellResult result = queryProcesses();
        if (result == null) {
            return RunningState.unknown("无法执行进程查询");
        }
        if (result.containsDenied()) {
            return RunningState.unknown("权限不足，无法读取进程列表");
        }
        List<String> pids = new ArrayList<>();
        for (ProcessEntry entry : parseProcesses(result.stdout)) {
            if (entry.userId == userId
                    && (entry.name.equals(packageName)
                    || entry.name.startsWith(packageName + ":"))) {
                pids.add(entry.pid);
            }
        }
        if (pids.isEmpty()) {
            return RunningState.notRunning();
        }
        return RunningState.running("PID " + join(pids));
    }

    @Override
    public Set<String> queryRunningPackages(List<AppInfo> apps) {
        ShellResult result = queryProcesses();
        if (result == null || result.containsDenied()) {
            return null;
        }
        Map<Integer, Set<String>> namesByUser = new HashMap<>();
        for (ProcessEntry entry : parseProcesses(result.stdout)) {
            Set<String> names = namesByUser.get(entry.userId);
            if (names == null) {
                names = new HashSet<>();
                namesByUser.put(entry.userId, names);
            }
            int colon = entry.name.indexOf(':');
            names.add(colon > 0 ? entry.name.substring(0, colon) : entry.name);
        }
        Set<String> running = new HashSet<>();
        for (AppInfo app : apps) {
            Set<String> names = namesByUser.get(app.userId);
            if (names != null && names.contains(app.packageName)) {
                running.add(app.key());
            }
        }
        return running;
    }

    private ShellResult queryProcesses() {
        ShellResult result = execute("/system/bin/ps -A -o USER,PID,NAME", 8000L);
        if (!result.isExitSuccess() || result.stdout.trim().isEmpty()) {
            ShellResult fallback = execute("/system/bin/ps -A", 8000L);
            if (fallback.isExitSuccess() && !fallback.stdout.trim().isEmpty()) {
                return fallback;
            }
        }
        if (!result.isExitSuccess() || result.timedOut) {
            return null;
        }
        return result;
    }

    private static final class ProcessEntry {
        final int userId;
        final String pid;
        final String name;

        ProcessEntry(int userId, String pid, String name) {
            this.userId = userId;
            this.pid = pid;
            this.name = name;
        }
    }

    private static List<ProcessEntry> parseProcesses(String output) {
        List<ProcessEntry> entries = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            String userToken = parts[0];
            if (userToken.equalsIgnoreCase("USER") || userToken.equalsIgnoreCase("PID")) {
                continue;
            }
            String pid = "";
            for (String part : parts) {
                if (part.matches("[0-9]+")) {
                    pid = part;
                    break;
                }
            }
            if (pid.isEmpty()) {
                continue;
            }
            String name = parts[parts.length - 1];
            int userId = 0;
            Matcher matcher = USER_PREFIX_PATTERN.matcher(userToken);
            if (matcher.find()) {
                try {
                    userId = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            entries.add(new ProcessEntry(userId, pid, name));
        }
        return entries;
    }

    @Override
    public FreezeState queryFrozen(String packageName, int userId) {
        Set<String> disabled = queryDisabledPackages(userId);
        Boolean hidden = isHidden(packageName, userId);

        boolean isDisabled = disabled != null && disabled.contains(packageName);
        boolean isHidden = Boolean.TRUE.equals(hidden);
        if (isDisabled || isHidden) {
            String detail = isDisabled && isHidden ? "disabled + hidden"
                    : isDisabled ? "disabled" : "hidden";
            return FreezeState.frozen(detail);
        }
        if (disabled == null || hidden == null) {
            return FreezeState.unknown("无法完整读取 disabled/hidden 状态");
        }
        return FreezeState.notFrozen();
    }

    @Override
    public Set<String> queryFrozenPackages(List<AppInfo> apps) {
        Set<Integer> users = new HashSet<>();
        for (AppInfo app : apps) {
            users.add(app.userId);
        }
        Set<String> result = new HashSet<>();
        boolean anyDisabled = false;
        for (int userId : users) {
            Set<String> disabled = queryDisabledPackages(userId);
            if (disabled != null) {
                anyDisabled = true;
                for (String pkg : disabled) {
                    result.add(userId + ":" + pkg);
                }
            }
        }
        Set<String> hidden = queryHiddenPackages();
        if (!anyDisabled && hidden == null) {
            return null;
        }
        if (hidden != null) {
            result.addAll(hidden);
        }
        return result;
    }

    private Set<String> queryDisabledPackages(int userId) {
        ShellResult result = execute("/system/bin/pm list packages -d --user " + userId, 12000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        Set<String> packages = new HashSet<>();
        for (String line : result.stdout.split("\\R")) {
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

    private Boolean isHidden(String packageName, int userId) {
        ShellResult result = execute("/system/bin/dumpsys package " + packageName, 12000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        String prefix = "User " + userId + ":";
        for (String line : result.stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix) && trimmed.contains("hidden=")) {
                return trimmed.contains("hidden=true");
            }
        }
        if (result.stdout.contains("hidden=true")) {
            return Boolean.TRUE;
        }
        if (result.stdout.contains("hidden=false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private Set<String> queryHiddenPackages() {
        ShellResult result = execute("/system/bin/dumpsys package", 30000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        Set<String> hidden = new HashSet<>();
        String current = null;
        for (String line : result.stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Package [")) {
                int start = "Package [".length();
                int end = trimmed.indexOf(']', start);
                current = end > start ? trimmed.substring(start, end) : null;
            } else if (current != null && trimmed.startsWith("User ")
                    && trimmed.contains("hidden=true")) {
                int colon = trimmed.indexOf(':');
                if (colon > 5) {
                    try {
                        int userId = Integer.parseInt(trimmed.substring(5, colon).trim());
                        hidden.add(userId + ":" + current);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return hidden;
    }

    private Boolean isPackageInstalled(String packageName, int userId) {
        ShellResult result = execute("/system/bin/pm list packages --user " + userId + " "
                + packageName, 12000L);
        if (!result.isExitSuccess()) {
            return null;
        }
        for (String line : result.stdout.split("\\R")) {
            if (line.trim().equals("package:" + packageName)) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    public List<Integer> listUserIds() {
        ShellResult result = execute("/system/bin/pm list users", 10000L);
        List<Integer> users = new ArrayList<>();
        if (result.isExitSuccess()) {
            for (String line : result.stdout.split("\\R")) {
                String trimmed = line.trim();
                int start = trimmed.indexOf("UserInfo{");
                if (start < 0) {
                    continue;
                }
                int colon = trimmed.indexOf(':', start);
                int end = trimmed.indexOf('}', start);
                if (colon > start && end > colon) {
                    try {
                        int id = Integer.parseInt(trimmed.substring(start + 9, colon));
                        if (!users.contains(id)) {
                            users.add(id);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (users.isEmpty()) {
            users.add(0);
        }
        return users;
    }

    public Set<String> listPackagesForUser(int userId) {
        Set<String> packages = new HashSet<>();
        ShellResult result = execute("/system/bin/pm list packages --user " + userId, 20000L);
        if (!result.isExitSuccess()) {
            return packages;
        }
        for (String line : result.stdout.split("\\R")) {
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

    public Map<String, Integer> listPackageUidsForUser(int userId) {
        Map<String, Integer> uids = new HashMap<>();
        ShellResult result = execute("/system/bin/pm list packages -U --user " + userId, 30000L);
        if (!result.isExitSuccess()) {
            return uids;
        }
        for (String line : result.stdout.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("package:")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            String pkg = null;
            int uid = -1;
            for (String token : tokens) {
                if (token.startsWith("package:")) {
                    pkg = token.substring("package:".length());
                } else if (token.startsWith("uid:")) {
                    try {
                        uid = Integer.parseInt(token.substring("uid:".length()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (pkg != null && uid >= 0) {
                uids.put(pkg, uid);
            }
        }
        return uids;
    }

    public Set<String> listDisabledPackagesForUser(int userId) {
        Set<String> disabled = queryDisabledPackages(userId);
        return disabled == null ? new HashSet<>() : disabled;
    }

    public static boolean isValidPackage(String packageName) {
        return packageName != null && PACKAGE_PATTERN.matcher(packageName).matches();
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
        return combineAll(first, second);
    }

    private static String combineAll(ShellResult... results) {
        StringBuilder builder = new StringBuilder();
        for (ShellResult result : results) {
            if (result == null) {
                continue;
            }
            String output = result.combinedOutput().trim();
            if (output.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(output);
        }
        if (builder.length() == 0) {
            builder.append("命令执行失败");
        }
        return builder.toString();
    }

    private static String userSuffix(int userId) {
        return userId == 0 ? "" : "（用户 " + userId + "）";
    }
}
