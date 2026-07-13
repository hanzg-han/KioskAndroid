package com.kiosk.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Kiosk 主 Activity
 * 核心功能:
 *   1. 全屏沉浸模式 - 隐藏状态栏和导航栏
 *   2. 禁用状态栏下拉 - 阻止下拉手势
 *   3. 禁用返回键/Home键（作为 Launcher 运行时）
 */
public class MainActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDisableStatusBarTask = new Runnable() {
        @Override
        public void run() {
            disableStatusBarExpansion();
            mHandler.postDelayed(this, 1000); // 每秒刷新一次
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hideSystemUI();
        disableStatusBarExpansion();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        disableStatusBarExpansion();
        // 启动定时刷新, 防止状态栏被意外恢复
        mHandler.postDelayed(mDisableStatusBarTask, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mDisableStatusBarTask);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            disableStatusBarExpansion();
        }
    }

    // ========== 全屏沉浸模式 ==========

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowInsetsController
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Android 10 及以下
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }

        // 始终启用的窗口标志
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ========== 禁用状态栏下拉 ==========

    @SuppressWarnings("deprecation")
    private void disableStatusBarExpansion() {
        // 方法1: StatusBarManager.disable() - 核心方法
        try {
            Object service = getSystemService("statusbar");
            if (service != null) {
                java.lang.reflect.Method method = service.getClass()
                        .getMethod("disable", int.class);

                int flags = 0;
                // STATUS_BAR_DISABLE_EXPAND = 0x00010000
                flags |= 0x00010000;
                // STATUS_BAR_DISABLE_NOTIFICATION_ICONS = 0x00020000
                flags |= 0x00020000;
                // STATUS_BAR_DISABLE_NOTIFICATION_ALERTS = 0x00040000
                flags |= 0x00040000;
                // STATUS_BAR_DISABLE_SYSTEM_INFO = 0x00100000
                flags |= 0x00100000;
                // STATUS_BAR_DISABLE_CLOCK = 0x00800000
                flags |= 0x00800000;

                method.invoke(service, flags);
            }
        } catch (Exception e) {
            android.util.Log.e("Kiosk", "disable statusbar failed: " + e.getMessage());
        }

        // 方法2: cmd statusbar send-disable-flag (作为备用)
        // 通过 ADB 或 root 权限执行, 这里仅供示例
    }

    // ========== 拦截返回键 ==========

    @Override
    public void onBackPressed() {
        // Kiosk 模式下禁用返回键
        // 如需退出, 可通过其他方式(如隐藏手势)
    }
}
