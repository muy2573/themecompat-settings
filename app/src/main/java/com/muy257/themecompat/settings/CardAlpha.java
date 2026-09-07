package com.muy257.themecompat.settings;

import android.content.SharedPreferences;
import android.os.SystemClock;

import io.github.libxposed.api.XposedModule;

/**
 * Owner-tunable card translucency shared by every adapter that frosts a
 * card.  Values live in the LSPosed remote preferences written by the
 * module's own UI ("card_alpha" store, byte alpha 0-255); the hook side
 * re-reads them at most every few seconds so per-draw callers stay cheap
 * and a slider change reaches running apps without a restart of the module.
 * Defaults are the owner's validated references: 0x48 daylight, 0x3D night.
 */
final class CardAlpha {
    private static final String STORE = "card_alpha";
    private static final String KEY_LIGHT = "light";
    private static final String KEY_DARK = "dark";
    private static final long REFRESH_INTERVAL_MS = 3000;

    private static volatile SharedPreferences store;
    private static volatile long lastRead;
    private static volatile int light = 0x48;
    private static volatile int dark = 0x3D;

    private CardAlpha() { }

    static void bind(XposedModule module) {
        if (store != null) return;
        try {
            store = module.getRemotePreferences(STORE);
            refresh();
        } catch (Throwable ignored) {
            // Older LSPosed builds without remote preferences keep defaults.
        }
    }

    static int light() {
        refresh();
        return light;
    }

    static int dark() {
        refresh();
        return dark;
    }

    private static void refresh() {
        SharedPreferences preferences = store;
        if (preferences == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < REFRESH_INTERVAL_MS) return;
        lastRead = now;
        try {
            light = clamp(preferences.getInt(KEY_LIGHT, 0x48));
            dark = clamp(preferences.getInt(KEY_DARK, 0x3D));
        } catch (Throwable ignored) {
            // The store vanished mid-read; keep the previous values.
        }
    }

    private static int clamp(int alpha) {
        if (alpha < 0) return 0;
        if (alpha > 255) return 255;
        return alpha;
    }
}
