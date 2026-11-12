package com.hou33.qqsignauto;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * logcat plus a plain-text file the user can read without a PC. Nothing sensitive is written:
 * cookies are never logged, only their names.
 */
public final class Logger {
    public static final String TAG = "QQSignAuto";
    private static final Object LOCK = new Object();
    private static volatile ClassLoader sCl;
    private static volatile Context sCtx;
    private static File sFile;
    private static boolean sFileBroken;

    private Logger() {
    }

    public static void setClassLoader(ClassLoader cl) {
        sCl = cl;
    }

    public static ClassLoader cl() {
        return sCl;
    }

    public static void setContext(Context ctx) {
        sCtx = ctx;
    }

    public static Context app() {
        return sCtx;
    }

    public static void i(String msg) {
        log(msg, false);
    }

    public static void e(String msg, Throwable t) {
        log(msg + (t == null ? "" : " :: " + t), true);
    }

    private static void log(String msg, boolean warn) {
        String line = "[" + Process.myPid() + "] " + msg;
        if (warn) {
            Log.w(TAG, line);
        } else {
            Log.i(TAG, line);
        }
        Context ctx = sCtx;
        if (ctx == null || sFileBroken) {
            return;
        }
        try {
            File f = file(ctx);
            if (f == null) {
                return;
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date()) + " " + line);
            w.newLine();
            w.close();
        } catch (Throwable t) {
            sFileBroken = true;
            Log.w(TAG, "file log disabled: " + t);
        }
    }

    public static String filePath() {
        Context ctx = sCtx;
        if (ctx == null) {
            return "(app not ready)";
        }
        File f = file(ctx);
        return f == null ? "(unavailable)" : f.getAbsolutePath();
    }

    private static File file(Context ctx) {
        synchronized (LOCK) {
            if (sFile != null) {
                return sFile;
            }
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) {
                    dir = ctx.getFilesDir();
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return null;
                }
                sFile = new File(dir, "qqsign_auto.txt");
                return sFile;
            } catch (Throwable t) {
                sFileBroken = true;
                return null;
            }
        }
    }
}
