package com.frostbyte.freezer;

public final class AppInfo {
    public final String packageName;
    public final String label;
    public final String versionName;
    public final boolean system;
    public final boolean pmEnabled;
    public final int uid;
    public final int userId;
    public boolean frozen;

    AppInfo(String packageName,
            String label,
            String versionName,
            boolean system,
            boolean pmEnabled,
            int uid,
            int userId) {
        this.packageName = packageName;
        this.label = label;
        this.versionName = versionName;
        this.system = system;
        this.pmEnabled = pmEnabled;
        this.uid = uid;
        this.userId = userId;
        this.frozen = !pmEnabled;
    }

    public String key() {
        return userId + ":" + packageName;
    }

    public boolean isClone() {
        return userId != 0;
    }
}
