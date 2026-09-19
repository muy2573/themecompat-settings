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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Light-mode card alpha for standalone pages whose card renderer keeps
 * R8-obfuscated names or moved between MIUIX releases.
 *
 * jadx on the shipped APKs proved both pages share one vendor hierarchy: a
 * preference host's inner ItemDecoration paints every rounded row through one
 * inherited Paint, re-reading the color from a card ColorDrawable field before
 * each drawPath. Current Cloud builds use h6.l$c and l6.f over m6.a; o6.a has
 * become an unrelated animator. Newer Account builds instead declare the
 * draw method directly on miuix.preference.m$e with a different name and
 * parameter count. Candidate classes are accepted only when they expose a
 * Canvas draw method with a View/RecyclerView parameter, so unrelated reused
 * obfuscated names are rejected.
 *
 * Fields are re-scanned before every draw instead of being captured once:
 * Mi Share and Cloud refresh methods replace the color field and repaint
 * the Paint after a day/night or floating-window change, so a one-time
 * capture would go stale.  Dark mode stays observational except for
 * restoring an alpha this adapter itself dimmed, so a theme refresh that
 * reuses the same drawable cannot leak light-mode translucency into dark
 * cards.  Radio-button pressed/checked colors (f4223s/f4224t,
 * f18505t/f18506u) are intentional accents and are deliberately untouched.
 */
final class ObfuscatedCardDecorationAdapter {
    private static final int LIGHT_CARD_ALPHA = 0x48;

    /** Newest first, followed by the renderer used by older system-app builds. */
    private static final Map<String, String[]> RENDERER_CANDIDATES;

    static {
        Map<String, String[]> renderers = new HashMap<>();
        renderers.put("com.miui.mishare.connectivity", new String[]{"g7.a"});
        // HyperOS 3 R-25.9 uses both the generic preference decoration and a
        // standalone RecyclerView decoration on the Cloud home page.  The
        // previous o6.a name is now an unrelated animator.
        renderers.put("com.miui.cloudservice", new String[]{"h6.l$c", "l6.f", "o6.a"});
        // Account R-25.10 moved the ItemDecoration implementation into the
        // MIUIX fragment inner class.  Older builds still use wb.a.
        renderers.put("com.xiaomi.account", new String[]{"miuix.preference.m$e", "wb.a"});
        RENDERER_CANDIDATES = Collections.unmodifiableMap(renderers);
    }

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final String targetPackage;
    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    private final Map<ColorDrawable, Integer> originalDrawableColors =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<String> loggedRenderers = ConcurrentHashMap.newKeySet();

    ObfuscatedCardDecorationAdapter(XposedModule module, ClassLoader classLoader,
            String targetPackage) {
        this.module = module;
        this.classLoader = classLoader;
        this.targetPackage = targetPackage;
    }

    static boolean supports(String packageName) {
        return RENDERER_CANDIDATES.containsKey(packageName);
    }

    void install() {
        String[] candidates = RENDERER_CANDIDATES.get(targetPackage);
        if (candidates == null) return;
        List<String> misses = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        for (String candidate : candidates) {
            try {
                Class<?> base = Class.forName(candidate, false, classLoader);
                int draws = 0;
                for (Method method : base.getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length < 2 || types[0] != Canvas.class
                            || !hasViewParameter(types)) continue;
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        applyBeforeDraw(chain.getThisObject(), args);
                        return chain.proceed(args);
                    });
                    draws++;
                }
                if (draws > 0) {
                    installed.add(candidate + ":" + draws);
                    continue;
                }
                misses.add(candidate + ":no-compatible-draw");
            } catch (ClassNotFoundException error) {
                misses.add(candidate + ":absent");
            } catch (Throwable error) {
                module.log(Log.WARN, "ObfCards", "candidate failed package=" + targetPackage
                        + " renderer=" + candidate, error);
                misses.add(candidate + ":error");
            }
        }
        if (!installed.isEmpty()) {
            module.log(Log.INFO, "ObfCards", "installed package=" + targetPackage
                    + " renderers=" + String.join(",", installed));
            return;
        }
        module.log(Log.WARN, "ObfCards", "no compatible renderer package=" + targetPackage
                + " candidates=" + String.join(",", misses));
    }

    private static boolean hasViewParameter(Class<?>[] types) {
        for (int index = 1; index < types.length; index++) {
            if (View.class.isAssignableFrom(types[index])) return true;
        }
        return false;
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
        String rendererName = renderer.getClass().getName();
        if ((drawables > 0 || paints > 0) && loggedRenderers.add(rendererName)) {
            module.log(Log.INFO, "ObfCards", "applied package=" + targetPackage
                    + " renderer=" + rendererName + " mode="
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
        if ("com.miui.cloudservice".equals(targetPackage)
                && drawable instanceof ColorDrawable) {
            return adjustCloudSourceColor((ColorDrawable) drawable, night);
        }
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

    /**
     * Cloud's h6.l$c and l6.f do not draw their ColorDrawable fields. They
     * call getColor() during every draw and copy that value into an inherited
     * Paint, overwriting any Paint alpha applied at method entry. Adjust the
     * source color itself so that the renderer copies the translucent value.
     */
    private boolean adjustCloudSourceColor(ColorDrawable drawable, boolean night) {
        int current = drawable.getColor();
        Integer original = originalDrawableColors.get(drawable);
        if (night) {
            if (original == null || current == original) return false;
            drawable.mutate();
            drawable.setColor(original);
            return true;
        }
        if (Color.alpha(current) != 255 || !isNearWhite(current)) return false;
        if (original == null) {
            original = current;
            originalDrawableColors.put(drawable, original);
        }
        int target = Color.argb(LIGHT_CARD_ALPHA, Color.red(original),
                Color.green(original), Color.blue(original));
        if (current == target) return false;
        drawable.mutate();
        drawable.setColor(target);
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
