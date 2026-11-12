// GENERATED FILE - DO NOT EDIT.
// Derived from work/checkin/auto/src/com/hou33/qqsignauto/AutoNotifier.java by work/checkin/sync_probe.py
// It lets the capture probe run the shipping module's logic and log the result.
package com.hou33.qqsignlog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * Two notifications a day at most, and two separate channels so they can be silenced
 * independently: "打卡结果" (the daily success line) and "打卡异常" (only when the day is
 * running out with a card still open).
 *
 * The code runs inside QQ, so the notification is posted under QQ's own name and needs the
 * POST_NOTIFICATIONS grant QQ already has. If the user turned QQ's notifications off wholesale,
 * areNotificationsEnabled() says so and we just keep writing the log file.
 */
public final class AutoNotifier {
    private static final String CH_RESULT = "qqsign_auto_result";
    private static final String CH_ALERT = "qqsign_auto_alert";
    private static final int ID_RESULT = 0x51470001;
    private static final int ID_ALERT = 0x51470002;
    private static final String TITLE = "QQ 每日打卡";

    private AutoNotifier() {
    }

    public static void success(Context ctx, String text) {
        post(ctx, CH_RESULT, ID_RESULT, TITLE, text, false);
    }

    public static void failure(Context ctx, String text) {
        post(ctx, CH_ALERT, ID_ALERT, TITLE + "未完成", text, true);
    }

    private static void post(Context ctx, String channel, int id, String title, String text,
                             boolean alert) {
        if (ctx == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ctx
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (!nm.areNotificationsEnabled()) {
                Logger.i("notifications are switched off for QQ, skipping: " + text);
                return;
            }
            makeChannel(nm, channel, alert ? "打卡异常" : "打卡结果", alert);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, channel)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(alert ? android.R.drawable.stat_notify_error
                            : android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            if (alert) {
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            nm.notify(id, b.build());
            Logger.i("notification posted on " + channel + ": " + text);
        } catch (Throwable t) {
            Logger.e("notify failed", t);
        }
    }

    private static void makeChannel(NotificationManager nm, String id, String name, boolean alert) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            if (nm.getNotificationChannel(id) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(id, name,
                    alert ? NotificationManager.IMPORTANCE_HIGH
                            : NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            ch.setDescription("QQ 自动打卡模块");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Logger.e("channel creation failed", t);
        }
    }
}
