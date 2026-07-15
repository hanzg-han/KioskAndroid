package com.kiosk.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;
import android.util.Log;

/**
 * 透明 Activity，用于定时亮屏
 * 由 AlarmManager 的 PendingIntent 直接启动，利用系统 Window flag 唤醒屏幕后立即结束
 * 可靠绕过 Android 10+ 后台启动 Activity 限制
 */
public class ScreenOnActivity extends Activity {

    private static final String TAG = "ScreenControl";
    private PowerManager.WakeLock mWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "ScreenOnActivity: waking up screen");

        // 设置 Window flag 唤醒屏幕
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        // 额外用 WakeLock 确保屏幕持续亮起
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                    "Kiosk:ScreenOn");
            mWakeLock.acquire(30_000); // 30 秒，足够屏幕完全亮起
        }

        // 重新调度下一次亮屏
        ScreenControlHelper.scheduleScreenOn(this);

        // 延迟 finish，确保屏幕已亮起
        new android.os.Handler().postDelayed(this::finish, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }
}
