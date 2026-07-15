package com.kiosk.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 内网自更新辅助类
 * - 从用户配置的内网地址拉取 version.json 检查版本
 * - 下载 APK
 * - 通过 PackageInstaller 静默安装（Device Owner 权限）
 *
 * version.json 格式：
 * { "versionName": "1.0.11", "downloadUrl": "http://192.168.1.100:8080/app-release.apk" }
 */
public class UpdateHelper {

    private static final String TAG = "KioskUpdate";
    private static final String PREFS_NAME = "kiosk_update";
    private static final String KEY_SERVER_URL = "update_server_url";

    /** 默认服务器地址 */
    private static final String DEFAULT_SERVER_URL = "";

    /**
     * 更新回调
     */
    public interface UpdateCallback {
        void onCheckStart();
        void onNoUpdate();
        void onUpdateFound(String version, long fileSize);
        void onDownloadProgress(int percent);
        void onInstalling();
        void onInstallSuccess();
        void onError(String message);
    }

    // ========== 服务器地址配置 ==========

    public static String getServerUrl(Context ctx) {
        String url = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        return url;
    }

    public static void setServerUrl(Context ctx, String url) {
        // 去掉末尾斜杠
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, url).apply();
    }

    // ========== 版本检查 ==========

    public static void checkForUpdate(Context context, UpdateCallback callback) {
        String serverUrl = getServerUrl(context);
        if (serverUrl.isEmpty()) {
            if (callback != null) callback.onError("未配置更新服务器地址");
            return;
        }

        if (callback != null) callback.onCheckStart();

        new Thread(() -> {
            try {
                String versionUrl = serverUrl + "/version.json";
                Log.d(TAG, "Checking: " + versionUrl);

                HttpURLConnection conn = (HttpURLConnection) new URL(versionUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    runOnUi(callback, () -> callback.onError("服务器响应: " + code));
                    return;
                }

                String json = readStream(conn.getInputStream());
                conn.disconnect();

                JSONObject obj = new JSONObject(json);
                String remoteVersion = obj.getString("versionName");
                String downloadUrl = obj.getString("downloadUrl");
                long fileSize = obj.optLong("fileSize", 0);

                String currentVersion = getAppVersion(context);
                Log.d(TAG, "Current: " + currentVersion + ", Remote: " + remoteVersion);

                if (remoteVersion.equals(currentVersion)) {
                    runOnUi(callback, () -> callback.onNoUpdate());
                    return;
                }

                runOnUi(callback, () -> callback.onUpdateFound(remoteVersion, fileSize));

                // 下载 APK
                File apkFile = downloadApk(context, downloadUrl, fileSize, callback);
                if (apkFile == null) {
                    runOnUi(callback, () -> callback.onError("APK 下载失败"));
                    return;
                }

                // 静默安装
                runOnUi(callback, () -> callback.onInstalling());
                installApk(context, apkFile, callback);

            } catch (Exception e) {
                Log.e(TAG, "Update failed", e);
                runOnUi(callback, () -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * 下载 APK
     */
    private static File downloadApk(Context context, String urlStr, long totalSize,
                                     UpdateCallback callback) throws Exception {
        File file = new File(context.getFilesDir(), "update.apk");

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        // 处理重定向
        int redirects = 0;
        while (true) {
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER) {
                if (++redirects > 5) {
                    conn.disconnect();
                    return null;
                }
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setRequestMethod("GET");
                continue;
            }
            break;
        }

        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return null;
        }

        long contentLength = conn.getContentLength();
        if (contentLength <= 0) contentLength = totalSize;

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(file)) {

            byte[] buf = new byte[8192];
            long downloaded = 0;
            int lastPercent = -1;
            int bytesRead;

            while ((bytesRead = in.read(buf)) != -1) {
                out.write(buf, 0, bytesRead);
                downloaded += bytesRead;

                if (contentLength > 0) {
                    int percent = (int) (downloaded * 100 / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int p = percent;
                        runOnUi(callback, () -> callback.onDownloadProgress(p));
                    }
                }
            }
        }
        conn.disconnect();
        return file;
    }

    /**
     * 静默安装 APK（Device Owner 权限）
     */
    private static void installApk(Context context, File apkFile, UpdateCallback callback) {
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());

            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            try (InputStream in = new java.io.FileInputStream(apkFile);
                 OutputStream out = session.openWrite("package", 0, apkFile.length())) {

                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            // 提交安装，安装完成后自动启动 MainActivity
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            session.commit(pending.getIntentSender());
            session.close();

            Log.d(TAG, "Install committed, killing self to allow install");

            // 通知成功
            runOnUi(callback, () -> callback.onInstallSuccess());

            // 短暂延迟确保回调显示，然后主动杀进程让系统完成安装
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());

        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            runOnUi(callback, () -> callback.onError("安装失败: " + e.getMessage()));
        }
    }

    // ========== 工具方法 ==========

    private static String getAppVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private static String readStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void runOnUi(UpdateCallback callback, Runnable action) {
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(action);
        }
    }
}
