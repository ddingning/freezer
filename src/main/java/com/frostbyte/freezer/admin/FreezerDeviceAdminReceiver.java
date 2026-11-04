package com.frostbyte.freezer.admin;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

public final class FreezerDeviceAdminReceiver extends DeviceAdminReceiver {
    public static ComponentName component(Context context) {
        return new ComponentName(context.getApplicationContext(), FreezerDeviceAdminReceiver.class);
    }
}
