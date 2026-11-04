package com.frostbyte.freezer.adb;

import android.content.Context;
import android.content.SharedPreferences;

public final class PairingState {
    public static final String PREF_NAME = "adb_pairing_state";
    public static final String KEY_PAIRED = "paired";
    public static final String KEY_PAIRED_AT = "paired_at";

    private final SharedPreferences preferences;

    public PairingState(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isPaired() {
        return preferences.getBoolean(KEY_PAIRED, false);
    }

    public void markPaired() {
        preferences.edit()
                .putBoolean(KEY_PAIRED, true)
                .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
