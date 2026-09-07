package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
 * Bottom "Screen translation"/"Subtitle translation" tiles on the Translate
 * home page (com.xiaomi.aiasst.vision MainActivity).
 *
 * The themed wallpaper already shows through the rest of the page.  The two
 * tiles inside btn_group1 are com.miui.support.cardview.CardView instances
 * whose fill is drawn internally from cardBackgroundColor — it never appears
 * through View.getBackground(), which is why a background walk dimmed
 * nothing.  Following the verified standalone-app card look: reduce each
 * card's own colour to alpha 0x48, keeping its RGB, so the tiles become
 * frosted panels in both day and night (whatever RGB the app resolves per
 * mode), re-applied on resume and day/night changes and idempotent per
 * draw pass.  Only the btn_group1 subtree is touched.
 */
final class AiasstTranslateCardAdapter {
    private static final String PACKAGE_NAME = "com.xiaomi.aiasst.vision";
    private static final String ACTIVITY = "com.xiaomi.aiasst.vision.ui.MainActivity";
    private static final String LOG_TAG = "TranslateCards";
    private static final int CARD_ALPHA = 0x48;

    private final XposedModule module;
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Activity, Map<Drawable, Integer>> originalAlphas =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean logged;

    AiasstTranslateCardAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        hookLifecycle("callActivityOnCreate", new Class<?>[]{Activity.class, Bundle.class}, false);
        hookLifecycle("callActivityOnResume", new Class<?>[]{Activity.class}, false);
        hookConfigurationChanges();
        module.log(Log.INFO, LOG_TAG, "installed");
    }

    private void hookLifecycle(String name, Class<?>[] parameters, boolean noop) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (args.length > 0 && args[0] instanceof Activity) schedule((Activity) args[0]);
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot hook " + name, error);
        }
    }

    private void hookConfigurationChanges() {
        try {
            Method method = Activity.class.getDeclaredMethod("onConfigurationChanged",
                    Configuration.class);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                Object receiver = chain.getThisObject();
                if (receiver instanceof Activity) schedule((Activity) receiver);
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot hook configuration", error);
        }
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (scheduled.put(activity, Boolean.TRUE) != null) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            release(activity);
            return;
        }
        decor.post(() -> applyAndRelease(activity, false));
        decor.postDelayed(() -> applyAndRelease(activity, false), 300L);
        decor.postDelayed(() -> applyAndRelease(activity, true), 900L);
    }

    private void applyAndRelease(Activity activity, boolean release) {
        try {
            if (isTarget(activity)) apply(activity);
        } finally {
            if (release) release(activity);
        }
    }

    private void release(Activity activity) {
        synchronized (scheduled) { scheduled.remove(activity); }
    }

    private void apply(Activity activity) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        int groupId = activity.getResources().getIdentifier("btn_group1", "id", PACKAGE_NAME);
        View group = groupId == 0 ? null : decor.findViewById(groupId);
        if (!(group instanceof ViewGroup)) {
            module.log(Log.WARN, LOG_TAG, "btn_group1 not found");
            return;
        }
        boolean night = isNight(activity);
        Map<Drawable, Integer> originals = originalsFor(activity);
        int[] changed = {0};
        int[] restored = {0};
        dimCardViewColors(group, changed);
        dimNearWhiteBackgrounds(group, night, originals, changed, restored);
        if (changed[0] > 0 || restored[0] > 0) {
            module.log(Log.INFO, LOG_TAG, "applied mode=" + (night ? "dark" : "light")
                    + " dimmed=" + changed[0] + " restored=" + restored[0]);
        }
    }

    /**
     * CardView fills live in an internal drawable fed by cardBackgroundColor
     * (androidx API inherited by the miui copy), so the alpha is enforced on
     * the colour itself.  Mode-agnostic: whatever RGB the theme resolves,
     * only its alpha is reduced, and an already-dimmed colour is a no-op.
     */
    private void dimCardViewColors(View view, int[] changed) {
        if (isVendorCardView(view)) {
            try {
                Method getter = view.getClass().getMethod("getCardBackgroundColor");
                getter.setAccessible(true);
                android.content.res.ColorStateList colors =
                        (android.content.res.ColorStateList) getter.invoke(view);
                int current = colors == null ? 0 : colors.getDefaultColor();
                if (current != 0 && ((current >>> 24) & 0xFF) != CardAlpha.light()) {
                    int target = (CardAlpha.light() << 24) | (current & 0x00FFFFFF);
                    Method setter = view.getClass().getMethod("setCardBackgroundColor", int.class);
                    setter.setAccessible(true);
                    setter.invoke(view, target);
                    changed[0]++;
                    if (!logged) {
                        logged = true;
                        module.log(Log.INFO, LOG_TAG, "card colour dimmed to alpha 0x"
                                + Integer.toHexString(CardAlpha.light()));
                    }
                }
            } catch (Throwable error) {
                module.log(Log.WARN, LOG_TAG, "cannot adjust card colour", error);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                dimCardViewColors(group.getChildAt(index), changed);
            }
        }
    }

    private static boolean isVendorCardView(View view) {
        return view.getClass().getName().contains("cardview.CardView");
    }

    private void dimNearWhiteBackgrounds(View view, boolean night,
                                         Map<Drawable, Integer> originals,
                                         int[] changed, int[] restored) {
        Drawable background = view.getBackground();
        if (isOpaqueNearWhite(background)) {
            applyAlpha(background, night, originals, changed, restored);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                dimNearWhiteBackgrounds(group.getChildAt(index), night, originals,
                        changed, restored);
            }
        }
    }

    private void applyAlpha(Drawable drawable, boolean night,
                            Map<Drawable, Integer> originals, int[] changed, int[] restored) {
        Integer original = originals.get(drawable);
        if (original == null) {
            original = drawable.getAlpha();
            originals.put(drawable, original);
        }
        int target = night ? original : CardAlpha.light();
        if (drawable.getAlpha() == target) return;
        try {
            drawable.mutate();
            drawable.setAlpha(target);
            if (night) restored[0]++;
            else changed[0]++;
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot adjust "
                    + drawable.getClass().getName(), error);
        }
    }

    private static boolean isOpaqueNearWhite(Drawable drawable) {
        if (drawable == null || drawable.getAlpha() != 255) return false;
        int color;
        if (drawable instanceof ColorDrawable) {
            color = ((ColorDrawable) drawable).getColor();
        } else if (drawable instanceof GradientDrawable) {
            android.content.res.ColorStateList colors = ((GradientDrawable) drawable).getColor();
            if (colors == null) return false;
            color = colors.getDefaultColor();
        } else {
            // Nine-patch / bitmap tile cards may arrive later if the vendor
            // restyles these tiles; evidence first, no blind dimming.
            return false;
        }
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        if (red < 226 || green < 226 || blue < 226) return false;
        int min = Math.min(red, Math.min(green, blue));
        int max = Math.max(red, Math.max(green, blue));
        return max - min <= 24;
    }

    private Map<Drawable, Integer> originalsFor(Activity activity) {
        Map<Drawable, Integer> originals = originalAlphas.get(activity);
        if (originals == null) {
            originals = new IdentityHashMap<>();
            originalAlphas.put(activity, originals);
        }
        return originals;
    }

    private boolean isTarget(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && PACKAGE_NAME.equals(activity.getPackageName())
                && ACTIVITY.equals(activity.getClass().getName());
    }

    private static boolean isNight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
