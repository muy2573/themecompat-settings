package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/** Exact background adjustment for the two SIM cards in MobileNetworkSettings. */
final class MobileNetworkSimCardAdapter {
    private static final String PACKAGE_NAME = "com.android.phone";
    private static final String ACTIVITY_NAME =
            "com.android.phone.settings.MobileNetworkSettings";
    private static final int LIGHT_CARD_ALPHA = 0x48;
    private static final Set<String> TARGET_IDS = Collections.unmodifiableSet(new HashSet<>(
            java.util.Arrays.asList("sim_info", "extra_info")));

    private final XposedModule module;
    private final Map<Drawable, Integer> nativeAlphas =
            Collections.synchronizedMap(new WeakHashMap<>());

    MobileNetworkSimCardAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        hookLifecycle("callActivityOnCreate", new Class<?>[]{Activity.class, Bundle.class});
        hookLifecycle("callActivityOnResume", new Class<?>[]{Activity.class});
        hookConfigurationChanged();
    }

    private void hookLifecycle(String name, Class<?>[] parameters) {
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
            module.log(Log.WARN, "MobileNetworkSIM", "Cannot hook " + name, error);
        }
    }

    private void hookConfigurationChanged() {
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
            module.log(Log.WARN, "MobileNetworkSIM", "Cannot hook configuration", error);
        }
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.post(() -> apply(activity, decor));
        decor.postDelayed(() -> apply(activity, decor), 700L);
    }

    private void apply(Activity activity, View root) {
        if (!isTarget(activity) || root == null) return;
        int adjusted = applyToCards(activity, root);
        if (adjusted > 0) module.log(Log.INFO, "MobileNetworkSIM",
                "SIM card backgrounds mode=" + (isNight(activity) ? "dark" : "light")
                        + " count=" + adjusted + " alpha="
                        + (isNight(activity) ? "native" : "0x48"));
    }

    private int applyToCards(Activity activity, View view) {
        if (view == null) return 0;
        int adjusted = 0;
        if (TARGET_IDS.contains(id(view)) && isSmoothContainer(view.getBackground())) {
            Drawable background = view.getBackground();
            Integer nativeAlpha = nativeAlphas.get(background);
            if (nativeAlpha == null) {
                nativeAlpha = background.getAlpha();
                nativeAlphas.put(background, nativeAlpha);
            }
            int wanted = isNight(activity) ? nativeAlpha : CardAlpha.light();
            if (background.getAlpha() != wanted) background.setAlpha(wanted);
            adjusted++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                adjusted += applyToCards(activity, group.getChildAt(index));
            }
        }
        return adjusted;
    }

    private static boolean isTarget(Activity activity) {
        return activity != null && PACKAGE_NAME.equals(activity.getPackageName())
                && ACTIVITY_NAME.equals(activity.getClass().getName())
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static boolean isNight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean isSmoothContainer(Drawable drawable) {
        return drawable != null && "miuix.smooth.SmoothContainerDrawable"
                .equals(drawable.getClass().getName());
    }

    private static String id(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Throwable ignored) { return ""; }
    }
}
