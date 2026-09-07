package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Card fill for the AI call settings pages (MiAiSettingsActivity and
 * CallLogAndSettingsActivity).
 *
 * The canvas trace proved these pages' rows are miuix HyperCellLayout views
 * whose background is an opaque near-white NinePatchDrawable — unlike the
 * Mishare/Cloud pages, no ItemDecoration paints above them, so a plain
 * background alpha is enough.  The rows inflate asynchronously after the
 * posted passes, so — like the Messaging verification cards — the alpha is
 * enforced per draw instead of once per layout pass.  HyperCellLayout is
 * scoped tightly enough that only card rows carry it; enforced in both day
 * and night so list rebinds cannot restore the stock sheet.
 */
final class AiasstServiceCardAdapter {
    private static final String PACKAGE_NAME = "com.xiaomi.aiasst.service";
    private static final String LOG_TAG = "AiasstCards";
    private static final int CARD_ALPHA = 0x48;
    /** The dark reference value from the original design: ~24% opacity. */
    private static final int NIGHT_CARD_ALPHA = 0x3D;

    private final XposedModule module;
    private static final Map<Class<?>, java.lang.reflect.Method> ACCESSORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean logged;

    AiasstServiceCardAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        try {
            Method draw = View.class.getDeclaredMethod("draw", android.graphics.Canvas.class);
            draw.setAccessible(true);
            module.hook(draw).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (!(receiver instanceof View)) {
                    return chain.proceed(chain.getArgs().toArray(new Object[0]));
                }
                View view = (View) receiver;
                if (view.getBackground() != null) {
                    adjustBeforeDraw(view);
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
            module.log(Log.INFO, LOG_TAG, "per-draw alpha guard installed");
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot hook View.draw", error);
        }
        hookAfter("callActivityOnResume", new Class<?>[]{Activity.class}, true);
        hookAfter("callActivityOnCreate", new Class<?>[]{Activity.class, Bundle.class}, true);
    }

    private void hookAfter(String name, Class<?>[] parameters, final boolean dump) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (dump && args.length > 0 && args[0] instanceof Activity) {
                    scheduleDump((Activity) args[0]);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot hook " + name, error);
        }
    }

    private void scheduleDump(final Activity activity) {
        if (!isTarget(activity)) return;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(() -> {
            if (isTarget(activity)) dumpBackgrounds(activity, decor, 0);
        }, 1200L);
    }

    private void dumpBackgrounds(Activity activity, View view, int depth) {
        if (view == null || depth > 30) return;
        Drawable background = view.getBackground();
        if (background != null) {
            String color = "";
            if (background instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList colors =
                        ((android.graphics.drawable.GradientDrawable) background).getColor();
                color = colors == null ? "colors=null"
                        : String.format("#%08X", colors.getDefaultColor());
            }
            module.log(Log.INFO, LOG_TAG, "bg depth=" + depth + " class="
                    + view.getClass().getName() + " id=" + viewId(view) + " frame="
                    + view.getWidth() + 'x' + view.getHeight() + " bg="
                    + background.getClass().getName() + "(a=" + background.getAlpha()
                    + (color.isEmpty() ? "" : " " + color) + ')');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                dumpBackgrounds(activity, group.getChildAt(index), depth + 1);
            }
        }
    }

    private static String viewId(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return "none";
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private void adjustBeforeDraw(View card) {
        Context context = card.getContext();
        if (context == null || !PACKAGE_NAME.equals(context.getPackageName())) return;
        boolean night = isNight(context);
        int target = night ? CardAlpha.dark() : CardAlpha.light();
        Drawable background = unwrap(card.getBackground());
        if (background instanceof NinePatchDrawable) {
            enforceAlpha(card, background, target);
        } else if (background instanceof android.graphics.drawable.GradientDrawable
                && isCardFill(card, background, night)) {
            enforceAlpha(card, background, target);
        }
    }

    /**
     * Peel wrapper drawables before the white-card checks.  State list rows
     * delegate to the current state's drawable (getCurrent), wrapped insets
     * expose getDrawable, and single-layer LayerDrawables expose layer 0.
     */
    private static Drawable unwrap(Drawable drawable) {
        Drawable current = drawable;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Drawable inner = null;
            try {
                if (current instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable layers =
                            (android.graphics.drawable.LayerDrawable) current;
                    inner = layers.getNumberOfLayers() == 1 ? layers.getDrawable(0) : null;
                } else {
                    java.lang.reflect.Method accessor = ACCESSORS.get(current.getClass());
                    if (accessor == null) {
                        for (String candidate : new String[]{"getCurrent", "getDrawable"}) {
                            try {
                                accessor = current.getClass().getMethod(candidate);
                                break;
                            } catch (NoSuchMethodException ignored) {
                                // Try the next known accessor name.
                            }
                        }
                        if (accessor != null) ACCESSORS.put(current.getClass(), accessor);
                    }
                    if (accessor != null) {
                        accessor.setAccessible(true);
                        inner = (Drawable) accessor.invoke(current);
                    }
                }
            } catch (Throwable error) {
                inner = null;
            }
            if (inner == null || inner == current) return current;
            current = inner;
        }
        return current;
    }

    /**
     * Plain LinearLayout cards on these pages carry an opaque white
     * GradientDrawable instead of the row NinePatch (voice picker, feature
     * tiles, the "more voices" row).  Width keeps small white controls —
     * such as the audition pill — untouched.
     */
    /**
     * Light mode only frosts the white sheet so tinted accents stay
     * untouched.  Night mode frosts every opaque card fill — the dark sheet
     * is exactly what hides the dark artwork the owner wants visible.
     */
    private static boolean isCardFill(View view, Drawable background, boolean night) {
        if (!(background instanceof android.graphics.drawable.GradientDrawable)) return false;
        if (view.getWidth() < 300) return false;
        android.content.res.ColorStateList colors =
                ((android.graphics.drawable.GradientDrawable) background).getColor();
        if (colors == null) return false;
        int color = colors.getDefaultColor();
        if (android.graphics.Color.alpha(color) != 255) return false;
        if (night) return true;
        int red = android.graphics.Color.red(color);
        int green = android.graphics.Color.green(color);
        int blue = android.graphics.Color.blue(color);
        int min = Math.min(red, Math.min(green, blue));
        int max = Math.max(red, Math.max(green, blue));
        return min >= 224 && max - min <= 24;
    }

    private void enforceAlpha(View card, Drawable drawable, int target) {
        if (drawable.getAlpha() == target) return;
        try {
            drawable.mutate();
            drawable.setAlpha(target);
            card.invalidate();
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot adjust card alpha", error);
            return;
        }
        if (!logged) {
            logged = true;
            module.log(Log.INFO, LOG_TAG, "per-draw card alpha enforced target=0x"
                    + Integer.toHexString(target));
        }
    }

    private boolean isTarget(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !PACKAGE_NAME.equals(activity.getPackageName())) return false;
        String name = activity.getClass().getName();
        return name.equals("com.xiaomi.aiasst.service.aicall.settings.main.MiAiSettingsActivity")
                || name.equals("com.xiaomi.aiasst.service.aicall.settings.main.CallLogAndSettingsActivity");
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
