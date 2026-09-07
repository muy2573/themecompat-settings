package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * First compatibility pass for HyperOS Messaging.  It follows the verified
 * File Explorer rule: preserve the active theme's resolved window drawable and
 * remove only broad, neutral fallback surfaces placed above it by MIUIX.
 */
final class MessagingSurfaceAdapter {
    private static final String DEFAULT_PACKAGE = "com.android.mms";
    private static final String CONTACTS_PACKAGE = "com.android.contacts";

    private final XposedModule module;
    private final String targetPackage;
    private final String logTag;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new WeakHashMap<>());

    MessagingSurfaceAdapter(XposedModule module) {
        this(module, DEFAULT_PACKAGE, "MessagingSurface");
    }

    MessagingSurfaceAdapter(XposedModule module, String targetPackage, String logTag) {
        this.module = module;
        this.targetPackage = targetPackage;
        this.logTag = logTag;
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
        return activity != null && targetPackage.equals(activity.getPackageName())
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (scheduled.containsKey(activity)) return;
            scheduled.put(activity, Boolean.TRUE);
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) { release(activity); return; }
        ensureLiveRescan(activity, decor);
        decor.post(() -> applyAndRelease(activity, false, "post"));
        decor.postDelayed(() -> applyAndRelease(activity, false, "350ms"), 350L);
        decor.postDelayed(() -> applyAndRelease(activity, true, "1000ms"), 1000L);
    }

    private void applyAndRelease(Activity activity, boolean release, String pass) {
        if (isTarget(activity)) apply(activity, pass);
        if (release) release(activity);
    }

    private void release(Activity activity) {
        synchronized (scheduled) { scheduled.remove(activity); }
    }

    private void apply(Activity activity, String pass) {
        Session session = sessionFor(activity);
        if (session.applying) return;
        session.applying = true;
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            Drawable decorBackground = decor.getBackground();
            boolean hasThemeDrawable = decorBackground != null && !(decorBackground instanceof ColorDrawable);
            if (!"layout".equals(pass)) {
                log(pass + " activity=" + activity.getClass().getName() + " decor="
                        + describe(decorBackground) + " themedDecor=" + hasThemeDrawable);
            }
            if (!hasThemeDrawable) return;
            clearNeutralSurfaces(activity, decor, decor, pass, 0);
            if (CONTACTS_PACKAGE.equals(targetPackage)) {
                clearContactsNestedOverlay(activity, decor, decor, pass, 0);
            }
            clearBottomNavigation(activity, decor, pass);
            decor.invalidate();
        } catch (Throwable error) {
            module.log(Log.ERROR, logTag, "Cannot apply package surface cleanup", error);
        } finally {
            session.applying = false;
        }
    }

    private void clearNeutralSurfaces(Activity activity, View decor, View view, String pass, int depth) {
        if (view == null || depth > 24) return;
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable && hasNavigationAncestor(view)
                && isBroadSurface(decor, view) && isOpaqueNeutral(((ColorDrawable) background).getColor())) {
            Session session = sessionFor(activity);
            session.save(view, background);
            view.setBackground(null);
            log(pass + " cleared-neutral-surface id=" + entryName(activity, view)
                    + " class=" + view.getClass().getName() + " frame=" + view.getWidth() + 'x'
                    + view.getHeight() + " color=" + hex(((ColorDrawable) background).getColor()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                clearNeutralSurfaces(activity, decor, group.getChildAt(index), pass, depth + 1);
            }
        }
    }

    private void clearBottomNavigation(Activity activity, View decor, String pass) {
        int id = activity.getResources().getIdentifier("miuix_bottom_navigation_bar", "id", targetPackage);
        if (id == 0) return;
        View bar = decor.findViewById(id);
        if (bar == null || !hasNavigationAncestor(bar) || bar.getBackground() == null) return;
        Session session = sessionFor(activity);
        Drawable background = bar.getBackground();
        session.save(bar, background);
        bar.setBackground(null);
        log(pass + " cleared-bottom-navigation class=" + bar.getClass().getName()
                + " frame=" + bar.getWidth() + 'x' + bar.getHeight() + " bg=" + describe(background));
    }

    /**
     * Contacts detail and settings pages do not use MiuixNavigationLayout.
     * Instead an outer ActionBarOverlayLayout owns a second full-screen overlay
     * that is reset to #F7F7F7 / black.  The parent-overlay test distinguishes
     * that fallback from the outer host that carries the resolved theme image.
     */
    private void clearContactsNestedOverlay(Activity activity, View decor, View view,
                                            String pass, int depth) {
        if (view == null || depth > 20) return;
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable && isActionBarOverlay(activity, view)
                && hasActionBarOverlayAncestor(activity, view) && isBroadSurface(decor, view)
                && isOpaqueNeutral(((ColorDrawable) background).getColor())) {
            Session session = sessionFor(activity);
            session.save(view, background);
            view.setBackground(null);
            log(pass + " cleared-contacts-nested-overlay id=" + entryName(activity, view)
                    + " class=" + view.getClass().getName() + " frame=" + view.getWidth() + 'x'
                    + view.getHeight() + " color=" + hex(((ColorDrawable) background).getColor()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                clearContactsNestedOverlay(activity, decor, group.getChildAt(index), pass, depth + 1);
            }
        }
    }

    private boolean isActionBarOverlay(Activity activity, View view) {
        String className = view.getClass().getName().toLowerCase();
        return className.contains("actionbaroverlaylayout")
                || "action_bar_overlay_layout".equals(entryName(activity, view));
    }

    private boolean hasActionBarOverlayAncestor(Activity activity, View view) {
        ViewParent current = view == null ? null : view.getParent();
        for (int depth = 0; current instanceof View && depth < 12; depth++) {
            if (isActionBarOverlay(activity, (View) current)) return true;
            current = current.getParent();
        }
        return false;
    }

    private static boolean isBroadSurface(View decor, View view) {
        return view.getWidth() >= decor.getWidth() * 0.88f
                && view.getHeight() >= decor.getHeight() * 0.72f;
    }

    private static boolean hasNavigationAncestor(View view) {
        ViewParent current = view == null ? null : view.getParent();
        for (int depth = 0; current instanceof View && depth < 20; depth++) {
            View ancestor = (View) current;
            String name = ancestor.getClass().getName();
            if (name.contains("MiuixNavigationLayout") || name.contains("NavigationLayout")) return true;
            current = ancestor.getParent();
        }
        return false;
    }

    private void ensureLiveRescan(Activity activity, View decor) {
        Session session = sessionFor(activity);
        if (session.decor == decor && session.layoutListener != null) return;
        removeLiveRescan(session);
        session.decor = decor;
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            if (!isTarget(activity) || session.applying || session.rescanQueued) return;
            session.rescanQueued = true;
            decor.post(() -> {
                session.rescanQueued = false;
                if (isTarget(activity) && session.decor == decor) apply(activity, "layout");
            });
        };
        session.layoutListener = listener;
        try {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            log("installed live hierarchy listener activity=" + activity.getClass().getName());
        } catch (Throwable error) {
            session.layoutListener = null;
            module.log(Log.WARN, logTag, "Cannot observe package hierarchy", error);
        }
    }

    private void restore(Activity activity) {
        Session session = sessions.remove(activity);
        if (session == null) return;
        removeLiveRescan(session);
        for (Map.Entry<View, Drawable> entry : session.originalBackgrounds.entrySet()) {
            try { entry.getKey().setBackground(entry.getValue()); }
            catch (Throwable error) {
                module.log(Log.WARN, logTag, "Cannot restore package surface", error);
            }
        }
    }

    private void removeLiveRescan(Session session) {
        if (session.decor == null || session.layoutListener == null) return;
        try {
            ViewTreeObserver observer = session.decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(session.layoutListener);
        } catch (Throwable ignored) {
            // The window may be detached already.
        }
        session.decor = null;
        session.layoutListener = null;
        session.rescanQueued = false;
    }

    private Session sessionFor(Activity activity) {
        Session session = sessions.get(activity);
        if (session == null) {
            session = new Session();
            sessions.put(activity, session);
        }
        return session;
    }

    private String entryName(Activity activity, View view) {
        if (view.getId() == 0 || view.getId() == View.NO_ID) return "";
        try { return activity.getResources().getResourceEntryName(view.getId()); }
        catch (Throwable ignored) { return ""; }
    }

    private static boolean isOpaqueNeutral(int color) {
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
    }

    private static String describe(Drawable drawable) {
        if (drawable == null) return "null";
        if (drawable instanceof ColorDrawable) return "ColorDrawable(" + hex(((ColorDrawable) drawable).getColor()) + ')';
        return drawable.getClass().getName() + '(' + drawable.getIntrinsicWidth() + 'x'
                + drawable.getIntrinsicHeight() + ')';
    }

    private static String hex(int color) { return String.format("#%08X", color); }
    private void log(String text) { module.log(Log.INFO, logTag, text); }

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try { callback.after(chain.getThisObject(), args); }
                catch (Throwable error) { module.log(Log.WARN, logTag, "Lifecycle " + name, error); }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, logTag, "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After { void after(Object receiver, Object[] args) throws Throwable; }

    private static final class Session {
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();
        View decor;
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean rescanQueued;
        boolean applying;

        void save(View view, Drawable background) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds.put(view, background);
        }
    }
}
