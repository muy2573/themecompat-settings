package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Exact MIUIX PreferenceFragment card renderer adjustment for one hosted app.
 * OS3 copies preferenceCardGroupBackground into a Paint and then draws the card
 * in Canvas, so changing View backgrounds or generic Recycler decorations cannot work.
 */
final class PreferenceCardPaintAdapter {
    /*
     * MIUIX is not binary-identical across the system packages.  Milink's
     * copy obfuscates this decoration as `$c`; the Launcher copy keeps its
     * source name as `$FrameDecoration`.  Both implementations own the
     * preferenceCardGroupBackground drawable and paint it on Canvas.
     */
    private static final String[] PAINTER_CLASSES = {
            "miuix.preference.PreferenceFragment$c",
            "miuix.preference.PreferenceFragment$FrameDecoration"
    };
    private static final int LIGHT_CARD_ALPHA = 0x48;

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final String targetPackage;
    private final Map<Object, Context> contexts = Collections.synchronizedMap(new WeakHashMap<>());

    PreferenceCardPaintAdapter(XposedModule module, ClassLoader classLoader, String targetPackage) {
        this.module = module;
        this.classLoader = classLoader;
        this.targetPackage = targetPackage;
    }

    void install() {
        Throwable lastError = null;
        for (String painterClass : PAINTER_CLASSES) {
            try {
                Class<?> type = Class.forName(painterClass, false, classLoader);
                int constructors = hookConstructors(type);
                int refreshes = hookRefreshMethods(type);
                write(Log.INFO, "Hooked MIUIX preference painter=" + painterClass + " package="
                        + targetPackage + " constructors=" + constructors
                        + " refreshes=" + refreshes, null);
                return;
            } catch (Throwable error) {
                lastError = error;
            }
        }
        write(Log.ERROR, "Cannot hook any MIUIX preference painter package=" + targetPackage,
                lastError);
    }

    private int hookConstructors(Class<?> type) {
        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!hasContextParameter(constructor.getParameterTypes())) continue;
            try {
                constructor.setAccessible(true);
                module.hook(constructor).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    Object result = chain.proceed(args);
                    Context context = findContext(args);
                    if (context != null) contexts.put(chain.getThisObject(), context);
                    apply(chain.getThisObject(), context);
                    return result;
                });
                count++;
            } catch (Throwable ignored) {
                // A vendor-synthetic overload can be unavailable; inspect the others.
            }
        }
        return count;
    }

    private int hookRefreshMethods(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            // `y()` initializes the Paint; the one-Context refresh runs on uiMode changes.
            if (!(parameters.length == 0 || (parameters.length == 1
                    && Context.class.isAssignableFrom(parameters[0])))) continue;
            try {
                method.setAccessible(true);
                module.hook(method).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    Object result = chain.proceed(args);
                    Context context = findContext(args);
                    if (context == null) context = contexts.get(chain.getThisObject());
                    apply(chain.getThisObject(), context);
                    return result;
                });
                count++;
            } catch (Throwable ignored) {
                // Diagnostic-free; the constructor hook remains sufficient on this build.
            }
        }
        return count;
    }

    private void apply(Object painter, Context context) {
        if (painter == null || context == null || !targetPackage.equals(context.getPackageName())
                || isNight(context)) return;
        try {
            Paint paint = null;
            ColorDrawable cardDrawable = null;
            for (Class<?> type = painter.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(painter);
                    if (value instanceof Paint) paint = (Paint) value;
                    if (value instanceof Drawable && value instanceof ColorDrawable
                            && isOpaqueNeutral((ColorDrawable) value)) {
                        cardDrawable = (ColorDrawable) value;
                    }
                }
            }
            if (paint == null || cardDrawable == null) return;
            int source = cardDrawable.getColor();
            int translucent = Color.argb(LIGHT_CARD_ALPHA, Color.red(source), Color.green(source), Color.blue(source));
            // This OS3 build selects between Paint.drawRoundRect and the stored
            // ColorDrawable at draw time. Keep both representations in sync.
            if (paint.getColor() == translucent && cardDrawable.getAlpha() == LIGHT_CARD_ALPHA) return;
            paint.setColor(translucent);
            cardDrawable.setAlpha(LIGHT_CARD_ALPHA);
            write(Log.INFO, "light preferenceCardGroupBackground/ColorDrawable -> alpha=0x48 package="
                    + targetPackage + " source=" + String.format("#%08X", source), null);
        } catch (Throwable error) {
            write(Log.WARN, "Cannot adjust preference card paint package=" + targetPackage, error);
        }
    }

    private static boolean hasContextParameter(Class<?>[] types) {
        for (Class<?> type : types) if (Context.class.isAssignableFrom(type)) return true;
        return false;
    }

    private static Context findContext(Object[] values) {
        for (Object value : values) if (value instanceof Context) return (Context) value;
        return null;
    }

    private static boolean isOpaqueNeutral(ColorDrawable drawable) {
        int color = drawable.getColor();
        if (Color.alpha(color) != 255) return false;
        int min = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
        int max = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        return max - min <= 24;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void write(int level, String message, Throwable error) {
        if (error == null) module.log(level, "PreferenceCards", message);
        else module.log(level, "PreferenceCards", message, error);
        if (error == null) Log.println(level, "PreferenceCards", message);
        else Log.println(level, "PreferenceCards", message + " " + error.getClass().getSimpleName());
    }
}
