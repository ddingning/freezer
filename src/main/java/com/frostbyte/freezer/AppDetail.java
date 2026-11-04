package com.frostbyte.freezer;

import android.graphics.drawable.Drawable;

public final class AppDetail {
    public final String packageName;
    public final String label;
    public final String versionName;
    public final String sourceDir;
    public final String dataDir;
    public final Drawable icon;
    public final int uid;
    public final long versionCode;
    public final int targetSdk;
    public final long firstInstallTime;
    public final long lastUpdateTime;
    public final long apkSizeBytes;
    public final boolean apkSizeAvailable;
    public final boolean system;
    public final boolean pmEnabled;

    public AppDetail(String packageName, String label, String versionName,
                     String sourceDir, String dataDir, Drawable icon,
                     int uid, long versionCode, int targetSdk,
                     long firstInstallTime, long lastUpdateTime,
                     long apkSizeBytes, boolean apkSizeAvailable,
                     boolean system, boolean pmEnabled) {
        this.packageName = packageName;
        this.label = label;
        this.versionName = versionName;
        this.sourceDir = sourceDir;
        this.dataDir = dataDir;
        this.icon = icon;
        this.uid = uid;
        this.versionCode = versionCode;
        this.targetSdk = targetSdk;
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.apkSizeBytes = apkSizeBytes;
        this.apkSizeAvailable = apkSizeAvailable;
        this.system = system;
        this.pmEnabled = pmEnabled;
    }
}
