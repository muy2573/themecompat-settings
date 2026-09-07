package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModule;

/**
 * Exact recorder list-card adapter. The app's r5.i ItemDecoration paints the
 * record-file card group directly on Canvas from its `m` drawable; no View
 * background or foreground owns that opaque surface.
 */
final class RecorderListSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.android.soundrecorder";
    private static final String DECORATION_CLASS = "r5.i";
    private static final int LIGHT_CARD_ALPHA = 0x48;

    private final XposedModule module;
    private final ClassLoader classLoader;

    RecorderListSurfaceAdapter(XposedModule module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            Class<?> type = Class.forName(DECORATION_CLASS, false, classLoader);
            Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            module.hook(constructor).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                Context context = args.length > 0 && args[0] instanceof Context
                        ? (Context) args[0] : null;
                apply(chain.getThisObject(), context);
                return result;
            });
            module.log(Log.INFO, "RecorderCards", "Hooked r5.i(Context) record-card painter");
        } catch (Throwable error) {
            module.log(Log.ERROR, "RecorderCards", "Cannot hook r5.i record-card painter", error);
        }
    }

    private void apply(Object decoration, Context context) {
        if (decoration == null || context == null || !PACKAGE_NAME.equals(context.getPackageName())
                || isNight(context)) return;
        try {
            Field field = decoration.getClass().getDeclaredField("m");
            field.setAccessible(true);
            Object value = field.get(decoration);
            if (!(value instanceof Drawable)) return;
            Drawable background = (Drawable) value;
            background.setAlpha(LIGHT_CARD_ALPHA);
            module.log(Log.INFO, "RecorderCards", "light r5.i cardGroupBackground -> alpha=0x48");
        } catch (Throwable error) {
            module.log(Log.WARN, "RecorderCards", "Cannot adjust r5.i cardGroupBackground", error);
        }
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
