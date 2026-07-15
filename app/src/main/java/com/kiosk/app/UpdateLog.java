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
 * 更新日志工具，写入文件，可通过 adb pull 拉取
 * 路径: /sdcard/Android/data/com.kiosk.app/files/kiosk_update.log
 */
public class UpdateLog {

    private static final String TAG = "KioskUpdate";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static String sLogPath = null;

    /** 初始化，必须在首次写日志前调用 */
    public static synchronized void init(Context context) {
        if (sLogPath != null) return;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                // fallback to internal
                dir = new File(context.getFilesDir(), "logs");
                dir.mkdirs();
            }
            sLogPath = new File(dir, "kiosk_update.log").getAbsolutePath();
            // 截断旧日志，保留最后 500KB
            File logFile = new File(sLogPath);
            if (logFile.exists() && logFile.length() > 500 * 1024) {
                logFile.delete();
            }
            write("I", "log path: " + sLogPath);
        } catch (Exception e) {
            Log.e(TAG, "UpdateLog init failed", e);
        }
    }

    private static synchronized void write(String level, String msg) {
        String ts = SDF.format(new Date());
        String line = ts + " [" + level + "] " + msg;
        Log.d(TAG, msg);
        if (sLogPath == null) {
            Log.w(TAG, "UpdateLog not initialized, log not written to file: " + msg);
            return;
        }
        try {
            FileWriter fw = new FileWriter(sLogPath, true);
            fw.write(line + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "Write log file failed: " + sLogPath, e);
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

    public static String getLogPath() {
        return sLogPath;
    }
}
