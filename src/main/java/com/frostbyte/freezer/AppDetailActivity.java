package com.frostbyte.freezer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.frostbyte.freezer.provider.FreezeState;
import com.frostbyte.freezer.provider.OperationResult;
import com.frostbyte.freezer.provider.ProviderManager;
import com.frostbyte.freezer.provider.RunningState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDetailActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_UID = "uid";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ProviderManager providerManager;
    private AppRepository repository;
    private String packageName;
    private int userId;
    private int uidExtra = -1;

    private ImageView iconView;
    private TextView nameView;
    private TextView packageView;
    private TextView frozenPill;
    private TextView runningPill;
    private MaterialButton toggleButton;
    private MaterialButton uninstallButton;
    private MaterialButton openButton;
    private LinearLayout rowsContainer;

    private AppDetail detail;
    private FreezeState freezeState;
    private RunningState runningState;
    private TextView frozenValueView;
    private TextView runningValueView;

    private AlertDialog progressDialog;
    private volatile boolean destroyed = false;
    private boolean firstResume = true;
    private boolean changed = false;
    private int statusGeneration = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_detail);

        providerManager = ProviderManager.get(this);
        repository = new AppRepository(this);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        userId = getIntent().getIntExtra(EXTRA_USER_ID, 0);
        uidExtra = getIntent().getIntExtra(EXTRA_UID, -1);
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }

        iconView = findViewById(R.id.detail_icon);
        nameView = findViewById(R.id.detail_name);
        packageView = findViewById(R.id.detail_package);
        frozenPill = findViewById(R.id.detail_frozen_status);
        runningPill = findViewById(R.id.detail_running_status);
        toggleButton = findViewById(R.id.btn_toggle);
        uninstallButton = findViewById(R.id.btn_uninstall);
        openButton = findViewById(R.id.btn_open);
        rowsContainer = findViewById(R.id.detail_rows);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh_status).setOnClickListener(v -> loadStatuses());
        findViewById(R.id.btn_info).setOnClickListener(v -> openAppInfo());
        openButton.setOnClickListener(v -> launchApp());
        toggleButton.setOnClickListener(v -> onToggleClicked());
        uninstallButton.setOnClickListener(v -> confirmUninstall());

        loadDetail();
        loadStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        loadStatuses();
    }

    private void loadDetail() {
        ioExecutor.execute(() -> {
            final AppDetail loaded = repository.loadAppDetail(packageName);
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                if (loaded == null) {
                    Toast.makeText(this, "应用信息不可用", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                detail = loaded;
                bindDetail();
            });
        });
    }

    private void bindDetail() {
        iconView.setImageDrawable(detail.icon != null
                ? detail.icon
                : ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon));
        nameView.setText(detail.label);
        packageView.setText(userId == 0 ? detail.packageName
                : detail.packageName + " · 分身");

        rowsContainer.removeAllViews();
        frozenValueView = null;
        runningValueView = null;

        addRow(getString(R.string.detail_label_package), detail.packageName);
        addRow(getString(R.string.detail_label_source),
                detail.sourceDir.isEmpty() ? getString(R.string.detail_unavailable) : detail.sourceDir);
        addRow(getString(R.string.detail_label_data),
                detail.dataDir.isEmpty() ? getString(R.string.detail_unavailable) : detail.dataDir);
        addRow(getString(R.string.detail_label_uid),
                String.valueOf(uidExtra >= 0 ? uidExtra : detail.uid));
        addRow(getString(R.string.detail_label_user),
                userId == 0 ? getString(R.string.detail_user_primary)
                        : getString(R.string.detail_user_clone, userId));
        addRow(getString(R.string.detail_label_first_install),
                detail.firstInstallTime == 0 ? getString(R.string.detail_unavailable)
                        : formatTime(detail.firstInstallTime));
        addRow(getString(R.string.detail_label_last_update),
                detail.lastUpdateTime == 0 ? getString(R.string.detail_unavailable)
                        : formatTime(detail.lastUpdateTime));
        runningValueView = addRow(getString(R.string.detail_label_running),
                getString(R.string.detail_loading));
        frozenValueView = addRow(getString(R.string.detail_label_frozen),
                getString(R.string.detail_loading));
        addRow(getString(R.string.detail_label_system),
                detail.system ? getString(R.string.detail_yes) : getString(R.string.detail_no));
        addRow(getString(R.string.detail_label_apk_size),
                detail.apkSizeAvailable ? Formatter.formatFileSize(this, detail.apkSizeBytes)
                        : getString(R.string.detail_unavailable));
        addRow(getString(R.string.detail_label_version),
                detail.versionName.isEmpty() ? getString(R.string.detail_unavailable)
                        : detail.versionName);
        addRow(getString(R.string.detail_label_target_sdk), String.valueOf(detail.targetSdk));
        addRow(getString(R.string.detail_label_version_code), String.valueOf(detail.versionCode));

        if (userId == 0 && (freezeState == null
                || freezeState.state == FreezeState.State.UNSUPPORTED)) {
            freezeState = new FreezeState(
                    detail.pmEnabled ? FreezeState.State.NOT_FROZEN : FreezeState.State.FROZEN,
                    "PackageManager enabled=" + detail.pmEnabled);
            bindStatusValues();
        }
        updateActionButtons();
        updatePills();
    }

    private TextView addRow(String label, String value) {
        if (rowsContainer.getChildCount() > 0) {
            View divider = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            params.setMarginStart(dp(14));
            params.setMarginEnd(dp(14));
            divider.setLayoutParams(params);
            divider.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke));
            rowsContainer.addView(divider);
        }

        View row = LayoutInflater.from(this).inflate(R.layout.item_detail_row, rowsContainer, false);
        TextView labelView = row.findViewById(R.id.row_label);
        TextView valueView = row.findViewById(R.id.row_value);
        labelView.setText(label);
        valueView.setText(value);
        valueView.setOnLongClickListener(v -> copyValue(valueView.getText()));
        row.setOnLongClickListener(v -> copyValue(valueView.getText()));
        rowsContainer.addView(row);
        return valueView;
    }

    private boolean copyValue(CharSequence value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("freezer", value));
            Toast.makeText(this, R.string.detail_copy_done, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void loadStatuses() {
        final int generation = ++statusGeneration;
        setPillsChecking();
        ioExecutor.execute(() -> {
            FreezeState freeze = providerManager.queryFrozen(packageName, userId);
            RunningState running = providerManager.queryRunning(packageName, userId);
            FreezeState finalFreeze = freeze;
            if (userId == 0 && (freeze.state == FreezeState.State.UNSUPPORTED
                    || freeze.state == FreezeState.State.UNKNOWN) && detail != null) {
                finalFreeze = new FreezeState(
                        detail.pmEnabled ? FreezeState.State.NOT_FROZEN : FreezeState.State.FROZEN,
                        "PackageManager enabled=" + detail.pmEnabled
                                + (freeze.detail.isEmpty() ? "" : "；" + freeze.detail));
            }
            final FreezeState resolvedFreeze = finalFreeze;
            mainHandler.post(() -> {
                if (destroyed || generation != statusGeneration) {
                    return;
                }
                freezeState = resolvedFreeze;
                runningState = running;
                bindStatusValues();
                updateActionButtons();
                updatePills();
            });
        });
    }

    private void bindStatusValues() {
        if (frozenValueView != null && freezeState != null) {
            frozenValueView.setText(freezeDetailText());
        }
        if (runningValueView != null && runningState != null) {
            runningValueView.setText(runningDetailText());
        }
    }

    private String freezeDetailText() {
        if (freezeState == null) {
            return getString(R.string.detail_unknown);
        }
        switch (freezeState.state) {
            case FROZEN:
                return "已冻结（" + freezeState.detail + "）";
            case NOT_FROZEN:
                return "未冻结（" + freezeState.detail + "）";
            case UNSUPPORTED:
                return "不可用：" + freezeState.detail;
            case UNKNOWN:
            default:
                return "未知：" + freezeState.detail;
        }
    }

    private String runningDetailText() {
        if (runningState == null) {
            return getString(R.string.detail_unknown);
        }
        switch (runningState.state) {
            case RUNNING:
                return "运行中（" + runningState.detail + "）";
            case NOT_RUNNING:
                return "未运行（" + runningState.detail + "）";
            case UNSUPPORTED:
                return "无法检测：" + runningState.detail;
            case UNKNOWN:
            default:
                return "未知：" + runningState.detail;
        }
    }

    private void setPillsChecking() {
        frozenPill.setText(R.string.detail_loading);
        frozenPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        frozenPill.setBackgroundResource(R.drawable.bg_pill_idle);
        runningPill.setText(R.string.detail_loading);
        runningPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        runningPill.setBackgroundResource(R.drawable.bg_pill_idle);
        if (frozenValueView != null) {
            frozenValueView.setText(R.string.detail_loading);
        }
        if (runningValueView != null) {
            runningValueView.setText(R.string.detail_loading);
        }
    }

    private void updatePills() {
        if (freezeState != null) {
            switch (freezeState.state) {
                case FROZEN:
                    frozenPill.setText(R.string.status_frozen);
                    frozenPill.setTextColor(ContextCompat.getColor(this, R.color.frozen_text));
                    frozenPill.setBackgroundResource(R.drawable.bg_pill_frozen);
                    break;
                case NOT_FROZEN:
                    frozenPill.setText(R.string.status_not_frozen);
                    frozenPill.setTextColor(ContextCompat.getColor(this, R.color.ok));
                    frozenPill.setBackgroundResource(R.drawable.bg_pill_ok);
                    break;
                default:
                    frozenPill.setText(R.string.status_unknown);
                    frozenPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    frozenPill.setBackgroundResource(R.drawable.bg_pill_idle);
                    break;
            }
        }
        if (runningState != null) {
            switch (runningState.state) {
                case RUNNING:
                    runningPill.setText(R.string.status_running);
                    runningPill.setTextColor(ContextCompat.getColor(this, R.color.ok));
                    runningPill.setBackgroundResource(R.drawable.bg_pill_ok);
                    break;
                case NOT_RUNNING:
                    runningPill.setText(R.string.status_not_running);
                    runningPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    runningPill.setBackgroundResource(R.drawable.bg_pill_idle);
                    break;
                default:
                    runningPill.setText(R.string.status_unknown);
                    runningPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    runningPill.setBackgroundResource(R.drawable.bg_pill_idle);
                    break;
            }
        }
    }

    private void updateActionButtons() {
        boolean frozen = isEffectivelyFrozen();
        if (frozen) {
            toggleButton.setText(R.string.action_unfreeze);
            toggleButton.setIconResource(R.drawable.ic_lock_open);
        } else {
            toggleButton.setText(R.string.action_freeze);
            toggleButton.setIconResource(R.drawable.ic_snowflake);
        }

        openButton.setVisibility(View.VISIBLE);
    }

    private boolean isEffectivelyFrozen() {
        if (freezeState == null) {
            return userId == 0 && detail != null && !detail.pmEnabled;
        }
        if (freezeState.state == FreezeState.State.FROZEN) {
            return true;
        }
        if (freezeState.state == FreezeState.State.NOT_FROZEN) {
            return false;
        }
        return userId == 0 && detail != null && !detail.pmEnabled;
    }

    private void onToggleClicked() {
        final boolean unfreeze = isEffectivelyFrozen();
        showProgress(getString(unfreeze ? R.string.progress_unfreeze : R.string.progress_freeze,
                detail == null ? packageName : detail.label));
        ioExecutor.execute(() -> {
            final OperationResult result = unfreeze
                    ? providerManager.unfreeze(packageName, userId)
                    : providerManager.freeze(packageName, detail != null && detail.system, userId);
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                dismissProgress();
                if (result.success) {
                    changed = true;
                    setResult(RESULT_OK);
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                    loadDetail();
                    loadStatuses();
                } else {
                    showFailure(getString(unfreeze ? R.string.fail_unfreeze : R.string.fail_freeze),
                            result.message);
                }
            });
        });
    }

    private void confirmUninstall() {
        String label = detail == null ? packageName : detail.label;
        StringBuilder message = new StringBuilder();
        message.append("确定要卸载「").append(label).append("」吗？\n\n包名：").append(packageName);
        if (userId != 0) {
            message.append("\n用户：分身（").append(userId).append("）");
        }
        if (detail != null && detail.system) {
            message.append("\n\n").append(getString(R.string.uninstall_system_warning));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.uninstall_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.action_uninstall, (dialog, which) -> uninstall())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void uninstall() {
        showProgress(getString(R.string.progress_uninstall,
                detail == null ? packageName : detail.label));
        ioExecutor.execute(() -> {
            final OperationResult result = providerManager.uninstall(
                    packageName, detail != null && detail.system, userId);
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                dismissProgress();
                if (result.success) {
                    setResult(RESULT_OK);
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    showFailure(getString(R.string.fail_uninstall), result.message);
                }
            });
        });
    }

    private void launchApp() {
        if (userId != 0) {
            Toast.makeText(this, "分身应用请在对应分身环境中启动", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isEffectivelyFrozen()) {
            Toast.makeText(this, "应用已冻结，请先解冻", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(this, R.string.no_launch_intent, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_SHORT).show();
        }
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

    private void showFailure(String title, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(millis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        statusGeneration++;
        dismissProgress();
        ioExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
