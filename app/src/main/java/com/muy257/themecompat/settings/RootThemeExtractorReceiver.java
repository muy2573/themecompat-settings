package com.muy257.themecompat.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Performs a narrowly-scoped theme-image extraction outside target app processes. */
public final class RootThemeExtractorReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !intent.hasExtra(RootThemeBackgroundLoader.EXTRA_NIGHT)) return;
        final PendingResult pending = goAsync();
        final boolean night = intent.getBooleanExtra(RootThemeBackgroundLoader.EXTRA_NIGHT, false);
        final String targetPackage = intent.getStringExtra(
                RootThemeBackgroundLoader.EXTRA_TARGET_PACKAGE);
        new Thread(() -> {
            try {
                if ("tv.danmaku.bili".equals(targetPackage)) {
                    RootThemeBackgroundLoader.extractForBilibiliCache(
                            context.getApplicationContext(), night);
                } else {
                    RootThemeBackgroundLoader.extractForCameraCache(
                            context.getApplicationContext(), night);
                }
            } catch (Throwable error) {
                Log.e("ThemeCompatRoot", "Companion extraction failed", error);
            } finally {
                pending.finish();
            }
        }, "ThemeCompatRootExtractor").start();
    }
}
