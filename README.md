# 冻结助手 · Freezer

一个面向 Android 的应用冻结 / 解冻 / 卸载工具，支持多种特权后端：

- **Root**（通用 `su -c`，不绑定 Magisk / KernelSU）
- **无线调试 ADB**（Android 11+，通知栏输入六位配对码，取得 UID 2000 shell）
- **Shizuku**
- **Device Owner（设备所有者）**：隐藏 / 取消隐藏应用
- **Device Admin（设备管理员）**：仅检测，不支持静默管理

> 项目主页：<https://github.com/ddingning/freezer>
>
> 当前版本：**1.0.1**（`versionCode 2`）
> 包名：`com.frostbyte.freezer`

---

## 功能特性

- 应用列表：名称、包名、图标、系统/用户应用、冻结状态
- 搜索：应用名称、包名（包含匹配），纯数字按 **UID 精确匹配**（如输入 `1000`）
- 分类筛选：全部 / 用户应用 / 系统应用 / 已冻结
- 应用详情二级页面：
  - 应用名称、包名、图标
  - 程序路径 `sourceDir`、数据路径 `dataDir`
  - UID、安装日期、更新时间
  - 运行状态（实际进程检测）
  - 冻结状态（实际 Provider 检测）
  - 系统应用、APK 大小、版本、目标 SDK、版本号
  - 长按任意信息行复制（包名、路径、UID 等）
- 操作：冻结 / 解冻 / 卸载 / 打开应用 / 应用信息
- 统一权限页：展示各 Provider 的真实状态、能力范围和连接操作
- 主图标点击：显示项目地址卡片，可一键复制（不打开浏览器）
- 前台配对服务：无线调试配对码通过通知栏内联回复输入，无需切回应用

---

## 权限模式与能力矩阵

不同后端能力并不相同，应用会根据当前真实状态自动选择可用的 Provider。

| 能力 | Root | 无线 ADB | Shizuku | Device Owner | Device Admin |
| --- | --- | --- | --- | --- | --- |
| 冻结 / 解冻 | ✅ | ✅ | ✅ | ✅（隐藏/取消隐藏） | ❌ |
| 静默卸载 | ✅ | ✅ | ✅ | ❌ | ❌ |
| 冻结状态检测 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 运行进程检测 | ✅ | ✅ | ✅ | ❌（未知） | ❌（未知） |
| 读取应用列表 | 系统 PackageManager | 同左 | 同左 | 同左 | 同左 |

说明：

- **ADB Shell 是 UID 2000，不是 Root**，权限页会独立显示。
- **Device Owner** 使用 `DevicePolicyManager.setApplicationHidden`，不是 `pm disable`；不支持静默卸载。
- **Device Admin** 只是普通设备管理员，不能静默冻结/卸载应用，本应用只展示其激活状态。
- 无特权 Provider 时，详情页冻结状态会退回 `PackageManager` 的 `enabled` 状态作为系统级参考；运行状态会明确显示“无法检测”，不会误报“运行中”。

---

## 使用说明

### 1. 安装

从构建产物安装：

```text
app/build/outputs/apk/release/Freezer-release.apk
```

最低支持 **Android 7.0（API 24）**；无线 ADB 配对需要 **Android 11（API 30）及以上**。

### 2. 授予权限

点击主界面右上角盾牌图标，或点击顶部权限状态胶囊，进入“权限与工作模式”。

#### Root

- 确保设备已 Root，并在 Root 管理器中授予本应用权限。
- 应用会实际执行 `su -c id`，只有返回 `uid=0` 才判定为 Root 可用。
- `/data` 访问测试仅作为辅助证据，不会单独作为 Root 判定依据。
- 不依赖 Magisk / KernelSU 专有命令；`su` 优先从 `PATH` 查找，并回退到常见路径。

#### 无线调试（ADB）

1. 在系统设置中打开“开发者选项 → 无线调试”。
2. 在权限页点击 **配对**。
3. 系统会弹出“使用配对码配对设备”窗口，保持该窗口打开。
4. 下拉通知栏，找到 **“冻结助手 / 无线调试配对”** 通知。
5. 点按通知中的 **输入配对码**，输入系统显示的六位数字。
6. 配对成功后返回应用，应用会自动通过 `_adb-tls-connect._tcp` 连接并取得 UID 2000 shell。

说明：

- 配对端口与最终连接端口是**不同的 mDNS 服务**，应用不会混用。
- 配对码和端口以通知发现到的最新服务为准。
- 配对状态会持久化；后续只需保持无线调试开启即可自动重连。

#### Shizuku

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)。
2. 在权限页点击 **授权**，在 Shizuku 授权弹窗中允许。
3. 应用通过 `IShizukuService.newProcess` 执行 shell 命令，UID 为 2000。

Shizuku 未安装、服务未运行、未授权、Binder 失效会分别显示不同状态。

#### Device Owner（设备所有者）

需要在**未添加账号、未完成初始设置**的设备上通过 ADB 一次性设置：

```bash
adb shell dpm set-device-owner com.frostbyte.freezer/.admin.FreezerDeviceAdminReceiver
```

设置后：

- 支持隐藏 / 取消隐藏应用（冻结 / 解冻）。
- 不支持静默卸载。
- 无法查询其他应用运行进程（显示“无法检测”）。

#### Device Admin（设备管理员）

- 在权限页点击 **激活管理员**，按系统提示激活。
- 仅用于状态检测；不会把 Device Admin 当作 Root、ADB 或 Device Owner。
- 不支持的操作会明确提示，不会假装成功。

### 3. 冻结 / 解冻 / 卸载

- 点击应用进入详情页。
- **冻结应用 / 解冻应用**：由当前可用 Provider 执行，并重新查询真实状态验证结果。
- **卸载应用**：系统应用默认使用 `pm uninstall --user 0`，可能只对当前用户移除。
- **打开应用**：始终显示；应用处于冻结状态时会提示先解冻。
- 操作失败时展示命令输出和失败原因，不会把失败当成功。

---

## 搜索与详情

- 搜索框支持：
  - 应用名称：包含匹配
  - 包名：包含匹配
  - **UID：纯数字精确匹配**（例如 `1000`、`2000`、`10183`）
- 详情页所有信息均来自 `PackageManager` / `ApplicationInfo` / `PackageInfo` 真实系统数据。
- 时间戳格式：`yyyy-MM-dd HH:mm:ss`。
- 无法读取的字段显示“不可用”，不会伪造默认值。
- 长按信息行复制到剪贴板。

---

## 工作原理

### 统一 Provider 架构

```text
PrivilegeProvider
├── RootProvider          (LocalShell + su -c)
├── AdbProvider           (无线配对 + AdbConnectionManager + ADB shell)
├── ShizukuProvider       (Shizuku API + IShizukuService.newProcess)
├── DeviceOwnerProvider   (DevicePolicyManager.setApplicationHidden)
└── DeviceAdminProvider   (仅检测 DevicePolicyManager.isAdminActive)
```

核心设计：

- `ProviderManager` 统一管理状态、能力和操作调度。
- 每次操作前重新验证 Provider，避免使用过期权限状态。
- 能力矩阵决定某个操作由哪个 Provider 执行。
- 所有 shell 命令都有超时、stdout/stderr 分离和真实退出码。
- 包名统一校验，避免命令注入。
- 异步操作使用 generation 计数，防止页面销毁或并发请求导致状态覆盖。

### Root 检测

- 实际执行 `su -c id`。
- 仅 `uid=0` 视为 Root。
- 区分：无 su、su 被拒绝、su 返回非 root 用户、超时、未知异常。
- `/data` 可访问性作为辅助状态展示，不单独作为 Root 依据。

### 运行状态检测

- Root / ADB / Shizuku：`pidof <pkg>`，再回退 `ps -A -o PID,NAME` / `ps -A`。
- 精确匹配 `pkg` 或 `pkg:` 子进程，收集 PID。
- Device Owner / Device Admin：无进程查询能力，显示“无法检测”。
- 检测失败、权限不足、超时显示“未知”，不会显示“未运行”。

### 冻结状态检测

- Shell Provider：`pm list packages -d`，按精确包名匹配。
- Device Owner：`DevicePolicyManager.isApplicationHidden`。
- 无 Provider 时回退 `PackageManager.enabled` 作为系统级参考。
- 冻结/解冻/卸载后会再次查询状态进行验证。

### OEM 应用列表限制

部分国产 ROM（如 ZUI / OriginOS）通过 AppOp `GET_INSTALLED_APP` 限制读取应用列表。

- 应用在首次加载前会尝试通过可用的 Root / ADB / Shizuku 执行：

```text
cmd appops set com.frostbyte.freezer GET_INSTALLED_APP allow
```

- 若当前进程的 PackageManager 可见性缓存已形成，可能需要重启应用；应用会提示并可尝试自动重启。
- 同时声明 `QUERY_ALL_PACKAGES` 以支持正常读取应用列表。该权限不适合直接上架 Google Play，本项目定位为个人/开源侧载工具。

### 无线 ADB 配对

- 使用 mDNS 发现 `_adb-tls-pairing._tcp`。
- 前台服务在通知栏提供六位配对码的内联输入。
- 使用 SPAKE2 + TLS 配对，密钥持久化复用。
- 配对成功后通过 `_adb-tls-connect._tcp` 连接，不混用配对端口。
- 连接健康检查会实际执行 `id -u` 并验证 UID=2000。
- 连接断开时状态会更新，可手动重连；操作前会重新验证连接。

---

## 构建

### 环境

- JDK 17+（本项目使用 Android Studio 自带 JBR 21）
- Android SDK Platform 35
- Gradle Wrapper（已包含）

### 命令

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

当前 `release` 默认使用本机 debug keystore 签名，便于个人覆盖安装。**正式发布前请在 `app/build.gradle` 中配置你自己的 release keystore。**

ABI：`arm64-v8a`、`armeabi-v7a`（Conscrypt TLS 原生库需要）。

R8 规则见 `app/proguard-rules.pro`，保留了 ADB/SPAKE2、Conscrypt、BouncyCastle、Shizuku 等必要类。

---

## 目录结构

```text
app/src/main/java/com/frostbyte/freezer/
├── MainActivity.java              # 主列表、搜索、权限状态入口
├── AppDetailActivity.java         # 应用详情二级页
├── PermissionsActivity.java       # 统一权限与工作模式页
├── AppInfo.java / AppDetail.java
├── AppRepository.java             # PackageManager 应用/详情读取
├── AppListAdapter.java            # 列表、搜索（名称/包名/UID）
├── admin/
│   └── FreezerDeviceAdminReceiver.java
├── provider/
│   ├── PrivilegeProvider.java
│   ├── ShellProvider.java
│   ├── RootProvider.java
│   ├── AdbProvider.java
│   ├── ShizukuProvider.java
│   ├── DeviceOwnerProvider.java
│   ├── DeviceAdminProvider.java
│   └── ProviderManager.java
├── shell/
│   ├── ShellResult.java
│   ├── ShellExecutor.java
│   ├── ProcessUtils.java
│   ├── LocalShell.java            # 通用 su -c
│   ├── AdbShellExecutor.java
│   └── ShizukuShell.java
├── adb/                           # 复用的 ADB 协议、配对、TLS、SPAKE2
│   ├── AdbPairingService.java     # 通知栏配对前台服务
│   ├── AdbConnectionManager.java
│   ├── AdbShell.java
│   ├── PairingConnectionCtx.java
│   ├── SslUtils.java
│   └── mdns/AdbMdns.java
└── crypto/spake2, crypto/x25519   # ADB 配对加密原语
```

---

## 已知限制

- 无线 ADB 配对面向**同机无线调试**（Android 11+），依赖本机 mDNS 服务；不同 ROM 的配对窗口行为可能略有差异。
- 系统应用卸载通常是 `--user 0` 移除，能否彻底卸载取决于系统权限和 ROM。
- Device Owner 需要一次性外部 ADB 设置，且要求设备未完成初始配置；已配置设备无法直接设置。
- Device Admin 不能静默管理应用，这是 Android 能力边界，不是实现缺陷。
- Shizuku 需要用户安装并启动 Shizuku 服务；未安装/未运行/未授权会分别提示。
- 部分 OEM 的“读取应用列表”AppOp 可能需要在授权后重启应用才能生效。
- 本项目未在全部 Android 版本/ROM 上做穷举测试；已做 Root 与无线 ADB 真机验证，Shizuku / Device Owner 需目标环境验证。
- Release 默认使用 debug 签名；正式分发请更换签名。

---

## 第三方与许可

本项目复用了参考项目中的 ADB 无线配对/连接实现：

- 参考项目：`vivo_x100ultra_root_src`
- ADB 协议部分：基于 Cameron Gutman 的 AdbLib（BSD-3-Clause）
- mDNS：基于 AOSP / Shizuku 实现
- SPAKE2 / x25519：按参考项目 GPLv3 许可

详见：

- [`LICENSE`](LICENSE)
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)



---

## 免责声明

本项目用于个人设备管理与学习研究。

- 冻结或卸载系统应用可能导致系统功能异常、数据丢失或无法开机。
- 使用前请确认你知道每个应用的作用，并自行承担风险。
- 请勿将本工具用于未经授权的设备。
