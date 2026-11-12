package com.hou33.qqsignlog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Read-only probe v2 for QQ "每日打卡".
 *
 * v1 proved the check-in is reported back to native by a page (JSB "signIn/signedCallback"),
 * but no Java-level HTTP was seen, so the request must leave through the X5/Chromium network
 * stack. v2 therefore watches the WebView resource pipeline and dumps the page view trees, so
 * that both the request and the button wording become visible. Nothing is modified.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String QQ = "com.tencent.mobileqq";

    /** Hippy native modules whose JS-visible calls we want to see. */
    private static final String[] HIPPY_MODULES = {
            "com.tencent.hippy.qq.module.HippyQQHttpModule",
            "com.tencent.hippy.qq.module.QQJsbModule",
            "com.tencent.hippy.qq.module.QQUiModule",
            "com.tencent.hippy.qq.module.QQNavigatorModule",
    };

    /** JS -> native dispatchers, version independent. */
    private static final String[] DISPATCHERS = {
            "com.tencent.mtt.hippy.modules.HippyModuleManagerImpl",
            "com.tencent.mtt.hippy.modules.nativemodules.HippyNativeModuleBase",
    };

    private static final String SIGNIN_JS_PLUGIN = "com.tencent.mobileqq.vaswebviewplugin.DailySignInWebviewPlugin";
    private static final String QIPC_PROXY = "com.tencent.mobileqq.perf.block.BinderMethodProxy";
    private static final String X5_WEBVIEW = "com.tencent.smtt.sdk.WebView";
    private static final String X5_WEBVIEW_CLIENT = "com.tencent.smtt.sdk.WebViewClient";
    private static final String X5_WEBVIEW_BASE = "com.tencent.smtt.export.external.interfaces.IX5WebViewBase";

    /** Resource types that never carry the sign-in call. */
    private static final String[] SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico",
            ".css", ".woff", ".woff2", ".ttf", ".svg", ".mp4", ".m3u8"};

    private volatile Activity lastActivity;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !QQ.equals(lpparam.packageName)) {
            return;
        }
        Logger.setClassLoader(lpparam.classLoader);
        final ClassLoader cl = lpparam.classLoader;
        Logger.i("agent v2 attached, proc=" + lpparam.processName + " firstApp=" + lpparam.isFirstApplication);
        // Cookie experiment: can the *main* process reach the web cookies too?
        if (QQ.equals(lpparam.processName)) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Logger.i("[selftest] from main process");
                    replaySelfTest();
                }
            }, 30000L);
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // A probe bug must never take QQ down, so swallow everything here.
                try {
                    install(cl);
                } catch (Throwable t) {
                    Logger.e("install aborted", t);
                }
            }
        }, "QQSignLog-install");
        t.setDaemon(true);
        t.start();
    }

    private void install(ClassLoader cl) {
        // 1) framework + X5 plumbing first: those classes exist from the very start, and the
        //    check-in page can be opened seconds after QQ starts.
        hookActivities(cl);
        boolean x5 = hookX5(cl);
        hookSystemWebView();
        boolean console = hookConsole(cl);
        boolean debug = enableDevtools(cl);

        // 2) lazily loaded QQ classes: retry, but keep it short.
        boolean[] done = new boolean[HIPPY_MODULES.length + DISPATCHERS.length + 3];
        for (int attempt = 0; attempt < 20; attempt++) {
          try {
            int i = 0;
            for (; i < HIPPY_MODULES.length; i++) {
                if (!done[i]) {
                    done[i] = hookHippyModule(cl, HIPPY_MODULES[i]);
                }
            }
            for (int j = 0; j < DISPATCHERS.length; j++) {
                if (!done[i + j]) {
                    done[i + j] = hookDispatcher(cl, DISPATCHERS[j]);
                }
            }
            int k = i + DISPATCHERS.length;
            if (!done[k]) {
                done[k] = hookSignInJsPlugin(cl);
            }
            if (!done[k + 1]) {
                done[k + 1] = hookQipc(cl);
            }
            if (!done[k + 2]) {
                done[k + 2] = hookHttpListener(cl);
            }
          } catch (Throwable t) {
            Logger.e("install attempt " + attempt + " failed", t);
          }
            if (!console) {
                console = hookConsole(cl);
            }
            boolean all = x5 && debug && console;
            for (boolean b : done) {
                all &= b;
            }
            if (all) {
                break;
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        Logger.i("install finished, log file: " + Logger.filePath());
    }

    // ------------------------------------------------------------ activities

    /**
     * Remember the top activity and dump its view tree a few times after it appears, so we see
     * the page both before and after the user taps the check-in button.
     */
    private void hookActivities(ClassLoader cl) {
        final Handler h = new Handler(Looper.getMainLooper());
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    final Activity a = (Activity) param.thisObject;
                    lastActivity = a;
                    String cn = a.getClass().getName();
                    if (!isPageOfInterest(cn)) {
                        return;
                    }
                    Logger.i("[activity] resumed " + cn);
                    long[] delays = {400L, 1500L, 4000L, 9000L};
                    for (int i = 0; i < delays.length; i++) {
                        final int idx = i;
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Logger.dumpViews(a, "resume#" + idx);
                            }
                        }, delays[i]);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String cn = param.thisObject.getClass().getName();
                    if (isPageOfInterest(cn)) {
                        Logger.i("[activity] created " + cn);
                    }
                }
            });
            Logger.i("watching activity lifecycle");
        } catch (Throwable t) {
            Logger.e("hookActivities failed", t);
        }
        // The "我的" page is not its own activity in 9.2.65, so also record every real tap:
        // that gives the exact view (text / id / bounds) of the check-in pill and button.
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    android.view.View v = (android.view.View) param.thisObject;
                    if (v == null || !v.isShown()) {
                        return;
                    }
                    CharSequence text = v instanceof android.widget.TextView
                            ? ((android.widget.TextView) v).getText() : null;
                    int[] loc = new int[2];
                    try {
                        v.getLocationOnScreen(loc);
                    } catch (Throwable ignored) {
                    }
                    Logger.i("[click] " + v.getClass().getName()
                            + " text=\"" + text + "\" desc=\"" + v.getContentDescription() + "\""
                            + " id=" + Logger.idName(v)
                            + " bounds=" + loc[0] + "," + loc[1] + "-" + (loc[0] + v.getWidth())
                            + "," + (loc[1] + v.getHeight())
                            + " activity=" + (lastActivity == null ? "?" : lastActivity.getClass().getName()));
                    Logger.dumpViews(v.getRootView(), "page of click");
                }
            });
            Logger.i("watching View.performClick");
        } catch (Throwable t) {
            Logger.e("hook performClick failed", t);
        }
    }

    private static boolean isPageOfInterest(String cn) {
        String low = cn.toLowerCase();
        return low.contains("settingme") || low.contains("vip") || low.contains("browser")
                || low.contains("webview") || low.contains("hippy") || low.contains("card")
                || low.contains("sign") || low.contains("vas");
    }

    // ------------------------------------------------------------------ web

    private boolean hookX5(ClassLoader cl) {
        final XC_MethodHook intercept = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object req = findArg(param.args, "WebResourceRequest");
                String url = req == null ? null : str(Logger.call(req, "getUrl"));
                if (url == null && param.args != null) {
                    for (Object a : param.args) {
                        if (a instanceof String) {
                            url = (String) a;
                            break;
                        }
                    }
                }
                if (url == null || !url.startsWith("http")) {
                    return;
                }
                if (skip(url)) {
                    return;
                }
                StringBuilder sb = new StringBuilder("[net ");
                sb.append(req == null ? "url" : safeUpper(str(Logger.call(req, "getMethod")))).append("] ");
                sb.append(Logger.safe(url));
                if (req != null) {
                    Object headers = Logger.call(req, "getRequestHeaders");
                    if (headers instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) headers).entrySet()) {
                            String k = String.valueOf(e.getKey());
                            String v = String.valueOf(e.getValue());
                            if (k.toLowerCase().contains("cookie") || k.toLowerCase().contains("user-agent")) {
                                sb.append("  ").append(k).append("=<len ").append(v.length()).append('>');
                            } else {
                                sb.append("  ").append(k).append('=').append(Logger.safe(v));
                            }
                        }
                    }
                }
                if (param.args != null) {
                    for (Object a : param.args) {
                        if (a instanceof android.os.Bundle) {
                            sb.append("  extras=").append(Logger.render(a));
                        }
                    }
                }
                Logger.i(sb.toString());
            }
        };
        boolean ok = false;
        try {
            Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(WebViewClient(), "shouldInterceptRequest", intercept);
            ok = s != null && !s.isEmpty();
            Logger.i("watching X5 shouldInterceptRequest (" + (s == null ? 0 : s.size()) + " overloads)");
        } catch (Throwable t) {
            Logger.e("hook X5 client failed", t);
        }
        XC_MethodHook load = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object url = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (url == null) {
                    return;
                }
                String u = String.valueOf(url);
                if (u.startsWith("http")) {
                    Logger.i("[loadUrl] " + Logger.safe(u));
                    if (u.contains("vip.qq.com") || u.contains("ti.qq.com") || u.contains("signin")) {
                        final Object view = param.thisObject;
                        instrumentPage(view);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                replaySelfTest();
                            }
                        }, 9000L);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Object root = Logger.call(view, "getRootView");
                                if (root instanceof android.view.View) {
                                    Logger.dumpViews((android.view.View) root, "page " + view.getClass().getSimpleName());
                                }
                            }
                        }, 3000L);
                    }
                }
            }
        };
        try {
            Class<?> x5 = X5WebView();
            if (x5 != null) {
                XposedBridge.hookAllMethods(x5, "loadUrl", load);
                XposedBridge.hookAllMethods(x5, "loadUrlWithHeaders", load);
                XposedBridge.hookAllMethods(x5, "postUrl", load);
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        return ok;
    }

    private Class<?> WebViewClient() {
        Class<?> c = XposedHelpers.findClassIfExists(X5_WEBVIEW_CLIENT, Logger.cl());
        return c;
    }

    private Class<?> X5WebView() {
        return XposedHelpers.findClassIfExists(X5_WEBVIEW, Logger.cl());
    }

    /**
     * Transparent fetch/XHR wrapper injected into the sign-in page. It only copies what the page
     * already sends to the console, so the page keeps working exactly as before.
     */
    private static final String JS_PROBE = "(function(){if(window.__qs){return;}window.__qs=1;"
            + "function rep(k,u,m,b,s,t){try{console.log('QQSIGNLOG '+k+' | '+m+' | '+u+' | body='+String(b).slice(0,1200)+' | '+s+' | '+String(t).slice(0,1200));}catch(e){}"
            + "try{new Image().src='https://ti.qq.com/qqsignlog?k='+k+'&m='+encodeURIComponent(m)+'&s='+s"
            + "+'&u='+encodeURIComponent(String(u).slice(0,300))"
            + "+'&b='+encodeURIComponent(String(b).slice(0,700))"
            + "+'&r='+encodeURIComponent(String(t).slice(0,700));}catch(e){}}"
            + "var O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return O.apply(this,arguments);};"
            + "XMLHttpRequest.prototype.send=function(b){var x=this;try{x.addEventListener('loadend',function(){rep('xhr',x.__u,x.__m,b,x.status,x.responseText||'');});}catch(e){}"
            + "return S.apply(this,arguments);};"
            + "if(window.fetch){var F=window.fetch;window.fetch=function(i,n){var u=(typeof i==='string')?i:(i&&i.url);"
            + "var m=(n&&n.method)||(i&&i.method)||'GET';var b=(n&&n.body)||'';var p=F.apply(this,arguments);"
            + "try{p.then(function(r){r.clone().text().then(function(t){rep('fetch',u,m,b,r.status,t);});});}catch(e){}return p;};}"
            + "rep('injected',location.href,'-',0,'','');})();";

    /** Off the UI thread: ask the sign-in endpoint what it says with QQ's own cookies. */
    private void replaySelfTest() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SignInClient.selfTest();
                } catch (Throwable e) {
                    Logger.e("selftest failed", e);
                }
            }
        }, "QQSignLog-selftest");
        t.setDaemon(true);
        t.start();
    }

    private void instrumentPage(final Object webView) {        final Handler h = new Handler(Looper.getMainLooper());
        final String script = "javascript:" + JS_PROBE;
        Logger.i("instrumenting " + webView.getClass().getName() + " url="
                + Logger.safe(String.valueOf(Logger.call(webView, "getUrl"))));
        long[] delays = {1200L, 3000L, 6000L, 12000L};
        for (int i = 0; i < delays.length; i++) {
            final int which = i;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        XposedHelpers.callMethod(webView, "loadUrl", script);
                        Logger.i("injected js attempt " + which);
                    } catch (Throwable t) {
                        Logger.e("inject failed", t);
                    }
                }
            }, delays[i]);
        }
    }

    /** Read back what the injected JS reports through the page console. */
    private boolean hookConsole(ClassLoader cl) {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String msg = null;
                if (param.args != null && param.args.length > 0) {
                    Object a = param.args[0];
                    if (a instanceof String) {
                        msg = (String) a;
                    } else {
                        Object m = Logger.call(a, "getMessage");
                        msg = m == null ? String.valueOf(a) : String.valueOf(m);
                    }
                }
                if (msg != null && msg.startsWith("QQSIGNLOG")) {
                    Logger.i("[js] " + Logger.safe(msg));
                }
            }
        };
        boolean ok = false;
        try {
            Class<?> c = XposedHelpers.findClassIfExists("com.tencent.smtt.sdk.WebChromeClient", cl);
            if (c != null) {
                Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(c, "onConsoleMessage", cb);
                ok = s != null && !s.isEmpty();
                Logger.i("watching X5 console (" + (s == null ? 0 : s.size()) + " overloads)");
            }
        } catch (Throwable t) {
            Logger.e("hookConsole failed", t);
        }
        try {
            XposedBridge.hookAllMethods(android.webkit.WebChromeClient.class, "onConsoleMessage", cb);
        } catch (Throwable ignored) {
        }
        return ok;
    }

    private boolean enableDevtools(ClassLoader cl) {
        try {
            Class<?> sys = Class.forName("android.webkit.WebView");
            XposedHelpers.callStaticMethod(sys, "setWebContentsDebuggingEnabled", Boolean.TRUE);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> x5 = XposedHelpers.findClassIfExists(X5_WEBVIEW, cl);
            if (x5 == null) {
                return false;
            }
            XposedHelpers.callStaticMethod(x5, "setWebContentsDebuggingEnabled", Boolean.TRUE);
            Logger.i("x5 remote debugging enabled");
            return true;
        } catch (Throwable t) {
            Logger.e("enableDevtools failed", t);
            return false;
        }
    }

    private void hookSystemWebView() {
        try {
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String u = (String) param.args[0];
                    if (u.startsWith("http")) {
                        Logger.i("[sys loadUrl] " + Logger.safe(u));
                    }
                }
            });
            XposedBridge.hookAllMethods(android.webkit.WebViewClient.class, "shouldInterceptRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    Object r = param.args[1];
                    String url = r instanceof String ? (String) r : str(Logger.call(r, "getUrl"));
                    if (url == null || !url.startsWith("http") || skip(url)) {
                        return;
                    }
                    Logger.i("[sys net " + safeUpper(str(Logger.call(r, "getMethod"))) + "] " + Logger.safe(url));
                }
            });
            Logger.i("watching system webview");
        } catch (Throwable t) {
            Logger.e("hookSystemWebView failed", t);
        }
    }

    private static boolean skip(String url) {
        String low = url.toLowerCase();
        for (String s : SKIP_SUFFIX) {
            if (low.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static Object findArg(Object[] args, String typePart) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a != null && a.getClass().getName().contains(typePart)) {
                return a;
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof android.net.Uri) {
            return o.toString();
        }
        return String.valueOf(o);
    }

    private static String safeUpper(String s) {
        return s == null ? "GET?" : s.toUpperCase();
    }

    // ---------------------------------------------------------------- hippy

    private boolean hookDispatcher(ClassLoader cl, String className) {
        final Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            return false;
        }
        // 9.2.65 / 9.3.75 route JS -> native through callNatives(Message); older hippy used
        // callNativeMethod(module, method, args, ctx). Hook whichever exists.
        String[] names = {"callNativeMethod", "callNatives", "doCallNatives"};
        int total = 0;
        for (final String name : names) {
            try {
                Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder sb = new StringBuilder("[dispatch ").append(c.getSimpleName())
                                .append('#').append(name).append("] ");
                        if (param.args != null) {
                            for (int i = 0; i < param.args.length; i++) {
                                Object a = param.args[i];
                                if (a != null && a.getClass().getName().contains("NativeArguments")) {
                                    sb.append("module=").append(Logger.call(a, "getModuleName"))
                                            .append(" method=").append(Logger.call(a, "getMethodName"))
                                            .append(" args=").append(Logger.render(Logger.call(a, "getArguments")));
                                } else {
                                    sb.append(Logger.dumpObject(a, 14));
                                }
                                sb.append(' ');
                            }
                        }
                        Logger.i(sb.toString());
                    }
                });
                if (s != null) {
                    total += s.size();
                }
            } catch (Throwable t) {
                Logger.e("hookDispatcher " + className + '.' + name + " failed", t);
            }
        }
        Logger.i("watching dispatcher " + className + " (" + total + " overloads)");
        if (total == 0) {
            StringBuilder sb = new StringBuilder("[dispatcher " + c.getSimpleName() + " methods] ");
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length && i < 40; i++) {
                sb.append(ms[i].getName()).append('/').append(ms[i].getParameterTypes().length).append(' ');
            }
            Logger.i(sb.toString());
        }
        return true;
    }

    private boolean hookHippyModule(ClassLoader cl, String className) {
        Class<?> c;
        try {
            c = XposedHelpers.findClassIfExists(className, cl);
        } catch (Throwable t) {
            return false;
        }
        if (c == null) {
            return false;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getName().startsWith("lambda$")
                    || m.getName().startsWith("access$")) {
                continue;
            }
            boolean exposed = false;
            for (Annotation a : m.getAnnotations()) {
                if (a.annotationType().getName().endsWith("HippyMethod")) {
                    exposed = true;
                    break;
                }
            }
            if (!exposed && m.getParameterTypes().length == 0) {
                continue;
            }
            if (!exposed && !isPublicInstance(m)) {
                continue;
            }
            m.setAccessible(true);
            try {
                XposedBridge.hookMethod(m, new HippyCall(m, exposed));
                hooked++;
            } catch (Throwable t) {
                Logger.e("hook fail " + className + "#" + m.getName(), t);
            }
        }
        Logger.i("watching " + className + " (" + hooked + " js-visible calls)");
        return hooked > 0;
    }

    private static boolean isPublicInstance(Method m) {
        int mod = m.getModifiers();
        return Modifier.isPublic(mod) && !Modifier.isStatic(mod) && !Modifier.isAbstract(mod);
    }

    private class HippyCall extends XC_MethodHook {
        private final Method method;
        private final boolean exposed;

        HippyCall(Method method, boolean exposed) {
            this.method = method;
            this.exposed = exposed;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            StringBuilder sb = new StringBuilder();
            sb.append("[hippy ").append(method.getDeclaringClass().getSimpleName())
                    .append('#').append(method.getName());
            if (!exposed) {
                sb.append(" (internal)");
            }
            sb.append("] ");
            Object[] args = param.args;
            if (args == null || args.length == 0) {
                sb.append("(no args)");
            } else {
                for (int i = 0; i < args.length; i++) {
                    sb.append("arg").append(i).append('=').append(Logger.render(args[i])).append("  ");
                }
            }
            Object res = param.getResult();
            if (res != null) {
                sb.append(" => ").append(Logger.render(res));
            }
            Logger.i(sb.toString());
            if (args != null) {
                for (Object a : args) {
                    watchCallback(a);
                }
            }
        }
    }

    /** Hook the promise/callback handed to a Hippy module so the response is logged too. */
    private void watchCallback(Object obj) {
        if (obj == null) {
            return;
        }
        String cn = obj.getClass().getName();
        if (!cn.contains("Promise") && !cn.contains("Callback") && !cn.contains("Listener")) {
            return;
        }
        if (XposedHelpers.getAdditionalInstanceField(obj, "qqsignlog") != null) {
            return;
        }
        XposedHelpers.setAdditionalInstanceField(obj, "qqsignlog", Boolean.TRUE);
        int n = 0;
        for (Method m : obj.getClass().getMethods()) {
            String name = m.getName();
            if (!name.equals("resolve") && !name.equals("reject") && !name.equals("onResult")
                    && !name.equals("onResponse") && !name.equals("callback") && !name.equals("invoke")
                    && !name.equals("onSuccess") && !name.equals("onFail")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Logger.i("[reply " + m.getDeclaringClass().getSimpleName() + '#' + m.getName() + "] "
                                + Logger.dumpObject(param.args == null || param.args.length == 0 ? null : param.args, 6));
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n > 0) {
            Logger.i("watching reply of " + cn + " (" + n + " methods)");
        }
    }

    private boolean hookHttpListener(ClassLoader cl) {
        final Class<?> c = XposedHelpers.findClassIfExists(
                "com.tencent.hippy.qq.module.HippyQQHttpModule$HttpListener", cl);
        if (c == null) {
            return false;
        }
        try {
            Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(c, "onHttpFinish", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object resp = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    Logger.i("[http response] " + Logger.dumpObject(resp, 12));
                }
            });
            Logger.i("watching hippy http responses (" + (s == null ? 0 : s.size()) + " overloads)");
        } catch (Throwable t) {
            Logger.e("hookHttpListener failed", t);
        }
        return true;
    }

    // ------------------------------------------------------------ web / jsb

    private boolean hookSignInJsPlugin(ClassLoader cl) {
        final Class<?> c = XposedHelpers.findClassIfExists(SIGNIN_JS_PLUGIN, cl);
        if (c == null) {
            return false;
        }
        try {
            XposedBridge.hookAllMethods(c, "handleJsRequest", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("[signIn jsb] ");
                    if (param.args != null) {
                        for (Object a : param.args) {
                            sb.append(Logger.render(a)).append(" | ");
                        }
                    }
                    Logger.i(sb.toString());
                    Logger.dumpSignInPrefs();
                    Activity a = lastActivity;
                    if (a != null) {
                        Logger.dumpViews(a, "signedCallback");
                    }
                }
            });
            Logger.i("watching " + SIGNIN_JS_PLUGIN);
        } catch (Throwable t) {
            Logger.e("hookSignInJsPlugin failed", t);
        }
        return true;
    }

    private boolean hookQipc(ClassLoader cl) {
        final Class<?> c = XposedHelpers.findClassIfExists(QIPC_PROXY, cl);
        if (c == null) {
            return false;
        }
        try {
            XposedBridge.hookAllMethods(c, "callServer", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2) {
                        return;
                    }
                    String module = String.valueOf(param.args.length > 0 ? param.args[0] : "");
                    String action = String.valueOf(param.args.length > 1 ? param.args[1] : "");
                    String extra = param.args.length > 2 ? Logger.render(param.args[2]) : "";
                    if (module.toLowerCase().contains("sign") || action.toLowerCase().contains("sign")
                            || extra.toLowerCase().contains("sign")) {
                        Logger.i("[qipc] module=" + module + " action=" + action + " data=" + extra);
                    }
                }
            });
            Logger.i("watching QIPC callServer");
        } catch (Throwable t) {
            Logger.e("hookQipc failed", t);
        }
        return true;
    }
}
