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
 * { "versionName": "1.0.13", "downloadUrl": "http://192.168.1.100:8080/app-release.apk" }
 */
public class UpdateHelper {

    private static final String TAG = "KioskUpdate";
    private static final String PREFS_NAME = "kiosk_update";
    private static final String KEY_SERVER_URL = "update_server_url";

    /** 默认服务器地址 */
    private static final String DEFAULT_SERVER_URL = "http://192.168.0.119";

    /**
     * 更新回调
     */
    public interface UpdateCallback {
        void onCheckStart();
        void onNoUpdate();
        void onUpdateFound(String version, long fileSize);
        void onDownloadProgress(int percent);
        /** 准备安装前调用，Activity 应在此退出 lockTaskMode 并 finish */
        void onBeforeInstall();
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
        UpdateLog.init(context);
        UpdateLog.i("===== checkForUpdate start =====");
        String serverUrl = getServerUrl(context);
        UpdateLog.i("serverUrl: " + serverUrl);
        if (serverUrl.isEmpty()) {
            UpdateLog.e("serverUrl is empty");
            if (callback != null) callback.onError("未配置更新服务器地址");
            return;
        }

        if (callback != null) callback.onCheckStart();

        new Thread(() -> {
            try {
                String versionUrl = serverUrl + "/version.json";
                UpdateLog.i("checking version: " + versionUrl);

                HttpURLConnection conn = (HttpURLConnection) new URL(versionUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int code = conn.getResponseCode();
                UpdateLog.i("version.json response code: " + code);
                if (code != 200) {
                    conn.disconnect();
                    UpdateLog.e("version.json response not 200: " + code);
                    runOnUi(callback, () -> callback.onError("服务器响应: " + code));
                    return;
                }

                String json = readStream(conn.getInputStream());
                conn.disconnect();
                UpdateLog.i("version.json content: " + json);

                JSONObject obj = new JSONObject(json);
                String remoteVersion = obj.getString("versionName");
                String downloadUrl = obj.getString("downloadUrl");
                long fileSize = obj.optLong("fileSize", 0);

                String currentVersion = getAppVersion(context);
                UpdateLog.i("current: " + currentVersion + ", remote: " + remoteVersion);

                if (remoteVersion.equals(currentVersion)) {
                    UpdateLog.i("same version, no update");
                    runOnUi(callback, () -> callback.onNoUpdate());
                    return;
                }

                UpdateLog.i("update found, downloadUrl: " + downloadUrl + ", fileSize: " + fileSize);
                runOnUi(callback, () -> callback.onUpdateFound(remoteVersion, fileSize));

                // 下载 APK
                File apkFile = downloadApk(context, downloadUrl, fileSize, callback);
                if (apkFile == null) {
                    UpdateLog.e("APK download returned null");
                    runOnUi(callback, () -> callback.onError("APK 下载失败"));
                    return;
                }
                UpdateLog.i("APK downloaded: " + apkFile.getAbsolutePath() + ", size: " + apkFile.length());

                // 让 Activity 退出 lockTaskMode 并关闭
                UpdateLog.i("calling onBeforeInstall...");
                runOnUiAndWait(callback, () -> callback.onBeforeInstall());
                UpdateLog.i("onBeforeInstall done");

                // 静默安装
                UpdateLog.i("calling onInstalling...");
                runOnUi(callback, () -> callback.onInstalling());
                installApk(context, apkFile, callback);

            } catch (Exception e) {
                UpdateLog.e("Update failed exception", e);
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
        UpdateLog.i("downloadApk: " + urlStr + " -> " + file.getAbsolutePath());

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        // 处理重定向
        int redirects = 0;
        while (true) {
            int status = conn.getResponseCode();
            UpdateLog.d("downloadApk response: " + status + ", redirects: " + redirects);
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER) {
                if (++redirects > 5) {
                    UpdateLog.e("too many redirects");
                    conn.disconnect();
                    return null;
                }
                String newUrl = conn.getHeaderField("Location");
                UpdateLog.d("redirect to: " + newUrl);
                conn.disconnect();
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setRequestMethod("GET");
                continue;
            }
            break;
        }

        if (conn.getResponseCode() != 200) {
            UpdateLog.e("downloadApk response not 200: " + conn.getResponseCode());
            conn.disconnect();
            return null;
        }

        long contentLength = conn.getContentLength();
        if (contentLength <= 0) contentLength = totalSize;
        UpdateLog.i("downloadApk contentLength: " + contentLength);

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

        UpdateLog.i("downloadApk complete, file size: " + file.length());
        if (file.length() <= 0) {
            UpdateLog.e("downloaded file is empty!");
            return null;
        }
        return file;
    }

    /**
     * 静默安装 APK（Device Owner 权限）
     */
    private static void installApk(Context context, File apkFile, UpdateCallback callback) {
        // 使用 ApplicationContext 确保在 Activity 销毁后仍可正常工作
        Context appCtx = context.getApplicationContext();
        UpdateLog.i("installApk: apkFile=" + apkFile.getAbsolutePath() + ", size=" + apkFile.length());
        UpdateLog.i("installApk: appCtx pkg=" + appCtx.getPackageName());
        try {
            PackageInstaller installer = appCtx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(appCtx.getPackageName());

            UpdateLog.i("installApk: creating session...");
            int sessionId = installer.createSession(params);
            UpdateLog.i("installApk: sessionId=" + sessionId);
            PackageInstaller.Session session = installer.openSession(sessionId);
            UpdateLog.i("installApk: session opened");

            try (InputStream in = new java.io.FileInputStream(apkFile);
                 OutputStream out = session.openWrite("package", 0, apkFile.length())) {

                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                }
                UpdateLog.i("installApk: wrote " + total + " bytes to session");
                session.fsync(out);
                UpdateLog.i("installApk: fsync done");
            }

            // 提交安装，安装完成后通过广播通知 UpdateResultReceiver 重启 App
            Intent intent = new Intent(appCtx, UpdateResultReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(
                    appCtx, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            UpdateLog.i("installApk: committing session...");
            session.commit(pending.getIntentSender());
            session.close();
            UpdateLog.i("installApk: session committed, waiting for system install");

            // 通知 UI
            runOnUi(callback, () -> callback.onInstallSuccess());

            // 短暂延迟后干净退出，让系统接管安装
            UpdateLog.i("installApk: waiting 1s then System.exit(0)");
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            UpdateLog.i("installApk: calling System.exit(0)");
            System.exit(0);

        } catch (Exception e) {
            UpdateLog.e("installApk failed", e);
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

    /**
     * 在主线程执行并等待完成
     */
    private static void runOnUiAndWait(UpdateCallback callback, Runnable action) {
        if (callback == null) return;
        final Object lock = new Object();
        final boolean[] done = {false};
        new Handler(Looper.getMainLooper()).post(() -> {
            action.run();
            synchronized (lock) {
                done[0] = true;
                lock.notify();
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try { lock.wait(); } catch (InterruptedException e) { break; }
            }
        }
    }
}
