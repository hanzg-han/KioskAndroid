package com.kiosk.app;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Toast;

/**
 * Kiosk 主 Activity
 * 使用屏幕固定 (Lock Task Mode) 实现真正的 Kiosk 锁定
 */
public class MainActivity extends Activity {

    private static final String APP_VERSION = "1.0.6";

    private static final int DPI_DEFAULT = 240;  // 默认 DPI（隐藏导航栏时）
    private static final int DPI_NAVBAR  = 200;  // 显示导航栏时的 DPI

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DevicePolicyManager mDpm;
    private ComponentName mAdminComponent;
    private WebView mWebView;
    private Button mBtnNavBar;
    private int mExitClickCount = 0;
    private long mExitLastClickTime = 0;
    private boolean mSystemBarsHidden = true; // 默认隐藏系统导航栏

    private final Runnable mKeepFullscreenTask = new Runnable() {
        @Override
        public void run() {
            if (mSystemBarsHidden) {
                hideSystemUI();
            }
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mAdminComponent = new ComponentName(this, KioskDeviceAdminReceiver.class);

        setupLockTaskPackages();
        hideSystemUI();
        showVersion();
        setupNavButtons();
    }

    /**
     * 在页面显示版本号
     */
    private void showVersion() {
        android.widget.TextView tvStatus = findViewById(R.id.tv_status);
        if (tvStatus != null) {
            tvStatus.setText("Kiosk 模式已启动\n状态栏下拉已禁用\n版本: " + APP_VERSION);
        }
    }

    /**
     * 初始化底部导航按钮
     */
    private void setupNavButtons() {
        mWebView = findViewById(R.id.webview);

        // 返回
        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWebView != null && mWebView.canGoBack()) {
                    mWebView.goBack();
                }
            }
        });

        // 主页
        Button btnHome = findViewById(R.id.btn_home);
        btnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWebView != null && mWebView.getUrl() != null) {
                    mWebView.reload();
                }
            }
        });

        // 刷新
        Button btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWebView != null && mWebView.getUrl() != null) {
                    mWebView.reload();
                } else {
                    recreate();
                }
            }
        });

        // 显示/隐藏系统导航栏
        mBtnNavBar = findViewById(R.id.btn_navbar);
        updateNavBarButtonText();
        mBtnNavBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSystemBarsHidden = !mSystemBarsHidden;
                if (mSystemBarsHidden) {
                    hideSystemUI();
                } else {
                    showSystemUI();
                }
                updateNavBarButtonText();
            }
        });

        // 退出（连点 5 次才能退出，防止误触）
        Button btnExit = findViewById(R.id.btn_exit);
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - mExitLastClickTime > 3000) {
                    mExitClickCount = 0;
                }
                mExitLastClickTime = now;
                mExitClickCount++;

                int remain = 5 - mExitClickCount;
                if (remain > 0) {
                    Toast.makeText(MainActivity.this,
                            "再点击 " + remain + " 次退出 Kiosk 模式",
                            Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        stopLockTask();
                    } catch (Exception e) {
                        // ignore
                    }
                    finishAffinity();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupLockTaskPackages();
        if (mSystemBarsHidden) {
            hideSystemUI();  // 内部会调用 enterLockTask()
        }
        mHandler.postDelayed(mKeepFullscreenTask, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mKeepFullscreenTask);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mSystemBarsHidden) {
            hideSystemUI();
        }
    }

    /**
     * 设置 Lock Task 白名单（仅设置 DPM 策略，不调用 startLockTask）
     */
    private void setupLockTaskPackages() {
        if (mDpm != null && mDpm.isDeviceOwnerApp(getPackageName())) {
            mDpm.setLockTaskPackages(mAdminComponent, new String[]{getPackageName()});
        }
    }

    /**
     * 进入锁定模式
     */
    private void enterLockTask() {
        try {
            startLockTask();
        } catch (Exception e) {
            android.util.Log.e("Kiosk", "startLockTask failed: " + e.getMessage());
        }
    }

    /**
     * 退出锁定模式
     */
    private void exitLockTask() {
        try {
            stopLockTask();
        } catch (Exception e) {
            android.util.Log.e("Kiosk", "stopLockTask failed: " + e.getMessage());
        }
    }

    /**
     * 隐藏系统 UI（状态栏 + 导航栏）并进入 Lock Task
     */
    private void hideSystemUI() {
        // 恢复默认 DPI
        setDisplayDensity(DPI_DEFAULT);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View decorView = getWindow().getDecorView();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }

        // 重新进入 Lock Task 模式
        enterLockTask();
    }

    /**
     * 通过 shell 设置屏幕 DPI
     */
    private void setDisplayDensity(int density) {
        try {
            Runtime.getRuntime().exec("wm density " + density);
        } catch (Exception e) {
            android.util.Log.e("Kiosk", "setDisplayDensity failed: " + e.getMessage());
        }
    }

    /**
     * 显示系统 UI（状态栏 + 导航栏）
     * 核心：降低 DPI 为系统导航栏腾出空间，再退出 Lock Task 并显示系统栏
     */
    private void showSystemUI() {
        setDisplayDensity(DPI_NAVBAR);
        exitLockTask();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        Toast.makeText(this, "已显示系统导航栏", Toast.LENGTH_SHORT).show();
    }

    /**
     * 根据当前状态更新导航栏按钮文字
     */
    private void updateNavBarButtonText() {
        if (mBtnNavBar != null) {
            mBtnNavBar.setText(mSystemBarsHidden ? "显示导航栏" : "隐藏导航栏");
        }
    }

    @Override
    public void onBackPressed() {
        // Kiosk 模式：禁用返回键
    }
}
