package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Exposes the Clock module's already-loaded window drawable.  It does not add a view,
 * replace a resource, or provide an image: it only removes the exact, full-window
 * MIUIX fallback colour that is drawn above the Decor background on this ROM.
 */
final class ClockSurfaceAdapter {
    private static final String CLOCK_PACKAGE = "com.android.deskclock";
    private static final String MAIN_ACTIVITY = "com.android.deskclock.DeskClockTabActivity";

    private final XposedModule module;
    private final Map<Activity, SurfaceSession> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Activity> scheduled =
            Collections.newSetFromMap(new WeakHashMap<>());

    ClockSurfaceAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        hookAfter(Instrumentation.class, "callActivityOnCreate",
                new Class<?>[]{Activity.class, Bundle.class}, (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnResume",
                new Class<?>[]{Activity.class}, (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnStop",
                new Class<?>[]{Activity.class}, (receiver, args) -> restore((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnDestroy",
                new Class<?>[]{Activity.class}, (receiver, args) -> restore((Activity) args[0]));
        hookAfter(Activity.class, "onContentChanged", new Class<?>[]{}, (receiver, args) -> {
            if (receiver instanceof Activity) schedule((Activity) receiver);
        });
    }

    private boolean isTarget(Activity activity) {
        return activity != null && CLOCK_PACKAGE.equals(activity.getPackageName())
                && MAIN_ACTIVITY.equals(activity.getClass().getName())
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (!scheduled.add(activity)) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            applyAndRelease(activity, true, "no-decor");
            return;
        }
        decor.post(() -> applyAndRelease(activity, false, "post"));
        decor.postDelayed(() -> applyAndRelease(activity, false, "300ms"), 300L);
        decor.postDelayed(() -> applyAndRelease(activity, true, "900ms"), 900L);
    }

    private void applyAndRelease(Activity activity, boolean release, String pass) {
        if (isTarget(activity)) apply(activity, pass);
        if (release) {
            synchronized (scheduled) {
                scheduled.remove(activity);
            }
        }
    }

    private void apply(Activity activity, String pass) {
        try {
            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            List<View> overlays = findOverlays(activity);
            if (decor == null || overlays.isEmpty()) {
                log(pass + " overlay=absent decor=" + (decor == null ? "absent" : decor.getClass().getName()));
                return;
            }
            Drawable fallback = settingsFallbackDrawable(activity);
            Integer expected = colorOf(fallback);
            log(pass + " overlays=" + overlays.size() + " fallback=" + describe(fallback));
            for (int index = 0; index < overlays.size(); index++) {
                clearExactOverlay(activity, decor, overlays.get(index), fallback, expected, pass, index);
            }
            clearBottomNavigation(activity, decor, pass);
            decor.invalidate();
        } catch (Throwable error) {
            module.log(Log.ERROR, "ClockSurface", "Cannot clear Clock window fallback", error);
        }
    }

    private void clearExactOverlay(Activity activity, View decor, View overlay, Drawable fallback, Integer expected,
                                   String pass, int index) {
        Drawable current = overlay.getBackground();
        String prefix = pass + " overlay[" + index + "] id=" + entryName(activity, overlay)
                + " class=" + overlay.getClass().getName() + " frame=" + overlay.getWidth()
                + "x" + overlay.getHeight();
        boolean viewport = overlay.getWidth() >= decor.getWidth() * 0.90f
                && overlay.getHeight() >= decor.getHeight() * 0.90f;
        if (!viewport) {
            log(prefix + " retain=not-full-window bg=" + describe(current));
            return;
        }
        if (current == null) {
            log(prefix + " already-transparent");
            return;
        }
        if (!(current instanceof ColorDrawable)) {
            log(prefix + " retain=non-colour bg=" + describe(current));
            return;
        }
        int actual = ((ColorDrawable) current).getColor();
        if (expected == null && fallback != null && isOpaqueNeutral(actual)) {
            SurfaceSession session = sessionFor(activity);
            session.save(overlay, current);
            Drawable themed = duplicate(fallback, activity);
            overlay.setBackground(themed);
            log(prefix + " supplied-active-theme-drawable=" + describe(themed)
                    + " replacing-neutral=" + hex(actual));
            return;
        }
        if (expected == null || actual != expected) {
            log(prefix + " retain=not-settings-fallback actual=" + hex(actual)
                    + " expected=" + hex(expected));
            return;
        }
        SurfaceSession session = sessionFor(activity);
        session.save(overlay, current);
        overlay.setBackground(null);
        log(prefix + " cleared fallback=" + hex(actual));
    }

    private List<View> findOverlays(Activity activity) {
        List<View> overlays = new ArrayList<>();
        int id = activity.getResources().getIdentifier("action_bar_overlay_layout", "id", CLOCK_PACKAGE);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        findOverlaysRecursively(decor, id, overlays, 0);
        return overlays;
    }

    private void findOverlaysRecursively(View view, int knownId, List<View> overlays, int depth) {
        if (view == null || depth > 18) return;
        boolean named = knownId != 0 && view.getId() == knownId;
        boolean classMatch = view.getClass().getName().toLowerCase().contains("actionbaroverlaylayout");
        if (named || classMatch) overlays.add(view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findOverlaysRecursively(group.getChildAt(i), knownId, overlays, depth + 1);
            }
        }
    }

    private Drawable settingsFallbackDrawable(Activity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String name = "miuix_appcompat_settings_window_bg_" + (night ? "dark" : "light");
        try {
            int id = activity.getResources().getIdentifier(name, "drawable", CLOCK_PACKAGE);
            if (id == 0) {
                log("fallback-resource=" + name + " absent");
                return null;
            }
            Drawable drawable = activity.getResources().getDrawable(id, activity.getTheme());
            log("fallback-resource=" + name + " resolved=" + describe(drawable));
            return drawable;
        } catch (Throwable error) {
            module.log(Log.WARN, "ClockSurface", "Cannot read Clock fallback resource=" + name, error);
            return null;
        }
    }

    private void clearBottomNavigation(Activity activity, View decor, String pass) {
        int id = activity.getResources().getIdentifier("miuix_bottom_navigation_bar", "id", CLOCK_PACKAGE);
        View bar = id == 0 ? null : activity.findViewById(id);
        if (bar == null) {
            log(pass + " bottom-navigation=absent");
            return;
        }
        Drawable background = bar.getBackground();
        boolean dimensions = bar.getWidth() >= decor.getWidth() * 0.90f
                && bar.getHeight() > 0 && bar.getHeight() <= decor.getHeight() * 0.16f;
        boolean classMatch = bar.getClass().getName().toLowerCase().contains("bottomnavigation");
        String prefix = pass + " bottom-navigation id=" + entryName(activity, bar)
                + " class=" + bar.getClass().getName() + " frame=" + bar.getWidth() + "x" + bar.getHeight();
        if (!dimensions || !classMatch || background == null) {
            log(prefix + " retain dimensions=" + dimensions + " classMatch=" + classMatch
                    + " bg=" + describe(background));
            return;
        }
        SurfaceSession session = sessionFor(activity);
        session.save(bar, background);
        bar.setBackground(null);
        log(prefix + " cleared-default-layer=" + describe(background));
    }

    private SurfaceSession sessionFor(Activity activity) {
        SurfaceSession session = sessions.get(activity);
        if (session == null) {
            session = new SurfaceSession();
            sessions.put(activity, session);
        }
        return session;
    }

    private static Integer colorOf(Drawable drawable) {
        return drawable instanceof ColorDrawable ? ((ColorDrawable) drawable).getColor() : null;
    }

    private static boolean isOpaqueNeutral(int color) {
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
    }

    private static Drawable duplicate(Drawable drawable, Activity activity) {
        Drawable.ConstantState state = drawable == null ? null : drawable.getConstantState();
        return state == null ? drawable.mutate()
                : state.newDrawable(activity.getResources(), activity.getTheme()).mutate();
    }

    private void restore(Activity activity) {
        SurfaceSession session = sessions.remove(activity);
        if (session == null) return;
        for (Map.Entry<View, Drawable> entry : session.originalBackgrounds.entrySet()) {
            try {
                entry.getKey().setBackground(entry.getValue());
                log("restored id=" + entryName(activity, entry.getKey())
                        + " bg=" + describe(entry.getValue()));
            } catch (Throwable error) {
                module.log(Log.WARN, "ClockSurface", "Cannot restore Clock window fallback", error);
            }
        }
    }

    private String entryName(Activity activity, View view) {
        if (view == null || view.getId() == View.NO_ID || view.getId() == 0) return "";
        try {
            return activity.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String describe(Drawable drawable) {
        if (drawable == null) return "null";
        if (drawable instanceof ColorDrawable) return "ColorDrawable(" + hex(((ColorDrawable) drawable).getColor()) + ")";
        return drawable.getClass().getName() + "(" + drawable.getIntrinsicWidth() + "x"
                + drawable.getIntrinsicHeight() + ")";
    }

    private static String hex(Integer color) {
        return color == null ? "absent" : String.format("#%08X", color);
    }

    private void log(String message) {
        module.log(Log.INFO, "ClockSurface", message);
    }

    private void hookAfter(Class<?> type, String name, Class<?>[] params, After after) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(name, params);
            method.setAccessible(true);
            module.hook(method).intercept(callback -> {
                Object[] args = callback.getArgs().toArray(new Object[0]);
                Object result = callback.proceed(args);
                try {
                    after.after(callback.getThisObject(), args);
                } catch (Throwable error) {
                    module.log(Log.WARN, "ClockSurface", "Callback " + name + " failed", error);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "ClockSurface", "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After {
        void after(Object receiver, Object[] args) throws Throwable;
    }

    private static final class SurfaceSession {
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();

        void save(View overlay, Drawable originalBackground) {
            if (!originalBackgrounds.containsKey(overlay)) {
                originalBackgrounds.put(overlay, originalBackground);
            }
        }
    }
}
