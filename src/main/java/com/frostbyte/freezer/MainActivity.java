package com.frostbyte.freezer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.frostbyte.freezer.provider.Capability;
import com.frostbyte.freezer.provider.PrivilegeProvider;
import com.frostbyte.freezer.provider.ProviderManager;
import com.frostbyte.freezer.provider.ProviderStatus;
import com.frostbyte.freezer.provider.ProviderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements AppListAdapter.Listener {

    private static final String PROJECT_URL = "https://github.com/ddingning/freezer";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();

    private AppRepository repository;
    private AppListAdapter adapter;
    private ProviderManager providerManager;

    private View loadingView;
    private View emptyView;
    private TextView providerStatusView;
    private TextView summaryView;
    private TextInputEditText searchInput;

    private AlertDialog progressDialog;
    private ActivityResultLauncher<Intent> detailLauncher;

    private volatile boolean destroyed = false;
    private boolean firstResume = true;
    private boolean packageListHelpShown = false;
    private boolean visibilityReloadAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new AppRepository(this);
        providerManager = ProviderManager.get(this);
        adapter = new AppListAdapter(this, this);

        RecyclerView recyclerView = findViewById(R.id.recycler);
        loadingView = findViewById(R.id.loading);
        emptyView = findViewById(R.id.empty_state);
        providerStatusView = findViewById(R.id.provider_status);
        summaryView = findViewById(R.id.app_count);
        searchInput = findViewById(R.id.search_input);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);

        detailLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refresh();
                    }
                });

        providerStatusView.setOnClickListener(v -> openPermissions());
        findViewById(R.id.btn_permissions).setOnClickListener(v -> openPermissions());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.app_logo).setOnClickListener(v -> showProjectCard());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s == null ? "" : s.toString());
            }
        });

        ChipGroup chipGroup = findViewById(R.id.chip_group);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds == null || checkedIds.isEmpty()) {
                return;
            }
            int id = checkedIds.get(0);
            if (id == R.id.chip_user) {
                adapter.setFilter(AppListAdapter.Filter.USER);
            } else if (id == R.id.chip_system) {
                adapter.setFilter(AppListAdapter.Filter.SYSTEM);
            } else if (id == R.id.chip_frozen) {
                adapter.setFilter(AppListAdapter.Filter.FROZEN);
            } else {
                adapter.setFilter(AppListAdapter.Filter.ALL);
            }
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        refreshProviderStatuses();
    }

    private void openPermissions() {
        startActivity(new Intent(this, PermissionsActivity.class));
    }

    private void showProjectCard() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("项目主页")
                .setMessage(PROJECT_URL)
                .setPositiveButton("复制", (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("project_url", PROJECT_URL));
                        Toast.makeText(this, R.string.detail_copy_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void refresh() {
        final int currentGeneration = generation.incrementAndGet();
        packageListHelpShown = false;
        visibilityReloadAttempted = false;
        setProviderStatusChecking();
        if (adapter.getItemCount() == 0) {
            loadingView.setVisibility(View.VISIBLE);
        }
        updateEmptyState();

        ioExecutor.execute(() -> {
            PrivilegeProvider startup = providerManager.startupShellProvider();
            if (startup != null) {
                providerManager.fixPackageVisibility(startup);
            }
            final List<AppInfo> apps = repository.loadInstalledApps();

            mainHandler.post(() -> {
                if (destroyed || currentGeneration != generation.get()) {
                    return;
                }
                adapter.setAll(apps);
                loadingView.setVisibility(View.GONE);
                updateSummary();
                updateEmptyState();
                updateProviderBadge();
                refreshProviderStatusesInternal(currentGeneration);
            });
        });
    }

    private void refreshProviderStatuses() {
        final int currentGeneration = generation.get();
        refreshProviderStatusesInternal(currentGeneration);
    }

    private void refreshProviderStatusesInternal(final int currentGeneration) {
        providerManager.refreshAll(new ProviderManager.Listener() {
            @Override
            public void onStatus(ProviderType type, ProviderStatus status) {
                if (!destroyed && currentGeneration == generation.get()) {
                    updateProviderBadge();
                }
            }

            @Override
            public void onAllComplete() {
                if (destroyed || currentGeneration != generation.get()) {
                    return;
                }
                updateProviderBadge();
                applyFrozenState(currentGeneration);
                maybeFixVisibilityAndReload(currentGeneration);
            }
        });
    }

    private void maybeFixVisibilityAndReload(final int currentGeneration) {
        if (adapter.getAllApps().size() > 0 || visibilityReloadAttempted) {
            if (adapter.getAllApps().isEmpty() && !packageListHelpShown) {
                showPackageListHelp(providerManager.primaryForCapability(Capability.SHELL) != null);
            }
            return;
        }
        PrivilegeProvider shell = providerManager.primaryForCapability(Capability.SHELL);
        if (shell == null) {
            if (!packageListHelpShown) {
                showPackageListHelp(false);
            }
            return;
        }
        visibilityReloadAttempted = true;
        ioExecutor.execute(() -> {
            providerManager.fixPackageVisibility();
            final List<AppInfo> apps = repository.loadInstalledApps();
            mainHandler.post(() -> {
                if (destroyed || currentGeneration != generation.get()) {
                    return;
                }
                adapter.setAll(apps);
                updateSummary();
                updateEmptyState();
                if (apps.isEmpty() && !packageListHelpShown) {
                    showPackageListHelp(true);
                }
            });
        });
    }

    private void applyFrozenState(final int currentGeneration) {
        final List<AppInfo> snapshot = new ArrayList<>(adapter.getAllApps());
        if (snapshot.isEmpty()) {
            return;
        }
        ioExecutor.execute(() -> {
            final Set<String> frozen = providerManager.getFrozenPackages(snapshot);
            if (frozen == null) {
                return;
            }
            mainHandler.post(() -> {
                if (destroyed || currentGeneration != generation.get()) {
                    return;
                }
                adapter.applyFrozen(frozen);
                updateSummary();
                updateEmptyState();
            });
        });
    }

    private void updateSummary() {
        summaryView.setText(getString(R.string.summary,
                adapter.getAllApps().size(), adapter.getFrozenCount()));
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0
                && loadingView.getVisibility() != View.VISIBLE;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void setProviderStatusChecking() {
        providerStatusView.setText(R.string.provider_checking);
        providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        providerStatusView.setBackgroundResource(R.drawable.bg_pill_idle);
    }

    private void updateProviderBadge() {
        ProviderType primary = providerManager.primaryDisplayType();
        if (primary == null) {
            boolean checking = false;
            for (PrivilegeProvider provider : providerManager.getProviders()) {
                if (providerManager.getStatus(provider.getType()).state
                        == ProviderStatus.State.CHECKING) {
                    checking = true;
                    break;
                }
            }
            if (checking) {
                setProviderStatusChecking();
            } else {
                providerStatusView.setText(R.string.no_privilege);
                providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.error));
                providerStatusView.setBackgroundResource(R.drawable.bg_pill_err);
            }
            return;
        }

        if (primary == ProviderType.DEVICE_ADMIN) {
            providerStatusView.setText(R.string.device_admin_only);
            providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            providerStatusView.setBackgroundResource(R.drawable.bg_pill_idle);
            return;
        }

        ProviderStatus status = providerManager.getStatus(primary);
        if (!status.isReady()) {
            setProviderStatusChecking();
            return;
        }

        switch (primary) {
            case ROOT:
                providerStatusView.setText("Root 已授权");
                providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.ok));
                providerStatusView.setBackgroundResource(R.drawable.bg_pill_ok);
                break;
            case ADB_WIRELESS:
                providerStatusView.setText("ADB Shell 已连接");
                providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.system_text));
                providerStatusView.setBackgroundResource(R.drawable.bg_pill_system);
                break;
            case SHIZUKU:
                providerStatusView.setText("Shizuku 已授权");
                providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.system_text));
                providerStatusView.setBackgroundResource(R.drawable.bg_pill_system);
                break;
            case DEVICE_OWNER:
                providerStatusView.setText("设备所有者");
                providerStatusView.setTextColor(ContextCompat.getColor(this, R.color.ok));
                providerStatusView.setBackgroundResource(R.drawable.bg_pill_ok);
                break;
            default:
                setProviderStatusChecking();
                break;
        }
    }

    private void showPackageListHelp(boolean hasShellProvider) {
        if (packageListHelpShown || destroyed) {
            return;
        }
        packageListHelpShown = true;
        if (hasShellProvider) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.applist_help_title)
                    .setMessage("系统限制了读取应用列表的权限。已尝试通过当前权限通道自动授权，请重新打开应用以生效。")
                    .setPositiveButton(R.string.restart_now, (dialog, which) -> restartApp())
                    .setNegativeButton(R.string.quit, (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.applist_help_title)
                    .setMessage("系统限制了读取应用列表的权限。请先授予 Root、无线调试或 Shizuku 权限，或在系统设置中允许本应用读取应用列表。")
                    .setPositiveButton(R.string.retry, (dialog, which) -> refresh())
                    .setNegativeButton(R.string.open_app_settings,
                            (dialog, which) -> openSettingsForPackage(getPackageName()))
                    .show();
        }
    }

    private void restartApp() {
        showProgress(getString(R.string.restarting));
        ioExecutor.execute(() -> {
            boolean ok = providerManager.restartApp();
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                if (ok) {
                    Process.killProcess(Process.myPid());
                } else {
                    dismissProgress();
                    Toast.makeText(this, "无法自动重启，请手动重新打开应用", Toast.LENGTH_LONG).show();
                }
            });
        });
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

    private void openSettingsForPackage(String packageName) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onAppClick(AppInfo app) {
        Intent intent = new Intent(this, AppDetailActivity.class);
        intent.putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, app.packageName);
        detailLauncher.launch(intent);
    }

    @Override
    public void onDataChanged(int visibleCount) {
        updateEmptyState();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        generation.incrementAndGet();
        dismissProgress();
        ioExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
