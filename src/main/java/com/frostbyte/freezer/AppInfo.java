package com.frostbyte.freezer;

import android.graphics.drawable.Drawable;

public final class AppInfo {
    public final String packageName;
    public final String label;
    public final String versionName;
    public final boolean system;
    public final boolean pmEnabled;
    public final int uid;
    public final Drawable icon;
    public boolean frozen;

    AppInfo(String packageName,
            String label,
            String versionName,
            boolean system,
            boolean pmEnabled,
            int uid,
            Drawable icon) {
        this.packageName = packageName;
        this.label = label;
        this.versionName = versionName;
        this.system = system;
        this.pmEnabled = pmEnabled;
        this.uid = uid;
        this.icon = icon;
        this.frozen = !pmEnabled;
    }
}
