package com.kiosk.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 更新日志工具
 * 主路径(内部存储, 无需权限): /data/data/com.kiosk.app/files/kiosk_update.log
 *   读取方式: adb shell run-as com.kiosk.app cat files/kiosk_update.log
 * 备用路径(外置存储, 无需权限): /sdcard/Android/data/com.kiosk.app/files/kiosk_update.log
 *   读取方式: adb pull /sdcard/Android/data/com.kiosk.app/files/kiosk_update.log
 */
public class UpdateLog {

    private static final String TAG = "KioskUpdate";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static String sPrimaryPath = null;   // 内部存储，一定可用
    private static String sExtPath = null;       // /sdcard/，不一定可用

    public static synchronized void init(Context context) {
        if (sPrimaryPath != null) return;
        try {
            // 主路径: 内部存储，100% 可用，无需权限
            File internalDir = new File(context.getFilesDir(), "logs");
            internalDir.mkdirs();
            sPrimaryPath = new File(internalDir, "kiosk_update.log").getAbsolutePath();

            // 截断旧日志
            File logFile = new File(sPrimaryPath);
            if (logFile.exists() && logFile.length() > 500 * 1024) {
                logFile.delete();
            }

            // 备用路径: 应用专属外部存储，无需权限
            File extDir = context.getExternalFilesDir("logs");
            if (extDir != null) {
                extDir.mkdirs();
                sExtPath = new File(extDir, "kiosk_update.log").getAbsolutePath();
                try {
                    File extFile = new File(sExtPath);
                    if (extFile.exists() && extFile.length() > 500 * 1024) {
                        extFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            String initMsg = "UpdateLog init: primary=" + sPrimaryPath + ", ext=" + sExtPath;
            Log.i(TAG, initMsg);
            writeRaw(sPrimaryPath, "I", initMsg);
        } catch (Exception e) {
            Log.e(TAG, "UpdateLog init failed", e);
        }
    }

    private static synchronized void write(String level, String msg) {
        String ts = SDF.format(new Date());
        String line = ts + " [" + level + "] " + msg;
        // 始终输出到 logcat
        Log.d(TAG, msg);

        // 写入主路径
        if (sPrimaryPath != null) {
            writeRaw(sPrimaryPath, null, line + "\n");
        }

        // 尝试写入 /sdcard/
        if (sExtPath != null) {
            writeRaw(sExtPath, null, line + "\n");
        }
    }

    /** 底层写入，不依赖 level/time 格式化 */
    private static void writeRaw(String path, String level, String content) {
        try {
            FileWriter fw = new FileWriter(path, true);
            if (level != null) {
                String ts = SDF.format(new Date());
                fw.write(ts + " [" + level + "] " + content + "\n");
            } else {
                fw.write(content);
            }
            fw.close();
        } catch (Exception ignore) {
            // 写入失败不崩溃
        }
    }

    public static void d(String msg) { write("D", msg); }
    public static void i(String msg) { write("I", msg); }
    public static void w(String msg) { write("W", msg); }
    public static void e(String msg) { write("E", msg); }

    public static void e(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.close();
        write("E", msg + "\n" + sw.toString());
    }

    public static String getPrimaryPath() { return sPrimaryPath; }
    public static String getExtPath() { return sExtPath; }
}
