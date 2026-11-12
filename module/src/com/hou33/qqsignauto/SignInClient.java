package com.hou33.qqsignauto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

/**
 * The whole "每日打卡" feature of the QQ 我的 page, spoken over HTTP.
 *
 * Everything here was read off QQ's own sign-in page
 * (https://ti.qq.com/signin/public/index.html) while it ran on QQ 9.2.65:
 *
 *   POST https://ti.qq.com/hybrid-h5/api/json/daily_attendance/SignInMainPage?bkn=238667809
 *        body {"uin":"<uin>"}
 *        -> data.vecSignInfo.value[] = one entry per card, each with
 *           {"type":1,"subType":0,"signInResult":1,"signInCover":{"title":"日签卡"}},
 *           {"type":2,"subType":0,"signInResult":0,"signInCover":{"title":"晚安卡"}}
 *           signInResult 0 means "not checked in yet", and data.lastSignInTime is the epoch
 *           seconds of the last 日签卡 check-in.
 *   POST https://ti.qq.com/hybrid-h5/api/json/daily_attendance/SignIn?bkn=238667809
 *        body {"uin":"<uin>","type":<type>,"subType":<subType>}   -> retCode 0 == accepted
 *
 * Login state comes from the WebView cookie jar (uin/skey/p_skey/a2 …), so the module never
 * touches the account password and never needs a proxy at runtime. bkn is the plain g_tk the
 * page itself sends. The uin in the body has to match the cookie, the server checks it.
 *
 * Cards are driven entirely by whatever vecSignInfo lists, so a card QQ adds later (or a type
 * number that changes between 9.2.x and 9.3.x) needs no code change.
 */
public final class SignInClient {
    private static final String BASE = "https://ti.qq.com/hybrid-h5/api/json/daily_attendance/";
    private static final String PAGE = "https://ti.qq.com/signin/public/index.html?_wv=1090528161&_wwv=13";
    private static final String BKN = "238667809";

    /** Identity for the current round: the jar's cookies when usable, else QQ's tickets. */
    private static volatile String sCookie;
    private static volatile String sBkn = BKN;

    /** What one round produced, in a form that is safe to put in a log. */
    public static final class Outcome {
        public final boolean finished;
        public final boolean checkedInNow;
        public final String detail;
        /** Cards this round actually signed, just the names - the caller keeps a daily list. */
        public final String signed;

        Outcome(boolean finished, boolean checkedInNow, String detail) {
            this(finished, checkedInNow, detail, "");
        }

        Outcome(boolean finished, boolean checkedInNow, String detail, String signed) {
            this.finished = finished;
            this.checkedInNow = checkedInNow;
            this.detail = detail;
            this.signed = signed;
        }
    }

    private SignInClient() {
    }

    /**
     * Read the card list, check in every card that is still open, then read again to be sure.
     * Never throws: a failure comes back as finished=false so the caller retries later.
     */
    public static Outcome runOnce() {
        ArrayList<Card> cards = null;
        String uin = uinFromCookie();
        if (uin != null) {
            sCookie = cookieFor(BASE);
            sBkn = BKN;
            cards = cards(uin);
        } else {
            // Names only, never values: tells apart "the jar is empty" (QQ has not opened any
            // H5 page yet) from "the jar has state but no uin for this domain".
            Logger.i("cookie jar has no uin; " + jarReport());
            ArrayList<Session.Id> ids = Session.identities(Logger.app());
            Session.logNote();
            if (ids.isEmpty()) {
                return new Outcome(false, false, "no identity yet (" + Session.note() + ")");
            }
            Logger.i("cookie 罐不可用，改用 QQ 自己的票据（" + Session.note() + "）");
            // Read-only probe: SignInMainPage tells us which key the server accepts.
            for (int i = 0; i < ids.size(); i++) {
                Session.Id id = ids.get(i);
                uin = id.uin;
                sCookie = id.cookie;
                sBkn = id.bkn;
                cards = cards(uin);
                if (cards != null) {
                    Logger.i("票据身份 " + id.label + " 可用");
                    break;
                }
                Logger.i("票据身份 " + id.label + " 未被接受，换下一个");
            }
        }
        if (cards == null) {
            return new Outcome(false, false, "SignInMainPage unreachable or session rejected");
        }
        if (cards.isEmpty()) {
            return new Outcome(false, false, "SignInMainPage listed no cards");
        }
        StringBuilder open = new StringBuilder();
        ArrayList<String> openNames = new ArrayList<String>();
        ArrayList<String> openWhy = new ArrayList<String>();
        StringBuilder justDone = new StringBuilder();
        int signed = 0;
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            if (c.done()) {
                continue;
            }
            Integer code = signIn(uin, c);
            Logger.i("打卡 " + c.title + "(" + c.key() + ") -> " + why(code));
            if (code != null && code.intValue() == 0) {
                signed++;
                if (justDone.length() > 0) {
                    justDone.append('、');
                }
                justDone.append(c.title);
            } else {
                openNames.add(c.title);
                openWhy.add(why(code));
            }
        }
        if (openNames.isEmpty()) {
            return new Outcome(true, signed > 0, signed > 0
                    ? "已打卡：" + justDone : "今日 " + cards.size() + " 张卡都已打过", justDone.toString());
        }
        // Some cards only accept after their own hour; confirm what is genuinely left.
        ArrayList<Card> after = cards(uin);
        for (int i = 0; i < openNames.size(); i++) {
            if (open.length() > 0) {
                open.append('、');
            }
            open.append(openNames.get(i)).append('（').append(openWhy.get(i)).append('）');
        }
        if (after == null) {
            return new Outcome(false, signed > 0, "复核失败: " + open, justDone.toString());
        }
        StringBuilder left = new StringBuilder();
        for (int i = 0; i < after.size(); i++) {
            Card c = after.get(i);
            if (c.done()) {
                continue;
            }
            if (left.length() > 0) {
                left.append('、');
            }
            left.append(c.title).append('（').append(reasonFor(c.title, openNames, openWhy)).append('）');
        }
        if (left.length() == 0) {
            return new Outcome(true, signed > 0, "复核：卡片已全部完成（" + open + " 其实已生效）",
                    justDone.toString());
        }
        return new Outcome(false, signed > 0, "还差 " + left, justDone.toString());
    }

    /** The reason we saw for this card in the round just finished, or a neutral one. */
    private static String reasonFor(String title, ArrayList<String> names, ArrayList<String> why) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(title)) {
                return why.get(i);
            }
        }
        return "未完成";
    }

    /** @return the card list, or null when the query itself failed. */
    private static ArrayList<Card> cards(String uin) {
        String resp = post(BASE + "SignInMainPage?bkn=" + sBkn, "{\"uin\":\"" + uin + "\"}");
        if (resp == null) {
            return null;
        }
        Integer ret = Card.firstInt(resp, "retCode");
        if (ret == null || ret.intValue() != 0) {
            Logger.i("SignInMainPage retCode=" + ret + " (bad session or uin?), answer="
                    + brief(resp));
            return null;
        }
        return Card.parse(resp);
    }

    private static Integer signIn(String uin, Card c) {
        String body = "{\"uin\":\"" + uin + "\",\"type\":" + c.type + ",\"subType\":" + c.subType + "}";
        String resp = post(BASE + "SignIn?bkn=" + sBkn, body);
        return Card.firstInt(resp, "retCode");
    }

    /**
     * What a SignIn answer means, in the words a notification can show. 0/1/-1 were established
     * on 2026-10-23; 2 was seen on 2026-10-24 at 09:58 for 晚安卡, which only opens at 20:00, so
     * it reads as "the card exists but is not open yet" - retrying later is the right move, not
     * an error. Anything else stays literal.
     */
    private static String why(Integer code) {
        if (code == null) {
            return "没有回应";
        }
        switch (code.intValue()) {
            case 0: return "已打卡";
            case 1: return "今天已经打过";
            case 2: return "还没到点";
            case -1: return "参数或登录态被拒";
            default: return "retCode=" + code;
        }
    }

    /** True once an identity is available, from either source. */
    public static boolean sessionReady() {
        return uinFromCookie() != null || !Session.identities(Logger.app()).isEmpty();
    }

    /** Names of the cookies present for our host, values omitted. Safe to log. */
    private static String names(String jar) {
        StringBuilder b = new StringBuilder();
        String[] parts = jar.split(";");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            String n = (eq > 0 ? parts[i].substring(0, eq) : parts[i]).trim();
            if (n.length() == 0) {
                continue;
            }
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(n);
        }
        return b.toString();
    }

    /**
     * Why the jar looks empty, in one line: which cookie stores QQ has, whether each one can be
     * reached from the main process, and what it holds for our host (names only, never values).
     * Run only when no identity was found, so it costs nothing in the normal path.
     */
    static String jarReport() {
        String[] labels = {"smtt", "xweb", "system"};
        String[] classes = {
                "com.tencent.smtt.sdk.CookieManager",
                "com.tencent.xweb.pinus.sdk.CookieManager",
                null,
        };
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < classes.length; i++) {
            if (b.length() > 0) {
                b.append("; ");
            }
            b.append(labels[i]).append('=');
            try {
                Object inst;
                if (classes[i] == null) {
                    inst = android.webkit.CookieManager.getInstance();
                } else {
                    Class<?> c = XposedHelpers.findClassIfExists(classes[i], Logger.cl());
                    if (c == null) {
                        b.append("no class");
                        continue;
                    }
                    inst = XposedHelpers.callStaticMethod(c, "getInstance");
                }
                if (inst == null) {
                    b.append("no instance");
                    continue;
                }
                Object v = XposedHelpers.callMethod(inst, "getCookie", BASE);
                if (!(v instanceof String)) {
                    b.append("null");
                } else if (((String) v).length() == 0) {
                    b.append("empty");
                } else {
                    b.append(names((String) v));
                }
            } catch (Throwable t) {
                b.append("err ").append(t.getClass().getSimpleName());
            }
        }
        return b.toString();
    }

    /** Which account the WebView cookies belong to; that is who we check in for. */
    public static String uinFromCookie() {
        String c = cookieFor(BASE);
        if (c == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?:^|;\\s*)uin=([^;]+)").matcher(c);
        if (!m.find()) {
            return null;
        }
        String u = m.group(1).replaceAll("[^0-9]", "");
        return u.length() >= 5 ? u : null;
    }

    private static String post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Referer", PAGE);
            c.setRequestProperty("Origin", "https://ti.qq.com");
            String cookie = sCookie != null ? sCookie : cookieFor(url);
            c.setRequestProperty("Cookie", cookie == null ? "" : cookie);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                Logger.i("POST " + tail(url) + " -> http " + code);
                return null;
            }
            return read(c.getInputStream());
        } catch (Throwable t) {
            Logger.e("POST " + tail(url) + " failed", t);
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String read(InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) > 0 && total < 65536) {
                buf.write(chunk, 0, n);
                total += n;
            }
            in.close();
            return new String(buf.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Cookie jar readers, most general first. X5 keeps them in its own store, and QQ also ships
     * the Chromium cookie manager under a second package name.
     */
    private static String cookieFor(String url) {
        String[] managers = {
                "com.tencent.smtt.sdk.CookieManager",
                "com.tencent.xweb.pinus.sdk.CookieManager",
        };
        ClassLoader cl = Logger.cl();
        for (int i = 0; i < managers.length; i++) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(managers[i], cl);
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
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/121.0.0.0 Mobile Safari/537.36 QQ/9.2.65";
    }

    private static String tail(String url) {
        int i = url.indexOf("/daily_attendance/");
        return i < 0 ? url : url.substring(i + 1);
    }

    private static String brief(String s) {
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}
