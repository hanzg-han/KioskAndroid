package com.kiosk.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * PackageInstaller 安装结果接收器
 * - STATUS_SUCCESS: 安装成功，App 正在重启
 * - 其他状态: 记录错误日志
 */
public class UpdateResultReceiver extends BroadcastReceiver {

    private static final String TAG = "KioskUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        UpdateLog.init(context);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

        UpdateLog.i("===== UpdateResultReceiver.onReceive =====");
        UpdateLog.i("status=" + status + ", msg=" + msg + ", pkg=" + pkgName);
        UpdateLog.i("isDeviceOwner=" + isDeviceOwner(context));

        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                UpdateLog.i("STATUS_SUCCESS - launching MainActivity");
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                UpdateLog.e("STATUS_PENDING_USER_ACTION=" + msg);
                // 尝试通过 Device Owner 静默安装
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                UpdateLog.e("FAILURE_STORAGE: " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                UpdateLog.e("FAILURE_INVALID: " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                UpdateLog.e("FAILURE_CONFLICT: " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                UpdateLog.e("FAILURE_INCOMPATIBLE: " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                UpdateLog.e("FAILURE_BLOCKED: " + msg);
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                UpdateLog.e("FAILURE_ABORTED: " + msg);
                break;
            default:
                UpdateLog.e("UNKNOWN status=" + status + " msg=" + msg);
                break;
        }
    }

    private boolean isDeviceOwner(Context context) {
        boolean ret = false;
        try {
            android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ret = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Exception e) {
            // ignore
        }
        return ret;
    }
}
