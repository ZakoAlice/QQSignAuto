package com.hou33.qqsignlog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Talks to the endpoints the "每日打卡" page uses, with QQ's own web cookies.
 *
 * Captured from https://ti.qq.com/signin/public/index.html:
 *   GET  https://ti.qq.com/hybrid-h5/api/json/daily_attendance/FrdDoSignIn?uin=&lt;uin&gt;&amp;traceInfo=
 *   POST https://ti.qq.com/hybrid-h5/api/json/daily_attendance/GetWeeklySignIn?bkn=&lt;bkn&gt;
 *   POST https://ti.qq.com/hybrid-h5/api/json/daily_attendance/SignInMainPage?bkn=&lt;bkn&gt;
 */
public final class SignInClient {
    private static final String BASE = "https://ti.qq.com/hybrid-h5/api/json/daily_attendance/";
    private static final String PAGE = "https://ti.qq.com/signin/public/index.html";

    private SignInClient() {
    }

    /** Fire the sign-in request once and log the server answer verbatim. */
    public static void selfTest() {
        String cu = uinFromCookie();
        final String uin = cu == null ? currentUin() : cu;
        Logger.i("[selftest] uin=" + uin + (cu == null ? " (from msf/prefs)" : " (from cookie)"));
        if (uin == null) {
            Logger.i("[selftest] no uin, aborting");
            return;
        }
        // Does the server look at the uin field at all, or only at the cookie?
        probe("SignInMainPage?bkn=" + BKN, uin, "POST", "{\"uin\":\"" + uin + "\"}");
        probe("SignInMainPage?bkn=" + BKN, uin, "POST", "{\"uin\":\"1\"}");
        probe("SignInMainPage?bkn=" + BKN, uin, "POST", "{\"uin\":\"999999999999\"}");
        probe("SignInMainPage?bkn=" + BKN, uin, "POST", "{}");
        // The action itself. Today the badge is green, so every answer here is a refusal;
        // what matters is which body, if any, gets a different retCode.
        probe("SignIn?bkn=" + BKN, uin, "POST", "{\"uin\":\"" + uin + "\"}");
        probe("SignIn?bkn=" + BKN, uin, "POST", "{\"uin\":\"1\"}");
        probe("SignIn?bkn=" + BKN, uin, "POST", "{\"uin\":\"" + uin + "\",\"traceInfo\":\"\"}");
        probe("SignIn?bkn=" + BKN, uin, "POST",
                "{\"uin\":\"" + uin + "\",\"type\":1,\"subType\":0}");
        probe("SignIn?bkn=" + BKN, uin, "POST",
                "{\"uin\":\"" + uin + "\",\"type\":1,\"subType\":0,\"traceInfo\":\"\"}");
        probe("SignIn?bkn=" + BKN, uin, "POST", "{\"uin\":\"" + uin + "\",\"sourceType\":1}");
        // Same code path the shipping module will take, run here so the decision can be watched.
        try {
            AutoClient.Outcome o = AutoClient.runOnce();
            Logger.i("[auto] finished=" + o.finished + " checkedInNow=" + o.checkedInNow
                    + " :: " + o.detail);
        } catch (Throwable t) {
            Logger.e("[auto] runOnce blew up", t);
        }
        // The notification path was verified here on 2026-10-23: both channels were created and
        // both test notifications showed in MIUI's shade under QQ's icon. That check is gone now
        // so the probe never posts anything; the shipping module owns the notifications.
    }

    private static final String BKN = "238667809";

    private static void probe(String path, String uin, String method, String body) {
        String url = BASE + path;
        if ("GET".equals(method)) {
            get(url, uin);
            sleep();
            return;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Referer", PAGE + "/index.html?_wv=1090528161&_wwv=13");
            c.setRequestProperty("Origin", "https://ti.qq.com");
            String cookie = cookieFor(url);
            c.setRequestProperty("Cookie", cookie == null ? "" : cookie);
            byte[] out = body.getBytes("UTF-8");
            c.getOutputStream().write(out);
            c.getOutputStream().flush();
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            String r = resp == null ? "" : (resp.length() > 30000 ? resp.substring(0, 30000) : resp);
            Logger.i("[full] POST " + path + " body=" + body.replace(uin, "<uin>")
                    + " -> http " + code + " resp=" + Logger.safe(r));
            if (!path.startsWith("SignInMainPage")) {
                r = r.length() > 900 ? r.substring(0, 900) : r;
            }
            Logger.i("[selftest] POST " + path + " body=" + body.replace(uin, "<uin>")
                    + " -> http " + code + " resp=" + Logger.safe(r));
        } catch (Throwable t) {
            Logger.e("[selftest] POST " + path + " " + body + " failed", t);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(400L);
        } catch (InterruptedException ignored) {
        }
    }

    private static void get(String url, String uin) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Referer", PAGE);
            c.setRequestProperty("Origin", "https://ti.qq.com");
            String cookie = cookieFor(url);
            c.setRequestProperty("Cookie", cookie == null ? "" : cookie);
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = read(in);
            String loc = c.getHeaderField("Location");
            Logger.i("[selftest] " + shortUrl(url) + " -> http " + code
                    + (loc == null ? "" : " location=" + shortUrl(loc))
                    + " cookieNames=" + cookieNames(cookie)
                    + " body=" + Logger.safe(body == null ? "" : (body.length() > 3000 ? body.substring(0, 3000) : body)));
        } catch (Throwable t) {
            Logger.e("[selftest] " + shortUrl(url) + " failed", t);
        }
    }

    private static String shortUrl(String u) {
        int i = u.indexOf("/daily_attendance/");
        return i < 0 ? u : u.substring(i);
    }

    private static String read(java.io.InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 4000) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "read-error " + t;
        }
    }

    /** Cookie names only, never values. */
    private static String cookieNames(String cookie) {
        if (cookie == null || cookie.length() == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : cookie.split(";")) {
            int eq = part.indexOf('=');
            sb.append(eq > 0 ? part.substring(0, eq).trim() : part.trim()).append(',');
        }
        return sb.toString();
    }

    private static String cookieFor(String url) {
        String[] managers = {
                "com.tencent.smtt.sdk.CookieManager",
                "com.tencent.xweb.pinus.sdk.CookieManager",
                "com.tencent.mobileqq.webview.swift.cookie.SwiftTicketCookie",
        };
        ClassLoader cl = Logger.cl();
        for (String m : managers) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(m, cl);
                if (c == null) {
                    continue;
                }
                Object inst = XposedHelpers.callStaticMethod(c, "getInstance");
                if (inst == null) {
                    continue;
                }
                Object v = XposedHelpers.callMethod(inst, "getCookie", url);
                if (v instanceof String && ((String) v).length() > 10) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String userAgent() {
        try {
            Class<?> c = XposedHelpers.findClassIfExists("com.tencent.smtt.sdk.WebView", Logger.cl());
            if (c != null) {
                Object ua = XposedHelpers.callStaticMethod(c, "getCoreUserAgent");
                if (ua instanceof String && ((String) ua).length() > 20) {
                    return (String) ua;
                }
            }
        } catch (Throwable ignored) {
        }
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/121.0.0.0 Mobile Safari/537.36 QQ/9.2.65";
    }

    /** The web cookie says which account the sign-in page is actually acting for. */
    private static String uinFromCookie() {
        String c = cookieFor(BASE);
        if (c == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|;\\s*)uin=([^;]+)").matcher(c);
        if (!m.find()) {
            return null;
        }
        String u = m.group(1).replaceAll("[^0-9]", "");
        return u.length() >= 5 ? u : null;
    }

    public static String currentUin() {
        ClassLoader cl = Logger.cl();
        // 1) MsfCommand.getInstance().mLogin().getUin()
        try {
            Class<?> msf = XposedHelpers.findClassIfExists("com.tencent.mobileqq.msf.sdk.MsfCommand", cl);
            if (msf != null) {
                Object inst = XposedHelpers.callStaticMethod(msf, "getInstance");
                Object login = XposedHelpers.callMethod(inst, "mLogin");
                Object uin = XposedHelpers.callMethod(login, "getUin");
                if (uin instanceof String && ((String) uin).length() > 4) {
                    return (String) uin;
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) BusinessUtils.getOnlineUin(app)
        try {
            Class<?> bu = XposedHelpers.findClassIfExists("com.tencent.mobileqq.utils.BusinessUtils", cl);
            Context ctx = Logger.app();
            if (bu != null && ctx != null) {
                Object uin = XposedHelpers.callStaticMethod(bu, "getOnlineUin", ctx);
                if (uin instanceof String && ((String) uin).length() > 4) {
                    return (String) uin;
                }
            }
        } catch (Throwable ignored) {
        }
        // 3) the sign-in prefs are keyed as "<uin>sign_in_info"
        try {
            Context ctx = Logger.app();
            if (ctx == null) {
                return null;
            }
            File sp = new File(ctx.getDataDir() == null ? ctx.getFilesDir().getParentFile()
                    : ctx.getDataDir(), "shared_prefs");
            File[] files = sp.listFiles();
            if (files == null) {
                return null;
            }
            for (File f : files) {
                if (!f.getName().endsWith(".xml")) {
                    continue;
                }
                String name = f.getName().substring(0, f.getName().length() - 4);
                try {
                    SharedPreferences p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
                    for (String k : p.getAll().keySet()) {
                        if (k.endsWith("sign_in_info")) {
                            String u = k.substring(0, k.length() - "sign_in_info".length());
                            if (u.length() >= 5 && u.matches("\\d+")) {
                                return u;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
