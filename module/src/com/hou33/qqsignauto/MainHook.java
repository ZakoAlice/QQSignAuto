package com.hou33.qqsignauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Headless module: it hooks nothing. It only borrows the QQ main process so it can read the
 * WebView cookie jar, then talks to the same HTTPS endpoint the 我的 → 打卡 page uses.
 *
 * A tick runs shortly after QQ starts and then every 5 minutes. Each tick asks the server which
 * cards are still open and checks in all of them, so the evening 晚安卡 is picked up as soon as
 * it opens; the day only closes once every card reports done. Real failures back off 30, then 60
 * minutes, and the record resets when the date changes.
 *
 * Notifications: one on the round where cards were newly signed, and one if it is late in the day
 * (22:00+) with cards still open. Both are at most once per day. They are posted under QQ's name
 * because this code lives in QQ's process, so QQ's own notification switch governs them.
 *
 * Optional config file (create it with any file manager, plain text, one key=value per line):
 *   <QQ external files dir>/qqsign_auto.cfg
 *     enabled=1        0 turns the module off without disabling it in LSPosed
 *     hour=0           first hour of the day we try; raise it if you would rather not have
 *                      the 日签卡 done in the small hours
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String QQ = "com.tencent.mobileqq";
    /**
     * How often we look. A tick is a preferences read plus, when it is due, the actual round,
     * so five minutes costs nothing and lets us catch the moment QQ finally writes the WebView
     * cookies (that happens the first time any H5 page is opened in this process).
     */
    private static final long TICK_MS = 5 * 60 * 1000L;
    private static final long FIRST_TICK_MS = 25 * 1000L;
    private static final String PREFS = "qqsign_auto";
    /** Past this hour a still-unfinished day is reported instead of retried in silence. */
    private static final int LATE_HOUR = 22;

    private static Handler sMain;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (lpparam == null || !QQ.equals(lpparam.packageName) || !QQ.equals(lpparam.processName)) {
            return;
        }
        Logger.setClassLoader(lpparam.classLoader);
        // handleLoadPackage runs before QQ's Application exists, so currentApplication() is
        // still null here. The main looper is already prepared, so waiting is enough.
        sMain = new Handler(Looper.getMainLooper());
        Logger.i("QQ 自动打卡已装载，等 QQ 自己起来");
        awaitApp(0);
    }

    /** Retry until the application context shows up; QQ needs a few seconds to bind it. */
    private static void awaitApp(final int tryNo) {
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                final Context ctx = appContext();
                if (ctx == null) {
                    if (tryNo < 60) {
                        awaitApp(tryNo + 1);
                    } else {
                        Logger.i("QQ never finished starting, giving up for this process");
                    }
                    return;
                }
                Logger.setContext(ctx);
                Logger.i("QQ 自动打卡已加载，日志: " + Logger.filePath());
                // A backoff belongs to one process: a fresh QQ may well have working cookies now,
                // so start over. Without this a 30-minute wait survives an app restart and hides
                // a session that just became usable.
                try {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong("nextAt", 0L).apply();
                } catch (Throwable ignored) {
                }
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tick(ctx);
                    }
                }, FIRST_TICK_MS);
            }
        }, 3000L);
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One scheduling step. Always cheap; the network work happens on another thread. */
    private static void tick(final Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            if (!today.equals(p.getString("day", ""))) {
                p.edit().putString("day", today).putBoolean("done", false)
                        .putInt("attempts", 0).putInt("waiting", 0).putLong("nextAt", 0L)
                        .putString("signed", "")
                        .putBoolean("notifiedOk", false).putBoolean("notifiedBad", false).apply();
            }
            Calendar cal = Calendar.getInstance();
            int hour = configHour(ctx);
            if (!configEnabled(ctx)) {
                Logger.i("qqsign_auto.cfg says enabled=0, skipping today");
                p.edit().putBoolean("done", true).apply();
            } else if (cal.get(Calendar.HOUR_OF_DAY) < hour) {
                Calendar target = (Calendar) cal.clone();
                target.set(Calendar.HOUR_OF_DAY, hour);
                target.set(Calendar.MINUTE, 5);
                target.set(Calendar.SECOND, 0);
                p.edit().putLong("nextAt", target.getTimeInMillis()).apply();
                Logger.i("waiting until " + hour + ":05 to check in");
            } else if (p.getBoolean("done", false)) {
                // nothing to do, but keep the loop alive in case the day rolls over
            } else if (System.currentTimeMillis() < p.getLong("nextAt", 0L)) {
                // backing off after a failed attempt
            } else {
                work(ctx, p);
            }
        } catch (Throwable t) {
            Logger.e("tick failed", t);
        }
        if (sMain != null) {
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tick(ctx);
                }
            }, TICK_MS);
        }
    }

    private static void work(final Context ctx, final SharedPreferences p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                SignInClient.Outcome o;
                try {
                    o = SignInClient.runOnce();
                } catch (Throwable e) {
                    Logger.e("check-in crashed", e);
                    o = new SignInClient.Outcome(false, false, "crash " + e);
                }
                int attempts = p.getInt("attempts", 0);
                // Cards can finish in different rounds (晚安卡 only opens at 20:00), so keep the
                // names of everything this module signed today for one honest summary.
                if (o.signed.length() > 0) {
                    String prev = p.getString("signed", "");
                    p.edit().putString("signed",
                            prev.length() == 0 ? o.signed : prev + "、" + o.signed).apply();
                }
                String all = p.getString("signed", "");
                if (o.finished) {
                    p.edit().putBoolean("done", true).putInt("attempts", attempts)
                            .putInt("waiting", 0).putLong("nextAt", 0L).apply();
                    Logger.i("打卡完成: " + o.detail);
                    // Only ping when we are the one who signed something. If the user already
                    // tapped the cards by hand, a notification would just be noise.
                    if (o.checkedInNow && !p.getBoolean("notifiedOk", false)) {
                        p.edit().putBoolean("notifiedOk", true).apply();
                        Notifier.success(ctx, all.length() == 0
                                ? o.detail : "今日自动打卡：" + all);
                    }
                } else if (!SignInClient.sessionReady()) {
                    // On a fresh install QQ's WebView jar stays empty until the app opens its
                    // first H5 page and the ticket service may need a moment after login, so
                    // there is nothing to retry with yet. No backoff: the next tick checks again.
                    // This is not a failure, so it does not count as one.
                    int waiting = p.getInt("waiting", 0) + 1;
                    p.edit().putInt("waiting", waiting).putLong("nextAt", 0L).apply();
                    if (waiting <= 3 || waiting % 12 == 0) {
                        Logger.i("还没有可用的登录态（第 " + waiting
                                + " 次，cookie 罐和票据都没有），在 QQ 里打开一次网页或重新登录会自动恢复");
                    }
                } else {
                    attempts++;
                    long backOff = (attempts <= 2 ? 30L : 60L) * 60 * 1000L;
                    p.edit().putInt("attempts", attempts)
                            .putLong("nextAt", System.currentTimeMillis() + backOff).apply();
                    Logger.i("还有卡没打上(第 " + attempts + " 次), " + backOff / 60000
                            + " 分钟后重试: " + o.detail);
                    // The day is nearly over and cards are still open -> tell the user once.
                    // Deliberately no attempt counter here: 晚安卡 is not open before 20:00, so a
                    // "still missing" round in the afternoon is normal and must not be an alarm.
                    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    if (hour >= LATE_HOUR && !p.getBoolean("notifiedBad", false)) {
                        p.edit().putBoolean("notifiedBad", true).apply();
                        Notifier.failure(ctx, o.detail + "，今天已试 " + attempts + " 次");
                    }
                }
            }
        }, "QQSignAuto");
        t.setDaemon(true);
        t.start();
    }

    private static boolean configEnabled(Context ctx) {
        return !"0".equals(config(ctx, "enabled"));
    }

    private static int configHour(Context ctx) {
        try {
            int v = Integer.parseInt(config(ctx, "hour"));
            if (v >= 0 && v <= 23) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String config(Context ctx, String key) {
        File f = configFile(ctx);
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && key.equals(line.substring(0, eq).trim())) {
                    r.close();
                    return line.substring(eq + 1).trim();
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File configFile(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            return dir == null ? null : new File(dir, "qqsign_auto.cfg");
        } catch (Throwable t) {
            return null;
        }
    }
}
