// GENERATED FILE - DO NOT EDIT.
// Derived from work/checkin/auto/src/com/hou33/qqsignauto/AutoSession.java by work/checkin/sync_probe.py
// It lets the capture probe run the shipping module's logic and log the result.
package com.hou33.qqsignlog;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XposedHelpers;

/**
 * Identity taken from QQ's own ticket service instead of the WebView cookie jar.
 *
 * Normally the jar is the right source: it is exactly what the H5 check-in page uses. But on
 * QQ 9.3.75 the X5 cookie store is unreachable from the main process - getCookie() answers null
 * for every store - so the jar route only works on 9.2.65.
 *
 * The ticket route cannot use getSkey either. Reading the shipped code says so plainly:
 * mqq.app.TicketManagerImpl#getSkey/getRealSkey both funnel into NtTicketProvider.getSkey(),
 * which returns mqq.inject.SigInjectManager.instance().getFakeSkey(), and that method is a
 * hard-coded "MMMMM" (com.tencent.qqconnect.wtlogin.e#getFakeSkey) unless QQ's own hotfix
 * redirects it. So a 5-char skey is a decoy, never a session.
 *
 * What QQ really sends for domain-scoped H5 calls is the pskey: the 会员/VAS code
 * (com.tencent.mobileqq.vas.api.VasSkey) asks for a pskey per domain and uses that as its key,
 * and getPskey(String,String) is a live method on TicketManagerImpl. So each identity below is
 * one candidate key, and SignInClient lets the server decide which one it accepts - a
 * SignInMainPage call is read-only, so probing costs nothing but a request.
 *
 * Privacy rule: only names, lengths and method signatures are ever logged, never a value.
 */
final class AutoSession {
    /** One usable (cookie, bkn) pair plus what it is, so a rejection can be named in the log. */
    static final class Id {
        final String label;
        final String uin;
        final String cookie;
        final String bkn;

        Id(String label, String uin, String key) {
            this.label = label;
            this.uin = uin;
            // bkn is g_tk of the key the page uses, so the two always travel together. The key
            // goes into both skey and p_skey because which of the two the endpoint validates is
            // not knowable from outside, and a matching pair is what the server checks.
            this.bkn = String.valueOf(gtk(key));
            this.cookie = "uin=o0" + uin + "; p_uin=o0" + uin + "; p_uid=" + uin
                    + "; skey=" + key + "; p_skey=" + key;
        }
    }

    private static String sNote = "";
    private static boolean sLogged;
    private static boolean sDumped;
    private static ArrayList<Id> sIds;
    private static long sIdsAt;

    /** getPskey waits on an IPC latch with no timeout of its own; do not hang our thread. */
    private static final long CALL_MS = 8000L;
    /** One ticket hunt per 10 minutes: it is reflection plus IPC, and a 5-minute tick must not spam it. */
    private static final long REHUNT_MS = 10 * 60 * 1000L;

    private AutoSession() {
    }

    static String note() {
        return sNote;
    }

    /** Candidate identities, cached for {@link #REHUNT_MS}. Never throws; empty means "no luck". */
    static ArrayList<Id> identities(Context ctx) {
        long now = System.currentTimeMillis();
        if (sIds != null && (sIds.size() > 0 || now - sIdsAt < REHUNT_MS)) {
            return sIds;
        }
        sIdsAt = now;
        sIds = new ArrayList<Id>();
        hunt(ctx);
        return sIds;
    }

    private static void hunt(Context ctx) {
        StringBuilder d = new StringBuilder();
        try {
            Object app = runtime(ctx);
            if (app == null) {
                sNote = "no app runtime (app class " + (ctx == null ? "null" : ctx.getClass().getName()) + ")";
                return;
            }
            Object u = call(app, "getCurrentAccountUin");
            String su = u instanceof String ? ((String) u).replaceAll("[^0-9]", "") : null;
            if (su == null || su.length() < 5) {
                sNote = "runtime has no uin (" + app.getClass().getName() + ")";
                return;
            }
            Object mgr = XposedHelpers.callMethod(app, "getManager", 2);
            if (mgr == null) {
                sNote = "no ticket manager (id 2) on " + app.getClass().getName();
                return;
            }
            dumpMethods(mgr);

            String skey = string(attempt(mgr, "getSkey", new Object[]{su}, d, false));
            String realSkey = string(attempt(mgr, "getRealSkey", new Object[]{su}, d, false));
            String ti = string(attempt(mgr, "getPskey", new Object[]{su, "ti.qq.com"}, d, true));

            if (len(skey) < 8 && len(realSkey) >= 8) {
                skey = realSkey;
            }
            // A 5-char skey is the decoy documented above; anything that short cannot be a key.
            if (len(skey) >= 8) {
                sIds.add(new Id("skey", su, skey));
            }
            if (len(ti) >= 8) {
                sIds.add(new Id("pskey(ti.qq.com)", su, ti));
            }
            // Deliberately no second domain. Over the 20-day run (2026-10-24..11-12, 20/20 days
            // complete) getPskey(uin,"qq.com") timed out in every one of the 69 hunts, while the
            // ti.qq.com key was accepted in every round that got one - so that probe cost 8
            // seconds and left a thread parked forever on QQ's untimed IPC latch, and bought
            // nothing. Re-add it only if a QQ build ever rejects ti.qq.com.
            sNote = d.length() == 0 ? "no tickets" : d.toString();
        } catch (Throwable t) {
            sNote = "ticket route failed: " + t;
        }
    }

    /** Log the note once per process, so a 5-minute tick does not spam the file. */
    static void logNote() {
        if (sLogged) {
            return;
        }
        sLogged = true;
        Logger.i("ticket route: " + sNote);
    }

    /**
     * QQ's Application keeps the runtime behind a getter; the spelling differs between versions,
     * so pick any no-argument method that returns something and mentions "runtime".
     */
    private static Object runtime(Context ctx) {
        if (ctx != null) {
            Method[] ms;
            try {
                ms = ctx.getClass().getMethods();
            } catch (Throwable t) {
                ms = new Method[0];
            }
            for (int i = 0; i < ms.length; i++) {
                String n = ms[i].getName().toLowerCase();
                if (n.contains("runtime") && ms[i].getParameterTypes().length == 0) {
                    try {
                        Object v = ms[i].invoke(ctx);
                        if (v != null) {
                            return v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        // Fallback for versions where the getter is not on the Application subclass itself.
        try {
            Class<?> base = XposedHelpers.findClassIfExists(
                    "com.tencent.common.app.BaseApplicationImpl", Logger.cl());
            if (base != null) {
                Object inst = XposedHelpers.callStaticMethod(base, "getApplication");
                return inst == null ? null : firstRuntime(inst);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object firstRuntime(Object holder) {
        Method[] ms = holder.getClass().getMethods();
        for (int i = 0; i < ms.length; i++) {
            if (ms[i].getName().toLowerCase().contains("runtime")
                    && ms[i].getParameterTypes().length == 0) {
                try {
                    Object v = ms[i].invoke(holder);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Object... args) {
        return XposedHelpers.callMethod(target, name, args);
    }

    /**
     * Call a ticket method and record only "name -> length" in the diagnostic line. getPskey
     * blocks on an IPC latch, so those calls go through a worker thread with a deadline.
     */
    private static Object attempt(final Object target, final String name, final Object[] args,
                                  final StringBuilder d, final boolean guarded) {
        Object v = null;
        Throwable err = null;
        if (guarded) {
            final Object[] box = new Object[1];
            try {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            box[0] = XposedHelpers.callMethod(target, name, args);
                        } catch (Throwable ignored) {
                        }
                    }
                }, "QQSignTicket");
                t.setDaemon(true);
                t.start();
                t.join(CALL_MS);
                v = box[0];
                if (v == null) {
                    d.append(name).append("=timeout ");
                    return null;
                }
            } catch (Throwable t) {
                err = t;
            }
        } else {
            try {
                v = XposedHelpers.callMethod(target, name, args);
            } catch (Throwable t) {
                err = t;
            }
        }
        if (err != null) {
            d.append(name).append("=err ").append(err.getClass().getSimpleName()).append(' ');
            return null;
        }
        d.append(name).append('=').append(len(v)).append(" chars ");
        return v;
    }

    private static int len(Object v) {
        return v instanceof String ? ((String) v).length() : 0;
    }

    private static String string(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** Every ticket method that could carry a session key, once per process. No values. */
    private static void dumpMethods(Object mgr) {
        if (sDumped) {
            return;
        }
        sDumped = true;
        StringBuilder b = new StringBuilder();
        Method[] ms = mgr.getClass().getMethods();
        for (int i = 0; i < ms.length; i++) {
            String n = ms[i].getName().toLowerCase();
            if (n.contains("skey") || n.equals("geta2") || n.contains("pt4token")) {
                b.append(ms[i].getName()).append('(');
                Class<?>[] ps = ms[i].getParameterTypes();
                for (int j = 0; j < ps.length; j++) {
                    if (j > 0) {
                        b.append(',');
                    }
                    b.append(ps[j].getSimpleName());
                }
                b.append(") ");
            }
        }
        Logger.i("ticket manager " + mgr.getClass().getName() + ": " + b);
    }

    /**
     * The g_tk QQ puts in the URL as bkn: 5381, then h += (h << 5) + c for every char, kept in
     * 31 bits. Computed rather than hard-coded because the key comes from the ticket service.
     */
    static int gtk(String skey) {
        int h = 5381;
        for (int i = 0; i < skey.length(); i++) {
            h += (h << 5) + skey.charAt(i);
        }
        return h & 0x7fffffff;
    }
}
