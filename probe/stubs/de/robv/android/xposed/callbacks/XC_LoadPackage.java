package de.robv.android.xposed.callbacks;

/** Compile-only stub of the Xposed API. */
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public android.content.pm.ApplicationInfo appInfo;
        public String processName;
        public String packageName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
