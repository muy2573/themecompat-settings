package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Exact card-surface adjustment for the final three standalone pages.
 *
 * The first direct test proved the CardStateDrawable foreground in Mi Share,
 * Cloud and Account is an interaction-only layer: alpha changes have no
 * visible effect.  It is intentionally not touched any more.  The one
 * confirmed source is Xiaomi Account's two ServiceSmallCardView backgrounds.
 * No generic View background is cleared and Account's large, intentionally
 * distinct service card is deliberately excluded.
 */
final class BusinessCardSurfaceAdapter {
    private static final int LIGHT_ALPHA = 0x48;
    private static final Set<String> TARGETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.miui.mishare.connectivity",
            "com.miui.cloudservice",
            "com.xiaomi.account"
    )));

    private final XposedModule module;
    private final String targetPackage;
    private final Map<Drawable, Integer> originalAlphas =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<Activity> scheduled = Collections.newSetFromMap(new IdentityHashMap<>());

    BusinessCardSurfaceAdapter(XposedModule module, String targetPackage) {
        this.module = module;
        this.targetPackage = targetPackage;
    }

    static boolean supports(String packageName) {
        return TARGETS.contains(packageName);
    }

    void install() {
        hookAfter("callActivityOnCreate", new Class<?>[]{Activity.class, Bundle.class});
        hookAfter("callActivityOnResume", new Class<?>[]{Activity.class});
        hookConfigurationChanges();
        module.log(Log.INFO, "BusinessCards", "installed package=" + targetPackage);
    }

    private void hookAfter(String name, Class<?>[] parameters) {
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
            module.log(Log.WARN, "BusinessCards", "cannot hook " + name, error);
        }
    }

    private void hookConfigurationChanges() {
        try {
            Method method = Activity.class.getDeclaredMethod("onConfigurationChanged", Configuration.class);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                Object receiver = chain.getThisObject();
                if (receiver instanceof Activity) schedule((Activity) receiver);
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, "BusinessCards", "cannot hook configuration", error);
        }
    }

    private void schedule(Activity activity) {
        if (!isTargetActivity(activity)) return;
        synchronized (scheduled) {
            if (!scheduled.add(activity)) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            applyAndRelease(activity, true);
            return;
        }
        // These cards are attached after the normal RecyclerView hierarchy;
        // follow the already verified 0/300/900ms cadence of the page adapter.
        decor.post(() -> applyAndRelease(activity, false));
        decor.postDelayed(() -> applyAndRelease(activity, false), 300L);
        decor.postDelayed(() -> applyAndRelease(activity, true), 900L);
    }

    private void applyAndRelease(Activity activity, boolean release) {
        try {
            if (isTargetActivity(activity)) apply(activity);
        } finally {
            if (release) {
                synchronized (scheduled) { scheduled.remove(activity); }
            }
        }
    }

    private void apply(Activity activity) {
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        int[] changed = {0};
        int[] restored = {0};
        walk(activity, root, root, changed, restored);
        if (changed[0] > 0 || restored[0] > 0) {
            module.log(Log.INFO, "BusinessCards", "applied package=" + targetPackage
                    + " mode=" + (isNight(activity) ? "dark" : "light")
                    + " foregrounds=" + changed[0] + " restored=" + restored[0]);
        }
    }

    private void walk(Activity activity, View root, View view, int[] changed, int[] restored) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        boolean night = isNight(activity);
        if ("com.xiaomi.account".equals(targetPackage) && isSmallAccountServiceCard(view)) {
            applyWhiteDescendantBackgrounds(view, night, changed, restored);
            // Child drawables have been examined separately. Continue walking
            // to reach later preference rows outside this service-card view.
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                walk(activity, root, group.getChildAt(index), changed, restored);
            }
        }
    }

    private static boolean isSmallAccountServiceCard(View view) {
        return view.getClass().getName().endsWith("ServiceSmallCardView");
    }

    private void applyWhiteDescendantBackgrounds(View view, boolean night, int[] changed, int[] restored) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        Drawable background = view.getBackground();
        if (isOpaqueNeutralGradient(background)) applyAlpha(background, night, changed, restored);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyWhiteDescendantBackgrounds(group.getChildAt(index), night, changed, restored);
            }
        }
    }

    private void applyAlpha(Drawable drawable, boolean night, int[] changed, int[] restored) {
        if (drawable == null) return;
        Integer original = originalAlphas.get(drawable);
        if (original == null) {
            original = drawable.getAlpha();
            originalAlphas.put(drawable, original);
        }
        int target = night ? original : CardAlpha.light();
        if (drawable.getAlpha() == target) return;
        try {
            drawable.mutate();
            drawable.setAlpha(target);
            if (night) restored[0]++;
            else changed[0]++;
        } catch (Throwable error) {
            module.log(Log.WARN, "BusinessCards", "cannot adjust " + drawable.getClass().getName(), error);
        }
    }

    private boolean isTargetActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !targetPackage.equals(activity.getPackageName())) return false;
        String name = activity.getClass().getName();
        if ("com.miui.mishare.connectivity".equals(targetPackage)) {
            return name.equals("com.miui.mishare.activity.MiShareSettingsActivity");
        }
        if ("com.miui.cloudservice".equals(targetPackage)) {
            return name.equals("com.miui.cloudservice.ui.MiCloudMainActivity");
        }
        return name.equals("com.xiaomi.account.ui.AccountSettingsActivity");
    }

    private static boolean isOpaqueNeutralGradient(Drawable drawable) {
        if (!(drawable instanceof GradientDrawable) || drawable.getAlpha() != 255) return false;
        android.content.res.ColorStateList colors = ((GradientDrawable) drawable).getColor();
        if (colors == null) return false;
        int color = colors.getDefaultColor();
        if (Color.alpha(color) != 255) return false;
        int min = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
        int max = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        return min >= 224 && max - min <= 24;
    }

    private static boolean isNight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

}
