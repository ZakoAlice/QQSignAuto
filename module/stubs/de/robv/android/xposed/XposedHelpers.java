package de.robv.android.xposed;

import java.lang.reflect.Method;
import java.util.Set;

/** Compile-only stub of the Xposed API. */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    public static Set<Method> findAllMethods(Class<?> clazz) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callObjectMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static long getLongField(Object obj, String fieldName) {
        return 0L;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        return null;
    }
}
