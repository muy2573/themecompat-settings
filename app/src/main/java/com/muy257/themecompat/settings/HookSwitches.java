package com.muy257.themecompat.settings;

import android.content.SharedPreferences;
import android.os.SystemClock;

import io.github.libxposed.api.XposedModule;

/**
 * Per-app kill switch for the runtime hook layer.  The "Hook 软件" list
 * writes the choice into the LSPosed remote preferences ("hook_switches",
 * key "hook_<package>"); HookEntry consults the same store before installing
 * any adapter.  Hooks install at process start, so a flip reaches a running
 * app on its next launch (the Hook page's restart button forces it).
 * Every app defaults ON; the control-center artwork hook on SystemUI is the
 * owner-designated exception and ships OFF.
 */
final class HookSwitches {
    private static final String STORE = "hook_switches";
    static final String PREFIX = "hook_";
    private static final String DEFAULT_OFF_PACKAGE = "com.android.systemui";
    private static final long REFRESH_INTERVAL_MS = 3000;

    private static volatile XposedModule owner;
    private static volatile SharedPreferences store;
    private static volatile long lastRead;
    private static volatile String bindError;
    private static volatile String lastLoggedError;
    /** Explicit user choices only; missing keys fall back to defaults. */
    private static final java.util.HashMap<String, Boolean> values = new java.util.HashMap<>();

    private HookSwitches() { }

    static boolean defaultEnabled(String packageName) {
        return !DEFAULT_OFF_PACKAGE.equals(packageName);
    }

    static String key(String packageName) {
        return PREFIX + packageName;
    }

    /** Hook side: bind once at package load, then poll the cache cheaply.
     *  A failed first bind (service not reachable that early) is retried
     *  lazily on every refresh instead of being lost forever. */
    static void bindIfPossible(XposedModule module) {
        if (owner == null) owner = module;
        if (store != null) return;
        tryBind();
    }

    private static void tryBind() {
        XposedModule module = owner;
        if (module == null) return;
        try {
            store = module.getRemotePreferences(STORE);
            bindError = null;
        } catch (Throwable error) {
            bindError = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
        }
    }

    static boolean enabled(String packageName) {
        refresh();
        synchronized (values) {
            Boolean explicit = values.get(packageName);
            return explicit != null ? explicit : defaultEnabled(packageName);
        }
    }

    private static void refresh() {
        SharedPreferences preferences = store;
        if (preferences == null) {
            tryBind();
            if (bindError != null && store == null) {
                // Log at most once per bind failure state.
                XposedModule module = owner;
                if (module != null && !bindError.equals(lastLoggedError)) {
                    lastLoggedError = bindError;
                    module.log(android.util.Log.WARN, "HookSwitches",
                            "remote prefs unavailable: " + bindError);
                }
                return;
            }
            preferences = store;
            if (preferences == null) return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < REFRESH_INTERVAL_MS) return;
        lastRead = now;
        java.util.HashMap<String, Boolean> snapshot = new java.util.HashMap<>();
        try {
            for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith(PREFIX)
                        && entry.getValue() instanceof Boolean) {
                    snapshot.put(key.substring(PREFIX.length()), (Boolean) entry.getValue());
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        synchronized (values) {
            values.clear();
            values.putAll(snapshot);
        }
    }
}
