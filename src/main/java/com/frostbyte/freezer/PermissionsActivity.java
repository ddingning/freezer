package com.frostbyte.freezer;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.frostbyte.freezer.admin.FreezerDeviceAdminReceiver;
import com.frostbyte.freezer.adb.AdbPairingService;
import com.frostbyte.freezer.provider.AdbProvider;
import com.frostbyte.freezer.provider.FreezeSettings;
import com.frostbyte.freezer.provider.OperationResult;
import com.frostbyte.freezer.provider.PrivilegeProvider;
import com.frostbyte.freezer.provider.ProviderManager;
import com.frostbyte.freezer.provider.ProviderStatus;
import com.frostbyte.freezer.provider.ProviderType;
import com.frostbyte.freezer.provider.ShizukuProvider;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class PermissionsActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<ProviderType, ProviderCard> cards = new EnumMap<>(ProviderType.class);

    private ProviderManager providerManager;
    private RadioGroup freezeModeGroup;
    private boolean bindingFreezeMode;
    private AlertDialog progressDialog;
    private AlertDialog pairingDialog;
    private AdbProvider adbProvider;
    private String pairingHost;
    private int pairingPort = -1;
    private boolean pairingInProgress = false;
    private boolean firstResume = true;
    private int generation = 0;

    private ActivityResultLauncher<Intent> adminLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == ShizukuProvider.REQUEST_PERMISSION) {
                    refreshOne(ProviderType.SHIZUKU);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        providerManager = ProviderManager.get(this);
        adminLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> refreshOne(ProviderType.DEVICE_ADMIN));
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startPairingNotification();
                    } else {
                        Toast.makeText(this, "需要通知权限才能显示配对码输入框",
                                Toast.LENGTH_LONG).show();
                    }
                });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh_permissions).setOnClickListener(v -> refreshAll());

        LinearLayout container = findViewById(R.id.provider_container);
        for (PrivilegeProvider provider : providerManager.getProviders()) {
            View root = LayoutInflater.from(this)
                    .inflate(R.layout.provider_card, container, false);
            ProviderCard card = new ProviderCard(provider.getType(), root);
            cards.put(provider.getType(), card);
            container.addView(root);
            card.name.setText(ProviderManager.displayName(provider.getType()));
            card.render(ProviderStatus.checking(), provider.getCapabilities());
        }


        bindFreezeMode();
        refreshAll();
    }

    private void bindFreezeMode() {
        freezeModeGroup = findViewById(R.id.freeze_mode_group);
        bindingFreezeMode = true;
        int checkedId;
        switch (FreezeSettings.getMode(this)) {
            case DISABLE_USER:
                checkedId = R.id.mode_disable_user;
                break;
            case DISABLE:
                checkedId = R.id.mode_disable;
                break;
            case HIDE:
                checkedId = R.id.mode_hide;
                break;
            case KILL:
                checkedId = R.id.mode_kill;
                break;
            case AUTO:
            default:
                checkedId = R.id.mode_auto;
                break;
        }
        freezeModeGroup.check(checkedId);
        bindingFreezeMode = false;
        freezeModeGroup.setOnCheckedChangeListener((group, id) -> {
            if (bindingFreezeMode) {
                return;
            }
            FreezeSettings.Mode mode;
            if (id == R.id.mode_disable_user) {
                mode = FreezeSettings.Mode.DISABLE_USER;
            } else if (id == R.id.mode_disable) {
                mode = FreezeSettings.Mode.DISABLE;
            } else if (id == R.id.mode_hide) {
                mode = FreezeSettings.Mode.HIDE;
            } else if (id == R.id.mode_kill) {
                mode = FreezeSettings.Mode.KILL;
            } else {
                mode = FreezeSettings.Mode.AUTO;
            }
            FreezeSettings.setMode(this, mode);
            Toast.makeText(this, "已保存冻结方式", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        if (firstResume) {
            firstResume = false;
            return;
        }
        refreshAll();
    }

    @Override
    protected void onPause() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private void refreshAll() {
        final int current = ++generation;
        providerManager.refreshAll(new ProviderManager.Listener() {
            @Override
            public void onStatus(ProviderType type, ProviderStatus status) {
                if (current != generation || isFinishing() || isDestroyed()) {
                    return;
                }
                ProviderCard card = cards.get(type);
                PrivilegeProvider provider = providerManager.getProvider(type);
                if (card != null && provider != null) {
                    card.render(status, provider.getCapabilities());
                }
            }

            @Override
            public void onAllComplete() {
            }
        });
    }

    private void refreshOne(ProviderType type) {
        final int current = generation;
        providerManager.refreshOne(type, new ProviderManager.Listener() {
            @Override
            public void onStatus(ProviderType changedType, ProviderStatus status) {
                if (current != generation) {
                    return;
                }
                ProviderCard card = cards.get(changedType);
                PrivilegeProvider provider = providerManager.getProvider(changedType);
                if (card != null && provider != null) {
                    card.render(status, provider.getCapabilities());
                }
            }

            @Override
            public void onAllComplete() {
            }
        });
    }

    private final class ProviderCard {
        final ProviderType type;
        final TextView name;
        final TextView status;
        final TextView message;
        final TextView caps;
        final LinearLayout actions;

        ProviderCard(ProviderType type, View root) {
            this.type = type;
            name = root.findViewById(R.id.provider_name);
            status = root.findViewById(R.id.provider_status);
            message = root.findViewById(R.id.provider_message);
            caps = root.findViewById(R.id.provider_caps);
            actions = root.findViewById(R.id.provider_actions);
        }

        void render(ProviderStatus providerStatus, java.util.EnumSet<com.frostbyte.freezer.provider.Capability> capabilities) {
            status.setText(stateLabel(providerStatus.state));
            applyPill(providerStatus.state);
            message.setText(providerStatus.message);
            caps.setText(ProviderManager.capabilityNames(capabilities));

            actions.removeAllViews();
            switch (type) {
                case ROOT:
                    addAction(getString(R.string.action_recheck),
                            v -> refreshOne(ProviderType.ROOT));
                    break;
                case ADB_WIRELESS:
                    addAction(getString(R.string.action_pair), v -> startAdbPairing());
                    if (AdbPairingService.isRunning()) {
                        addAction(getString(R.string.action_stop_pairing), v -> {
                            AdbPairingService.stop(PermissionsActivity.this);
                            refreshOne(ProviderType.ADB_WIRELESS);
                        });
                    }
                    if (providerStatus.isReady()) {
                        addAction(getString(R.string.action_disconnect), v -> disconnectAdb());
                    } else {
                        addAction(getString(R.string.action_connect), v -> connectAdb());
                    }
                    addAction(getString(R.string.action_recheck),
                            v -> refreshOne(ProviderType.ADB_WIRELESS));
                    break;
                case SHIZUKU:
                    if (providerStatus.state == ProviderStatus.State.NOT_INSTALLED) {
                        addAction("安装说明", v -> openShizukuWebsite());
                    } else {
                        if (providerStatus.state == ProviderStatus.State.NOT_AUTHORIZED) {
                            addAction(getString(R.string.action_authorize), v -> requestShizuku());
                        }
                        addAction(getString(R.string.action_open_shizuku), v -> openShizuku());
                    }
                    addAction(getString(R.string.action_recheck),
                            v -> refreshOne(ProviderType.SHIZUKU));
                    break;
                case DEVICE_OWNER:
                    addAction(getString(R.string.action_setup_owner),
                            v -> showDeviceOwnerInstructions());
                    addAction(getString(R.string.action_recheck),
                            v -> refreshOne(ProviderType.DEVICE_OWNER));
                    break;
                case DEVICE_ADMIN:
                    if (providerStatus.isReady()) {
                        addAction(getString(R.string.action_deactivate_admin),
                                v -> deactivateAdmin());
                    } else {
                        addAction(getString(R.string.action_activate_admin),
                                v -> activateAdmin());
                    }
                    addAction(getString(R.string.action_recheck),
                            v -> refreshOne(ProviderType.DEVICE_ADMIN));
                    break;
                default:
                    break;
            }
        }

        private void addAction(String text, View.OnClickListener listener) {
            MaterialButton button = (MaterialButton) LayoutInflater.from(PermissionsActivity.this)
                    .inflate(R.layout.button_provider_action, actions, false);
            button.setText(text);
            button.setOnClickListener(listener);
            actions.addView(button);
        }

        private void applyPill(ProviderStatus.State state) {
            switch (state) {
                case READY:
                    status.setTextColor(ContextCompat.getColor(PermissionsActivity.this, R.color.ok));
                    status.setBackgroundResource(R.drawable.bg_pill_ok);
                    break;
                case DENIED:
                case ERROR:
                    status.setTextColor(ContextCompat.getColor(PermissionsActivity.this, R.color.error));
                    status.setBackgroundResource(R.drawable.bg_pill_err);
                    break;
                case DISCONNECTED:
                case NOT_AUTHORIZED:
                case NOT_PAIRED:
                case NOT_RUNNING:
                case NOT_INSTALLED:
                    status.setTextColor(ContextCompat.getColor(PermissionsActivity.this, R.color.system_text));
                    status.setBackgroundResource(R.drawable.bg_pill_system);
                    break;
                default:
                    status.setTextColor(ContextCompat.getColor(PermissionsActivity.this, R.color.text_secondary));
                    status.setBackgroundResource(R.drawable.bg_pill_idle);
                    break;
            }
        }
    }

    private static String stateLabel(ProviderStatus.State state) {
        switch (state) {
            case READY:
                return "可用";
            case CHECKING:
                return "检测中";
            case NOT_AVAILABLE:
                return "不可用";
            case NOT_INSTALLED:
                return "未安装";
            case NOT_RUNNING:
                return "未运行";
            case NOT_AUTHORIZED:
                return "未授权";
            case DENIED:
                return "已拒绝";
            case NOT_PAIRED:
                return "未配对";
            case DISCONNECTED:
                return "未连接";
            case ERROR:
                return "异常";
            case UNKNOWN:
            default:
                return "未知";
        }
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 服务未运行", Toast.LENGTH_SHORT).show();
                return;
            }
            Shizuku.requestPermission(ShizukuProvider.REQUEST_PERMISSION);
        } catch (Throwable e) {
            Toast.makeText(this, "无法请求 Shizuku 授权：" + e.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openShizuku() {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage(ShizukuProvider.SHIZUKU_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            openShizukuWebsite();
        }
    }

    private void startAdbPairing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startPairingNotification();
        }
    }

    private void startPairingNotification() {
        AdbPairingService.start(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.adb_pairing_title)
                .setMessage("已开始监听配对请求。请在系统无线调试中打开“使用配对码配对设备”，"
                        + "然后下拉通知栏，在“无线调试配对”通知中点按输入六位配对码。配对码会随页面变化，请以通知中的最新端口为准。")
                .setPositiveButton(R.string.action_open_wireless, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开开发者选项", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
        refreshOne(ProviderType.ADB_WIRELESS);
    }

    private void openShizukuWebsite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://shizuku.rikka.app/")));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void activateAdmin() {
        ComponentName admin = FreezerDeviceAdminReceiver.component(this);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后本应用可以显示设备管理员状态；静默冻结仍需 Root、ADB 或 Shizuku。");
        adminLauncher.launch(intent);
    }

    private void deactivateAdmin() {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.removeActiveAdmin(FreezerDeviceAdminReceiver.component(this));
            refreshOne(ProviderType.DEVICE_ADMIN);
        } catch (Exception e) {
            Toast.makeText(this, "停用失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeviceOwnerInstructions() {
        String command = "adb shell dpm set-device-owner "
                + getPackageName() + "/.admin.FreezerDeviceAdminReceiver";
        String message = "设备所有者需要通过一次性 ADB 命令设置：\n\n" + command
                + "\n\n要求设备未添加账号且未完成初始设置。设置后本应用可使用隐藏/取消隐藏方式冻结应用，但不支持静默卸载。";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_setup_owner)
                .setMessage(message)
                .setPositiveButton(R.string.action_copy_command, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("command", command));
                        Toast.makeText(this, R.string.detail_copy_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void connectAdb() {
        showProgress("正在连接 ADB…");
        ioExecutor.execute(() -> {
            final OperationResult result = adbProvider().connectNow();
            mainHandler.post(() -> {
                dismissProgress();
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                refreshOne(ProviderType.ADB_WIRELESS);
            });
        });
    }

    private void disconnectAdb() {
        showProgress("正在断开 ADB…");
        ioExecutor.execute(() -> {
            final OperationResult result = adbProvider().disconnect();
            mainHandler.post(() -> {
                dismissProgress();
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                refreshOne(ProviderType.ADB_WIRELESS);
            });
        });
    }

    private AdbProvider adbProvider() {
        if (adbProvider == null) {
            adbProvider = (AdbProvider) providerManager.getProvider(ProviderType.ADB_WIRELESS);
        }
        return adbProvider;
    }

    private void showAdbPairingDialog() {
        AdbProvider provider = adbProvider();
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pairing, null, false);
        TextView status = content.findViewById(R.id.pair_status);
        TextInputEditText codeInput = content.findViewById(R.id.pair_code_input);
        MaterialButton openWireless = content.findViewById(R.id.btn_open_wireless);
        MaterialButton start = content.findViewById(R.id.btn_pair_start);
        MaterialButton cancel = content.findViewById(R.id.btn_pair_cancel);

        pairingHost = null;
        pairingPort = -1;
        pairingInProgress = false;

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.adb_pairing_title)
                .setView(content)
                .create();

        provider.startPairingDiscovery(new AdbProvider.PairingDiscoveryListener() {
            @Override
            public void onDiscovered(String host, int port) {
                mainHandler.post(() -> {
                    pairingHost = host;
                    pairingPort = port;
                    status.setText(getString(R.string.adb_pairing_found, host, port));
                });
            }

            @Override
            public void onLost() {
                mainHandler.post(() -> {
                    pairingHost = null;
                    pairingPort = -1;
                    status.setText(R.string.adb_pairing_lost);
                });
            }
        });

        openWireless.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开开发者选项", Toast.LENGTH_SHORT).show();
            }
        });

        start.setOnClickListener(v -> {
            if (pairingInProgress) {
                return;
            }
            String code = codeInput.getText() == null ? "" : codeInput.getText().toString().trim();
            if (code.length() != 6) {
                status.setText(R.string.adb_pairing_invalid_code);
                return;
            }
            if (pairingHost == null || pairingPort <= 0) {
                status.setText(R.string.adb_pairing_no_service);
                return;
            }
            pairingInProgress = true;
            start.setEnabled(false);
            status.setText("配对中…");
            final String host = pairingHost;
            final int port = pairingPort;
            ioExecutor.execute(() -> {
                final OperationResult result = adbProvider().pair(host, port, code);
                mainHandler.post(() -> {
                    pairingInProgress = false;
                    start.setEnabled(true);
                    status.setText(result.message);
                    if (result.success) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                        refreshOne(ProviderType.ADB_WIRELESS);
                        mainHandler.postDelayed(() -> {
                            if (pairingDialog != null && pairingDialog.isShowing()) {
                                pairingDialog.dismiss();
                            }
                        }, 1500);
                    } else {
                        refreshOne(ProviderType.ADB_WIRELESS);
                    }
                });
            });
        });

        cancel.setOnClickListener(v -> {
            if (pairingInProgress) {
                Toast.makeText(this, "正在配对，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            provider.stopPairingDiscovery();
            if (pairingDialog == d) {
                pairingDialog = null;
            }
        });
        pairingDialog = dialog;
        dialog.show();
    }

    private void showProgress(String message) {
        dismissProgress();
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null, false);
        TextView textView = content.findViewById(R.id.progress_text);
        textView.setText(message);
        progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            progressDialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
        }
        if (adbProvider != null) {
            adbProvider.stopPairingDiscovery();
        }
        dismissProgress();
        ioExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
