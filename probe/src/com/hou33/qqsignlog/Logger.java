package com.hou33.qqsignlog;

import android.app.Activity;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;

/** logcat + file sink, with light redaction of long secrets. */
public final class Logger {
    public static final String TAG = "QQSignLog";
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

    public static void i(String msg) {
        log(1, msg);
    }

    public static void w(String msg) {
        log(4, msg);
    }

    public static void e(String msg, Throwable t) {
        log(6, msg + (t == null ? "" : " :: " + t));
    }

    private static void log(int level, String msg) {
        if (msg == null) {
            msg = "null";
        }
        String head = "[" + Process.myPid() + "] ";
        String full = head + msg;
        // logcat drops very long lines, so chunk it.
        int max = 3000;
        for (int i = 0; i < full.length(); i += max) {
            String part = full.substring(i, Math.min(full.length(), i + max));
            if (level == 6) {
                Log.w(TAG, part);
            } else {
                Log.i(TAG, part);
            }
        }
        write(full);
    }

    private static void write(String line) {
        if (sFileBroken) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (sFile == null) {
                    Context ctx = app();
                    if (ctx == null) {
                        return;
                    }
                    File dir = ctx.getExternalFilesDir(null);
                    if (dir == null) {
                        dir = ctx.getFilesDir();
                    }
                    sFile = new File(dir, "qqsign_log.txt");
                }
                BufferedWriter w = new BufferedWriter(new FileWriter(sFile, true));
                w.write(java.text.SimpleDateFormat.getTimeInstance().format(new java.util.Date()) + " " + line);
                w.newLine();
                w.close();
            } catch (Throwable t) {
                sFileBroken = true;
                Log.w(TAG, "file sink disabled: " + t);
            }
        }
    }

    public static String filePath() {
        return sFile == null ? "(not created yet)" : sFile.getAbsolutePath();
    }

    public static Context app() {
        Context ctx = sCtx;
        if (ctx != null) {
            return ctx;
        }
        ClassLoader cl = sCl;
        if (cl == null) {
            return null;
        }
        try {
            Class<?> base = cl.loadClass("com.tencent.common.app.BaseApplicationImpl");
            Object app = base.getMethod("getApplication").invoke(null);
            if (app instanceof Context) {
                sCtx = (Context) app;
                return sCtx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(inst);
            if (app instanceof Context) {
                sCtx = (Context) app;
                return sCtx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Keep the shape of a value but hide long tokens, without eating URL paths. */
    public static String safe(String s) {
        if (s == null) {
            return "null";
        }
        // Only values whose *parameter name* looks like a credential are hidden, so the JSON
        // bodies the probe exfiltrates as b= / r= stay readable.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)((?:p_skey|pskey|skey|cookie|token|session|sessdata|sid|idt|sign|sig|auth"
                        + "|password|pwd)=)([^\\s&#%]{8,})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tok = m.group(2);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    m.group(1) + tok.substring(0, 6) + "…len" + tok.length()));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return out.length() > 30000 ? out.substring(0, 30000) + "…(truncated)" : out;
    }

    /** Render an arbitrary hook argument into something readable. */
    public static String render(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            if (o instanceof String) {
                return safe((String) o);
            }
            if (o instanceof byte[]) {
                return "bytes:" + safe(new String((byte[]) o, "UTF-8"));
            }
            if (o instanceof Object[]) {
                StringBuilder sb = new StringBuilder("[");
                Object[] arr = (Object[]) o;
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(render(arr[i]));
                }
                return sb.append(']').toString();
            }
            if (o instanceof Activity) {
                return "<activity " + ((Activity) o).getClass().getSimpleName() + ">";
            }
            String cn = o.getClass().getName();
            if (cn.contains("HippyMap")) {
                Object json = call(o, "toJSONObject");
                return "<HippyMap " + safe(String.valueOf(json)) + ">";
            }
            if (cn.contains("HippyArray")) {
                Object json = call(o, "toJSONArray");
                return "<HippyArray " + safe(String.valueOf(json)) + ">";
            }
            if (o instanceof android.os.Bundle) {
                android.os.Bundle b = (android.os.Bundle) o;
                StringBuilder sb = new StringBuilder("<Bundle ");
                for (String k : b.keySet()) {
                    sb.append(k).append('=').append(safe(String.valueOf(b.get(k)))).append("; ");
                }
                return sb.append('>').toString();
            }
            return "<" + cn + "> " + safe(String.valueOf(o));
        } catch (Throwable t) {
            return "<unrenderable " + o.getClass().getName() + ": " + t + ">";
        }
    }

    public static Object call(Object obj, String name, Object... args) {
        if (obj == null) {
            return null;
        }
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                    continue;
                }
                m.setAccessible(true);
                return m.invoke(obj, args);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String dumpObject(Object obj, int maxFields) {
        if (obj == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v;
                    try {
                        v = f.get(obj);
                    } catch (Throwable t) {
                        continue;
                    }
                    sb.append(c.getSimpleName()).append('.').append(f.getName()).append('=')
                            .append(render(v)).append(" | ");
                    if (sb.length() > 4000 || --maxFields <= 0) {
                        return sb.toString();
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            sb.append("err ").append(t);
        }
        return sb.toString();
    }

    /** Print every shared_prefs entry that mentions sign_in / checkin. */
    public static void dumpSignInPrefs() {
        Context ctx = app();
        if (ctx == null) {
            i("dumpSignInPrefs: no context yet");
            return;
        }
        File sp = new File(ctx.getDataDir() == null ? ctx.getFilesDir().getParentFile() : ctx.getDataDir(), "shared_prefs");
        File[] files = sp.listFiles();
        i("shared_prefs dir=" + sp.getAbsolutePath() + " files=" + (files == null ? -1 : files.length));
        if (files == null) {
            return;
        }
        int hits = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".xml")) {
                continue;
            }
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    String low = line.toLowerCase();
                    if (low.contains("sign_in") || low.contains("signin") || low.contains("check_in") || low.contains("checkin")) {
                        i("PREF " + f.getName() + " -> " + safe(line.trim()));
                        if (++hits > 60) {
                            break;
                        }
                    }
                }
                r.close();
            } catch (Throwable ignored) {
            }
            if (hits > 60) {
                break;
            }
        }
        i("dumpSignInPrefs done, hits=" + hits + ", log=" + filePath());
    }

    // ------------------------------------------------------------- view tree

    /**
     * Dump the visible texts of an activity so we can find the exact wording of the
     * check-in button. Only clickable / text-bearing nodes are printed.
     * Runs on the caller thread, so the budget has to stay small enough for the UI thread.
     */
    public static void dumpViews(final Activity a, final String why) {
        dumpViews(a, why, 700);
    }

    public static void dumpViews(final Activity a, final String why, int budgetNodes) {
        if (a == null || a.getWindow() == null) {
            return;
        }
        final android.view.View root;
        try {
            root = a.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (root == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        walk(root, 0, new int[]{budgetNodes}, sb);
        i("[views " + a.getClass().getName() + " @" + why + "] " + safe(sb.toString()));
    }

    /** Same as dumpViews but for any view already at hand (Hippy pages are native views). */
    public static void dumpViews(android.view.View root, String why) {
        if (root == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        walk(root, 0, new int[]{700}, sb);
        i("[views " + why + "] " + safe(sb.toString()));
    }

    private static void walk(android.view.View v, int depth, int[] budget, StringBuilder sb) {
        if (v == null || budget[0] <= 0 || depth > 24) {
            return;
        }
        budget[0]--;
        CharSequence text = null;
        if (v instanceof android.widget.TextView) {
            text = ((android.widget.TextView) v).getText();
        }
        CharSequence desc = v.getContentDescription();
        boolean interesting = v.isClickable()
                || (text != null && text.length() > 0)
                || (desc != null && desc.length() > 0);
        if (interesting) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            sb.append(v.getClass().getSimpleName());
            if (text != null && text.length() > 0) {
                sb.append(" text=\"").append(text).append('"');
            }
            if (desc != null && desc.length() > 0) {
                sb.append(" desc=\"").append(desc).append('"');
            }
            sb.append(v.isClickable() ? " CLICKABLE" : "");
            sb.append(" id=").append(idName(v));
            sb.append(" bounds=").append(loc[0]).append(',').append(loc[1])
                    .append('-').append(loc[0] + v.getWidth()).append(',').append(loc[1] + v.getHeight());
            sb.append(" vis=").append(v.getVisibility()).append(" || ");
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                walk(g.getChildAt(i), depth + 1, budget, sb);
            }
        }
    }

    public static String idName(android.view.View v) {
        int id = v.getId();
        if (id == 0) {
            return "-";
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return Integer.toHexString(id);
        }
    }
}
