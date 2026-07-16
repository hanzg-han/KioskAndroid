package com.kiosk.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志工具 —— 仅写文件，不输出到 logcat
 * 主路径(内部存储): /data/data/com.kiosk.app/files/logs/kiosk_update.log
 *   读取: adb shell run-as com.kiosk.app cat files/logs/kiosk_update.log
 * 备用路径(外部存储): /sdcard/Android/data/com.kiosk.app/files/logs/kiosk_update.log
 *   读取: adb pull /sdcard/Android/data/com.kiosk.app/files/logs/kiosk_update.log .
 */
public class UpdateLog {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static String sPrimaryPath = null;   // 内部存储，一定可用
    private static String sExtPath = null;       // 外部存储，不一定可用

    public static synchronized void init(Context context) {
        if (sPrimaryPath != null) return;
        try {
            // 主路径: 内部存储，100% 可用，无需权限
            File internalDir = new File(context.getFilesDir(), "logs");
            internalDir.mkdirs();
            sPrimaryPath = new File(internalDir, "kiosk_update.log").getAbsolutePath();

            // 每次启动自动清理旧日志
            File logFile = new File(sPrimaryPath);
            if (logFile.exists()) {
                logFile.delete();
            }

            // 备用路径: 应用专属外部存储，无需权限
            File extDir = context.getExternalFilesDir("logs");
            if (extDir != null) {
                extDir.mkdirs();
                sExtPath = new File(extDir, "kiosk_update.log").getAbsolutePath();
                try {
                    File extFile = new File(sExtPath);
                    if (extFile.exists()) {
                        extFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            String initMsg = "UpdateLog init: primary=" + sPrimaryPath + ", ext=" + sExtPath;
            writeRaw(sPrimaryPath, "I", initMsg);
        } catch (Exception e) {
            // init 失败，尝试直接写文件
            try {
                if (sPrimaryPath != null) {
                    writeRaw(sPrimaryPath, "E", "UpdateLog init failed: " + e.getMessage());
                }
            } catch (Exception ignored) {}
        }
    }

    private static synchronized void write(String level, String msg) {
        String ts = SDF.format(new Date());
        String line = ts + " [" + level + "] " + msg + "\n";

        // 写入主路径
        if (sPrimaryPath != null) {
            writeRaw(sPrimaryPath, null, line);
        }

        // 尝试写入外部路径
        if (sExtPath != null) {
            writeRaw(sExtPath, null, line);
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
