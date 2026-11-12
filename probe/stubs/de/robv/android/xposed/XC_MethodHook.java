package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only stub of the Xposed API. */
public abstract class XC_MethodHook {
    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class Unhook {
        public Member getMember() {
            return null;
        }

        public void unhook() {
        }
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Throwable throwable;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public boolean hasThrowable() {
            return false;
        }

        public void setThrowable(Throwable throwable) {
        }

        public Object getThrowable() {
            return null;
        }
    }
}
