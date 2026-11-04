package com.frostbyte.freezer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class AppRepository {

    private final Context context;
    private final PackageManager packageManager;

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
    }

    List<AppInfo> loadInstalledApps() {
        List<ApplicationInfo> installed;
        try {
            installed = packageManager.getInstalledApplications(
                    PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Exception e) {
            installed = packageManager.getInstalledApplications(0);
        }

        String self = context.getPackageName();
        List<AppInfo> result = new ArrayList<>(installed.size());
        for (ApplicationInfo info : installed) {
            if (info.packageName.equals(self)) {
                continue;
            }
            String label = info.packageName;
            try {
                CharSequence applicationLabel = packageManager.getApplicationLabel(info);
                if (applicationLabel != null && applicationLabel.length() > 0) {
                    label = applicationLabel.toString().trim();
                }
            } catch (Exception ignored) {
            }
            if (label.isEmpty()) {
                label = info.packageName;
            }

            String version = "";
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(info.packageName, 0);
                if (packageInfo.versionName != null) {
                    version = packageInfo.versionName;
                }
            } catch (Exception ignored) {
            }

            Drawable icon;
            try {
                icon = packageManager.getApplicationIcon(info);
            } catch (Exception e) {
                icon = packageManager.getDefaultActivityIcon();
            }

            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

            result.add(new AppInfo(
                    info.packageName,
                    label,
                    version,
                    system,
                    info.enabled,
                    info.uid,
                    icon));
        }

        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(result, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo left, AppInfo right) {
                int byLabel = collator.compare(left.label, right.label);
                if (byLabel != 0) {
                    return byLabel;
                }
                return left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        return result;
    }

    AppDetail loadAppDetail(String packageName) {
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                packageInfo = packageManager.getPackageInfo(
                        packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        ApplicationInfo info = packageInfo.applicationInfo;
        if (info == null) {
            return null;
        }

        String label = packageName;
        try {
            CharSequence applicationLabel = packageManager.getApplicationLabel(info);
            if (applicationLabel != null && applicationLabel.length() > 0) {
                label = applicationLabel.toString();
            }
        } catch (Exception ignored) {
        }

        Drawable icon;
        try {
            icon = packageManager.getApplicationIcon(info);
        } catch (Exception e) {
            icon = packageManager.getDefaultActivityIcon();
        }

        long apkSize = 0L;
        boolean apkSizeAvailable = false;
        if (info.sourceDir != null) {
            File base = new File(info.sourceDir);
            if (base.exists()) {
                apkSize += base.length();
                apkSizeAvailable = true;
            }
        }
        String[] splits = info.splitSourceDirs;
        if (splits != null) {
            for (String split : splits) {
                if (split == null) {
                    continue;
                }
                File file = new File(split);
                if (file.exists()) {
                    apkSize += file.length();
                    apkSizeAvailable = true;
                }
            }
        }

        long versionCode = packageInfo.versionCode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionCode = packageInfo.getLongVersionCode();
        }

        boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

        return new AppDetail(
                info.packageName,
                label,
                packageInfo.versionName == null ? "" : packageInfo.versionName,
                info.sourceDir == null ? "" : info.sourceDir,
                info.dataDir == null ? "" : info.dataDir,
                icon,
                info.uid,
                versionCode,
                info.targetSdkVersion,
                packageInfo.firstInstallTime,
                packageInfo.lastUpdateTime,
                apkSize,
                apkSizeAvailable,
                system,
                info.enabled);
    }
}
