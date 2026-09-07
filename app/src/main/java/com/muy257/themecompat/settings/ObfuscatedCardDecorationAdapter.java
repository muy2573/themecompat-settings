package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Light-mode card alpha for the last two standalone pages, Mi Share and
 * Cloud, whose card renderer keeps R8-obfuscated names.
 *
 * jadx on the shipped APKs proved both pages share one vendor hierarchy: a
 * preference host's inner ItemDecoration (Mi Share c7.l$c extends g7.a,
 * Cloud j6.l$c extends o6.a) paints every rounded row through one inherited
 * Paint, re-reading the color from a card ColorDrawable field (f4222r /
 * f18504s) before each drawPath.  androidx is obfuscated in these apps too,
 * so ItemDecoration.onDraw is only reachable as the declared method "g" on
 * the shared base class; a single hook there covers every subclass because
 * none of them overrides it.
 *
 * Fields are re-scanned before every draw instead of being captured once:
 * Mi Share's C()/w() and Cloud's F()/H() replace the color field and repaint
 * the Paint after a day/night or floating-window change, so a one-time
 * capture would go stale.  Dark mode stays observational except for
 * restoring an alpha this adapter itself dimmed, so a theme refresh that
 * reuses the same drawable cannot leak light-mode translucency into dark
 * cards.  Radio-button pressed/checked colors (f4223s/f4224t,
 * f18505t/f18506u) are intentional accents and are deliberately untouched.
 */
final class ObfuscatedCardDecorationAdapter {
    private static final int LIGHT_CARD_ALPHA = 0x48;

    /** Obfuscated ItemDecoration base class whose declared "g" is onDraw. */
    private static final Map<String, String> BASE_RENDERERS;

    static {
        Map<String, String> renderers = new HashMap<>();
        renderers.put("com.miui.mishare.connectivity", "g7.a");
        renderers.put("com.miui.cloudservice", "o6.a");
        // Xiaomi Account keeps vendor class names but obfuscates androidx the
        // same way: miuix.preference.m$e extends wb.a with the identical
        // Paint (f25824a) plus card ColorDrawable field (f21724s) pattern.
        renderers.put("com.xiaomi.account", "wb.a");
        BASE_RENDERERS = Collections.unmodifiableMap(renderers);
    }

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final String targetPackage;
    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    private volatile boolean logged;

    ObfuscatedCardDecorationAdapter(XposedModule module, ClassLoader classLoader,
            String targetPackage) {
        this.module = module;
        this.classLoader = classLoader;
        this.targetPackage = targetPackage;
    }

    static boolean supports(String packageName) {
        return BASE_RENDERERS.containsKey(packageName);
    }

    void install() {
        String baseName = BASE_RENDERERS.get(targetPackage);
        if (baseName == null) return;
        try {
            Class<?> base = Class.forName(baseName, false, classLoader);
            int draws = 0;
            for (Method method : base.getDeclaredMethods()) {
                if (!"g".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 3 || types[0] != Canvas.class) continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    applyBeforeDraw(chain.getThisObject(), args);
                    return chain.proceed(args);
                });
                draws++;
            }
            module.log(Log.INFO, "ObfCards", "installed package=" + targetPackage
                    + " base=" + baseName + " draws=" + draws);
        } catch (Throwable error) {
            module.log(Log.ERROR, "ObfCards", "cannot install package=" + targetPackage, error);
        }
    }

    private void applyBeforeDraw(Object renderer, Object[] args) {
        if (renderer == null) return;
        Context context = contextFrom(args);
        if (context == null || !targetPackage.equals(context.getPackageName())) return;
        boolean night = isNight(context);
        int drawables = 0;
        int paints = 0;
        for (Field field : fieldsOf(renderer.getClass())) {
            Object value;
            try {
                value = field.get(renderer);
            } catch (Throwable error) {
                continue;
            }
            if (value instanceof Paint) {
                if (adjust((Paint) value, night)) paints++;
            } else if (value instanceof Drawable) {
                if (adjustDrawable((Drawable) value, night)) drawables++;
            }
        }
        if ((drawables > 0 || paints > 0) && !logged) {
            logged = true;
            module.log(Log.INFO, "ObfCards", "applied package=" + targetPackage
                    + " renderer=" + renderer.getClass().getName() + " mode="
                    + (night ? "dark-restore" : "light") + " drawables=" + drawables
                    + " paints=" + paints);
        }
    }

    private boolean adjust(Paint paint, boolean night) {
        int color = paint.getColor();
        int alpha = Color.alpha(color);
        if (night) {
            if (alpha != LIGHT_CARD_ALPHA || !isNearWhite(color)) return false;
            paint.setColor(Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)));
            return true;
        }
        if (alpha != 255 || !isNearWhite(color) || alpha == LIGHT_CARD_ALPHA) return false;
        paint.setColor(Color.argb(LIGHT_CARD_ALPHA, Color.red(color),
                Color.green(color), Color.blue(color)));
        return true;
    }

    private boolean adjustDrawable(Drawable drawable, boolean night) {
        int color;
        if (drawable instanceof ColorDrawable) {
            color = ((ColorDrawable) drawable).getColor();
        } else if (drawable instanceof GradientDrawable) {
            android.content.res.ColorStateList colors = ((GradientDrawable) drawable).getColor();
            if (colors == null) return false;
            color = colors.getDefaultColor();
        } else {
            // Non-color card skins are a deliberate design and stay untouched.
            return false;
        }
        int alpha = drawable.getAlpha();
        if (night) {
            if (alpha != LIGHT_CARD_ALPHA || !isNearWhite(color)) return false;
            drawable.mutate();
            drawable.setAlpha(255);
            return true;
        }
        if (Color.alpha(color) != 255 || !isNearWhite(color)
                || alpha == LIGHT_CARD_ALPHA) return false;
        drawable.mutate();
        drawable.setAlpha(LIGHT_CARD_ALPHA);
        return true;
    }

    private Field[] fieldsOf(Class<?> type) {
        Field[] fields = fieldCache.get(type);
        if (fields != null) return fields;
        List<Field> collected = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    collected.add(field);
                } catch (Throwable ignored) {
                    // Inaccessible fields simply stay unexamined.
                }
            }
        }
        fields = collected.toArray(new Field[0]);
        fieldCache.put(type, fields);
        return fields;
    }

    private static Context contextFrom(Object[] values) {
        for (Object value : values) {
            if (value instanceof Context) return (Context) value;
            if (value instanceof View) return ((View) value).getContext();
        }
        return null;
    }

    private static boolean isNearWhite(int color) {
        return Color.red(color) >= 226 && Color.green(color) >= 226 && Color.blue(color) >= 226;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
