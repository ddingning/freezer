package com.frostbyte.freezer.provider;

import android.content.Context;
import android.content.SharedPreferences;

public final class FreezeSettings {
    public enum Mode {
        AUTO,
        DISABLE_USER,
        DISABLE,
        HIDE,
        KILL
    }

    private static final String PREFS = "freeze_settings";
    private static final String KEY_MODE = "mode";

    private FreezeSettings() {
    }

    public static Mode getMode(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = preferences.getString(KEY_MODE, Mode.AUTO.name());
        try {
            return Mode.valueOf(value);
        } catch (Exception e) {
            return Mode.AUTO;
        }
    }

    public static void setMode(Context context, Mode mode) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name())
                .apply();
    }
}
