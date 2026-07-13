package com.kiosk.app;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        // 设备管理员激活后自动启用 Lock Task
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);
        if (dpm.isDeviceOwnerApp(context.getPackageName())) {
            dpm.setLockTaskPackages(admin,
                    new String[]{context.getPackageName()});
        }
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // 设备管理员被禁用
    }
}
