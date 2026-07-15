package com.kiosk.app;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 定时控制广播接收器
 * - ACTION_SCREEN_OFF: 执行息屏（lockNow）
 * - BOOT_COMPLETED: 重新调度闹钟
 *
 * 亮屏由 AlarmManager 的 PendingIntent 直接启动 ScreenOnActivity，
 * 不经由此 Receiver（避免 Android 10+ 后台启动 Activity 限制）
 */
public class ScreenControlReceiver extends BroadcastReceiver {

    private static final String TAG = "ScreenControl";
    public static final String ACTION_SCREEN_OFF = "com.kiosk.app.ACTION_SCREEN_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "onReceive: " + action);

        switch (action) {
            case ACTION_SCREEN_OFF:
                lockScreen(context);
                break;

            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
                ScreenControlHelper.rescheduleAfterBoot(context);
                break;
        }
    }

    /**
     * 锁定屏幕（息屏）
     * 利用 DevicePolicyManager.lockNow() 立即锁屏
     */
    private void lockScreen(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);

        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
            Log.d(TAG, "Screen locked (lockNow)");

            // 锁屏后重新调度明天的息屏闹钟
            ScreenControlHelper.scheduleScreenOff(context);
        } else {
            Log.e(TAG, "lockScreen failed: device admin not active");
        }
    }
}
