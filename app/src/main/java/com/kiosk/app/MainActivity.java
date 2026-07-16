package com.kiosk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kiosk 主 Activity
 * 使用屏幕固定 (Lock Task Mode) 实现真正的 Kiosk 锁定
 */
public class MainActivity extends Activity {

    private static final String APP_VERSION = "1.0.34";

    private static final int DPI_DEFAULT = 240;  // 默认 DPI（隐藏导航栏时）
    private static final int DPI_NAVBAR  = 200;  // 显示导航栏时的 DPI

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DevicePolicyManager mDpm;
    private ComponentName mAdminComponent;
    private Button mBtnNavBar;
    private boolean mSystemBarsHidden = true; // 默认隐藏系统导航栏
    private TextView mTvScheduleInfo; // 定时状态显示

    // 语音识别相关
    private TextView mTvAsrStatus;
    private TextView mTvAsrText;
    private ImageView mIvVideo;
    private TextView mTvLogContent;
    private ScrollView mSvLog;
    private SpeechManager mSpeechManager;
    private final SimpleDateFormat mLogTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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
        initSpeechRecognition();
        setupNavButtons();
    }

    /**
     * 初始化语音识别
     */
    private void initSpeechRecognition() {
        mTvAsrStatus = findViewById(R.id.tv_asr_status);
        mTvAsrText = findViewById(R.id.tv_asr_text);
        mIvVideo = findViewById(R.id.iv_video);
        mTvLogContent = findViewById(R.id.tv_log_content);
        mSvLog = findViewById(R.id.sv_log);

        mTvLogContent.setMovementMethod(new ScrollingMovementMethod());
        mTvAsrStatus.setText("语音识别就绪 (VAD驱动) | v" + APP_VERSION);

        appendLog("系统", "初始化完成，开始连接音频+视频...");

        mSpeechManager = SpeechManager.getInstance();
        mSpeechManager.init(new SpeechManager.SpeechCallback() {
            @Override
            public void onWakeup() {
                mTvAsrStatus.setText("VAD_BOS | 请说话...");
                mTvAsrStatus.setTextColor(Color.parseColor("#4CAF50"));
                mTvAsrText.setText("");
                appendLog("音频", "VAD_BOS 开始说话");
            }

            @Override
            public void onSleep() {
                mTvAsrStatus.setText("VAD_EOS | 识别完成");
                mTvAsrStatus.setTextColor(Color.parseColor("#1976D2"));
                appendLog("音频", "VAD_EOS 结束说话");
            }

            @Override
            public void onIatResult(String text, boolean isFinal) {
                mTvAsrText.setText(text);
                if (isFinal) {
                    appendLog("ASR", "识别结果: " + text);
                }
            }

            @Override
            public void onVadChanged(int vadStatus) {
                switch (vadStatus) {
                    case AiuiProtocol.VAD_BOS:
                    case AiuiProtocol.VAD_EOS:
                    case AiuiProtocol.VAD_VOL:
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onVideoFrame(Bitmap bitmap) {
                mIvVideo.setImageBitmap(bitmap);
            }

            @Override
            public void onError(String message) {
                mTvAsrStatus.setText("错误: " + message);
                mTvAsrStatus.setTextColor(Color.RED);
                appendLog("错误", message);
            }

            @Override
            public void onLog(String tag, String message) {
                appendLog(tag, message);
            }
        });

        // 自动连接
        mSpeechManager.connect();

        UpdateLog.i("SpeechManager VAD-driven mode, Audio=" + AiuiProtocol.DEFAULT_LOCAL_IP +
                    ", Video=" + AiuiProtocol.DEFAULT_LOCAL_IP +
                    ", QwenASR=" + AsrWebSocketClient.DEFAULT_ASR_WS_URL);
    }

    /**
     * 追加日志到消息框
     */
    private void appendLog(String tag, String message) {
        String timestamp = mLogTimeFormat.format(new Date());
        String line = "[" + timestamp + "][" + tag + "] " + message + "\n";
        mTvLogContent.append(line);

        // 自动滚动到底部
        mSvLog.post(() -> mSvLog.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 初始化底部导航按钮
     */
    private void setupNavButtons() {
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

        // 退出按钮：单击退出，完全终止进程
        Button btnExit = findViewById(R.id.btn_exit);
        btnExit.setOnClickListener(v -> {
            if (mSpeechManager != null) {
                mSpeechManager.disconnect();
            }
            finishAffinity();
            // 彻底杀掉进程，避免残留后台服务
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        // ========== 定时息屏/亮屏 ==========
        mTvScheduleInfo = findViewById(R.id.tv_schedule_info);
        updateScheduleDisplay();

        // 设置息屏时间
        Button btnScreenOff = findViewById(R.id.btn_set_screen_off);
        btnScreenOff.setOnClickListener(v -> showTimePicker(true));

        // 设置亮屏时间
        Button btnScreenOn = findViewById(R.id.btn_set_screen_on);
        btnScreenOn.setOnClickListener(v -> showTimePicker(false));

        // 清除定时
        Button btnClear = findViewById(R.id.btn_clear_schedule);
        btnClear.setOnClickListener(v -> {
            ScreenControlHelper.clearScreenOffTime(this);
            ScreenControlHelper.clearScreenOnTime(this);
            updateScheduleDisplay();
            Toast.makeText(this, "定时已清除", Toast.LENGTH_SHORT).show();
        });

        // ========== 自更新 ==========
        final TextView tvUpdateInfo = findViewById(R.id.tv_update_info);
        updateDisplay(tvUpdateInfo);

        // 配置更新服务器地址
        Button btnUpdateConfig = findViewById(R.id.btn_update_config);
        btnUpdateConfig.setOnClickListener(v -> showUpdateConfigDialog(tvUpdateInfo));

        // 检查更新
        Button btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnCheckUpdate.setOnClickListener(v -> doCheckUpdate(tvUpdateInfo));
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupLockTaskPackages();
        if (mSystemBarsHidden) {
            hideSystemUI();  // 内部会调用 enterLockTask()
        }
        updateScheduleDisplay();
        mHandler.postDelayed(mKeepFullscreenTask, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSpeechManager != null) {
            mSpeechManager.disconnect();
        }
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
     * 进入锁定模式（仅标记，实际不调用 startLockTask 避免系统提示框）
     * DPM setLockTaskPackages 已通过 Device Owner 策略锁定，无需 startLockTask
     */
    private void enterLockTask() {
        // 不调用 startLockTask()，避免弹出"应用已固定"的系统提示框
        // Device Owner 的 setLockTaskPackages 已足够阻止用户退出
    }

    /**
     * 退出锁定模式
     */
    private void exitLockTask() {
        try {
            stopLockTask();
        } catch (Exception e) {
            // 可能未处于 Lock Task 状态，忽略
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
            UpdateLog.e("setDisplayDensity failed: " + e.getMessage());
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

    // ========== 定时息屏/亮屏 ==========

    /**
     * 显示时间选择器
     * @param isScreenOff true=息屏时间, false=亮屏时间
     */
    private void showTimePicker(boolean isScreenOff) {
        String title = isScreenOff ? "设置息屏时间" : "设置亮屏时间";
        int defaultHour = isScreenOff
                ? ScreenControlHelper.getScreenOffHour(this)
                : ScreenControlHelper.getScreenOnHour(this);
        int defaultMinute = isScreenOff
                ? ScreenControlHelper.getScreenOffMinute(this)
                : ScreenControlHelper.getScreenOnMinute(this);

        // 默认值处理
        if (defaultHour == ScreenControlHelper.NOT_SET) {
            defaultHour = isScreenOff ? 22 : 6;  // 息屏默认 22:00, 亮屏默认 06:00
            defaultMinute = 0;
        }

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (TimePicker view, int hourOfDay, int minute) -> {
                    onTimeSet(isScreenOff, hourOfDay, minute);
                },
                defaultHour, defaultMinute, true); // true = 24小时制
        dialog.setTitle(title);
        dialog.show();
    }

    /**
     * 时间选择回调
     */
    private void onTimeSet(boolean isScreenOff, int hour, int minute) {
        if (isScreenOff) {
            ScreenControlHelper.saveScreenOffTime(this, hour, minute);
        } else {
            ScreenControlHelper.saveScreenOnTime(this, hour, minute);
        }
        updateScheduleDisplay();
        String label = isScreenOff ? "息屏" : "亮屏";
        Toast.makeText(this, label + "时间已设为 " + String.format("%02d:%02d", hour, minute),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 更新定时状态显示
     */
    private void updateScheduleDisplay() {
        if (mTvScheduleInfo == null) return;

        String off = ScreenControlHelper.formatTime(
                ScreenControlHelper.getScreenOffHour(this),
                ScreenControlHelper.getScreenOffMinute(this));
        String on = ScreenControlHelper.formatTime(
                ScreenControlHelper.getScreenOnHour(this),
                ScreenControlHelper.getScreenOnMinute(this));

        mTvScheduleInfo.setText("息屏: " + off + " | 亮屏: " + on);
    }

    // ========== 自更新 ==========

    private void updateDisplay(TextView tv) {
        String url = UpdateHelper.getServerUrl(this);
        if (url.isEmpty()) {
            tv.setText("更新: 未配置");
        } else {
            tv.setText("更新: " + url + "\n当前: v" + APP_VERSION);
        }
    }

    /**
     * 显示配置对话框
     */
    private void showUpdateConfigDialog(TextView tvUpdateInfo) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.100:8080");
        input.setText(UpdateHelper.getServerUrl(this));

        new AlertDialog.Builder(this)
                .setTitle("配置更新服务器地址")
                .setMessage("设置内网服务器地址，App 将检查\n{地址}/version.json 获取最新版本")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    UpdateHelper.setServerUrl(this, url);
                    updateDisplay(tvUpdateInfo);
                    Toast.makeText(this, url.isEmpty() ? "已清除" : "已保存: " + url,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行更新检查
     */
    private void doCheckUpdate(TextView tvUpdateInfo) {
        UpdateHelper.checkForUpdate(this, new UpdateHelper.UpdateCallback() {
            @Override
            public void onCheckStart() {
                Toast.makeText(MainActivity.this, "正在检查更新...", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNoUpdate() {
                Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onUpdateFound(String version, long fileSize) {
                String sizeStr = fileSize > 0 ? String.format(" (%.1f MB)", fileSize / 1048576.0) : "";
                Toast.makeText(MainActivity.this,
                        "发现新版本 v" + version + sizeStr + "，开始下载...",
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onDownloadProgress(int percent) {
                if (percent % 20 == 0 || percent >= 100) {
                    Toast.makeText(MainActivity.this,
                            "下载中 " + percent + "%", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onInstalling() {
                Toast.makeText(MainActivity.this, "正在安装，即将重启...",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onBeforeInstall() {
                // 退出 lockTaskMode，让系统能正常替换 APK
                UpdateLog.i("onBeforeInstall: stopping lockTask...");
                try {
                    MainActivity.this.stopLockTask();
                    UpdateLog.i("onBeforeInstall: stopLockTask OK");
                } catch (Exception e) {
                    UpdateLog.e("onBeforeInstall: stopLockTask failed", e);
                }
                // 移到后台，避免用户误操作
                MainActivity.this.moveTaskToBack(true);
                UpdateLog.i("onBeforeInstall: moveTaskToBack done");
            }

            @Override
            public void onInstallSuccess() {
                // PackageInstaller 提交成功，系统将重启 App
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "更新失败: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
