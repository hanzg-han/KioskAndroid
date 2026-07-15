package com.kiosk.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * PackageInstaller 安装结果接收器
 * - STATUS_SUCCESS: 安装成功，App 正在重启
 * - 其他状态: 记录错误日志
 */
public class UpdateResultReceiver extends BroadcastReceiver {

    private static final String TAG = "KioskUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                Log.d(TAG, "Install success, launching MainActivity");
                // 安装成功，启动 MainActivity
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Log.e(TAG, "Install failed: storage error - " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                Log.e(TAG, "Install failed: invalid APK - " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                Log.e(TAG, "Install failed: conflict - " + msg);
                break;
            default:
                Log.e(TAG, "Install status: " + status + " - " + msg);
                break;
        }
    }
}
