package com.kiosk.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * 定时息屏/亮屏调度辅助类
 * - 使用 SharedPreferences 存储时间
 * - 使用 AlarmManager 设置闹钟
 */
public class ScreenControlHelper {

    private static final String TAG = "ScreenControl";
    private static final String PREFS_NAME = "kiosk_screen_schedule";

    // SharedPreferences 键
    private static final String KEY_OFF_HOUR = "screen_off_hour";
    private static final String KEY_OFF_MINUTE = "screen_off_minute";
    private static final String KEY_ON_HOUR = "screen_on_hour";
    private static final String KEY_ON_MINUTE = "screen_on_minute";

    // 默认值：-1 表示未设置
    public static final int NOT_SET = -1;
    private static final int ALARM_REQUEST_OFF = 1001;
    private static final int ALARM_REQUEST_ON = 1002;

    // ========== 保存时间 ==========

    public static void saveScreenOffTime(Context ctx, int hour, int minute) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_OFF_HOUR, hour)
                .putInt(KEY_OFF_MINUTE, minute)
                .apply();
        Log.d(TAG, "Save screen off: " + hour + ":" + String.format("%02d", minute));
        scheduleScreenOff(ctx);
    }

    public static void saveScreenOnTime(Context ctx, int hour, int minute) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_ON_HOUR, hour)
                .putInt(KEY_ON_MINUTE, minute)
                .apply();
        Log.d(TAG, "Save screen on: " + hour + ":" + String.format("%02d", minute));
        scheduleScreenOn(ctx);
    }

    public static void clearScreenOffTime(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_OFF_HOUR, NOT_SET)
                .putInt(KEY_OFF_MINUTE, NOT_SET)
                .apply();
        cancelScreenOff(ctx);
    }

    public static void clearScreenOnTime(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_ON_HOUR, NOT_SET)
                .putInt(KEY_ON_MINUTE, NOT_SET)
                .apply();
        cancelScreenOn(ctx);
    }

    // ========== 读取时间 ==========

    public static int getScreenOffHour(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_OFF_HOUR, NOT_SET);
    }

    public static int getScreenOffMinute(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_OFF_MINUTE, NOT_SET);
    }

    public static int getScreenOnHour(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ON_HOUR, NOT_SET);
    }

    public static int getScreenOnMinute(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ON_MINUTE, NOT_SET);
    }

    public static boolean isScreenOffSet(Context ctx) {
        return getScreenOffHour(ctx) != NOT_SET;
    }

    public static boolean isScreenOnSet(Context ctx) {
        return getScreenOnHour(ctx) != NOT_SET;
    }

    // ========== 调度闹钟 ==========

    /**
     * 调度息屏闹钟——计算下一次触发时间
     */
    public static void scheduleScreenOff(Context ctx) {
        int hour = getScreenOffHour(ctx);
        int minute = getScreenOffMinute(ctx);
        if (hour == NOT_SET) {
            cancelScreenOff(ctx);
            return;
        }

        Calendar target = getNextTime(hour, minute);
        Intent intent = new Intent(ctx, ScreenControlReceiver.class);
        intent.setAction(ScreenControlReceiver.ACTION_SCREEN_OFF);
        PendingIntent pending = PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST_OFF, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        target.getTimeInMillis(), pending);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, target.getTimeInMillis(), pending);
            }
            Log.d(TAG, "Screen OFF scheduled: " + target.getTime());
        }
    }

    /**
     * 调度亮屏闹钟——使用 PendingIntent 直接启动透明 Activity 保证可靠唤醒
     */
    public static void scheduleScreenOn(Context ctx) {
        int hour = getScreenOnHour(ctx);
        int minute = getScreenOnMinute(ctx);
        if (hour == NOT_SET) {
            cancelScreenOn(ctx);
            return;
        }

        Calendar target = getNextTime(hour, minute);
        Intent intent = new Intent(ctx, ScreenOnActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                ctx, ALARM_REQUEST_ON, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        target.getTimeInMillis(), pending);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, target.getTimeInMillis(), pending);
            }
            Log.d(TAG, "Screen ON scheduled: " + target.getTime());
        }
    }

    /**
     * 取消息屏闹钟
     */
    private static void cancelScreenOff(Context ctx) {
        Intent intent = new Intent(ctx, ScreenControlReceiver.class);
        intent.setAction(ScreenControlReceiver.ACTION_SCREEN_OFF);
        PendingIntent pending = PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST_OFF, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending);
            pending.cancel();
        }
    }

    /**
     * 取消亮屏闹钟
     */
    private static void cancelScreenOn(Context ctx) {
        Intent intent = new Intent(ctx, ScreenOnActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                ctx, ALARM_REQUEST_ON, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending);
            pending.cancel();
        }
    }

    /**
     * 开机后重新调度所有闹钟
     */
    public static void rescheduleAfterBoot(Context ctx) {
        if (isScreenOffSet(ctx)) {
            scheduleScreenOff(ctx);
        }
        if (isScreenOnSet(ctx)) {
            scheduleScreenOn(ctx);
        }
    }

    // ========== 工具方法 ==========

    /**
     * 计算下一次触发时间
     * 如果今天的设定时间已过，则设为明天的同一时间
     */
    private static Calendar getNextTime(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }
        return target;
    }

    /**
     * 格式化时间显示
     */
    public static String formatTime(int hour, int minute) {
        if (hour == NOT_SET) return "未设置";
        return String.format("%02d:%02d", hour, minute);
    }
}
