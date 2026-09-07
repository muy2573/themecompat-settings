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
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * K70 NewInstallerPrepareActivity already resolves its themed window bitmap,
 * but two known page hosts repaint opaque neutral colours above it.  The
 * bottom action area is intentionally untouched because it has a separate
 * NinePatch treatment in the source theme.
 */
final class PackageInstallerSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.miui.packageinstaller";
    private static final String CONFIRMATION_ACTIVITY =
            "com.miui.packageInstaller.NewInstallerPrepareActivity";
    private static final String ROOT_ID = "root";
    private static final String FRAGMENT_CONTAINER_ID = "fragment_container";

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new WeakHashMap<>());

    PackageInstallerSurfaceAdapter(XposedModule module) {
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
        return activity != null && PACKAGE_NAME.equals(activity.getPackageName())
                && CONFIRMATION_ACTIVITY.equals(activity.getClass().getName())
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (scheduled.containsKey(activity)) return;
            scheduled.put(activity, Boolean.TRUE);
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            release(activity);
            return;
        }
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
            if (decor == null || decor.getBackground() instanceof ColorDrawable) {
                if (!"layout".equals(pass)) {
                    module.log(Log.INFO, "InstallerSurface", pass
                            + " no themed window drawable; confirmation page unchanged");
                }
                return;
            }
            int rootId = activity.getResources().getIdentifier(ROOT_ID, "id", PACKAGE_NAME);
            int fragmentId = activity.getResources().getIdentifier(FRAGMENT_CONTAINER_ID, "id", PACKAGE_NAME);
            clearExactHosts(activity, decor, rootId, ROOT_ID, pass);
            clearExactHosts(activity, decor, fragmentId, FRAGMENT_CONTAINER_ID, pass);
            decor.invalidate();
        } catch (Throwable error) {
            module.log(Log.ERROR, "InstallerSurface", "Cannot clear Package Installer confirmation hosts", error);
        } finally {
            session.applying = false;
        }
    }

    private void clearExactHosts(Activity activity, View decor, int id, String label, String pass) {
        if (id == 0) return;
        List<View> matches = new ArrayList<>();
        collectById(decor, id, matches, 0);
        for (View view : matches) {
            Drawable background = view.getBackground();
            if (!(background instanceof ColorDrawable) || !isBroadPageHost(decor, view)
                    || !isOpaqueNeutral(((ColorDrawable) background).getColor())) continue;
            Session session = sessionFor(activity);
            session.save(view, background);
            view.setBackground(null);
            if (!"layout".equals(pass)) {
                module.log(Log.INFO, "InstallerSurface", pass + " cleared confirmed host=" + label
                        + " class=" + view.getClass().getName() + " frame="
                        + view.getWidth() + 'x' + view.getHeight());
            }
        }
    }

    private static boolean isBroadPageHost(View decor, View view) {
        return view.getWidth() >= decor.getWidth() * 0.90f
                && view.getHeight() >= decor.getHeight() * 0.65f;
    }

    private static boolean isOpaqueNeutral(int color) {
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
    }

    private void collectById(View view, int id, List<View> out, int depth) {
        if (view == null || depth > 20) return;
        if (view.getId() == id) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectById(group.getChildAt(index), id, out, depth + 1);
            }
        }
    }

    private void ensureLiveRescan(Activity activity, View decor) {
        Session session = sessionFor(activity);
        if (session.decor == decor && session.layoutListener != null) return;
        removeLiveRescan(session);
        session.decor = decor;
        session.layoutListener = () -> {
            if (!isTarget(activity) || session.applying || session.rescanQueued) return;
            session.rescanQueued = true;
            decor.post(() -> {
                session.rescanQueued = false;
                if (isTarget(activity) && session.decor == decor) apply(activity, "layout");
            });
        };
        try {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(session.layoutListener);
        } catch (Throwable error) {
            session.layoutListener = null;
            module.log(Log.WARN, "InstallerSurface", "Cannot observe Package Installer hierarchy", error);
        }
    }

    private Session sessionFor(Activity activity) {
        Session session = sessions.get(activity);
        if (session == null) {
            session = new Session();
            sessions.put(activity, session);
        }
        return session;
    }

    private void restore(Activity activity) {
        Session session = sessions.remove(activity);
        if (session == null) return;
        removeLiveRescan(session);
        for (Map.Entry<View, Drawable> entry : session.originalBackgrounds.entrySet()) {
            try { entry.getKey().setBackground(entry.getValue()); }
            catch (Throwable ignored) { }
        }
    }

    private void removeLiveRescan(Session session) {
        if (session.decor == null || session.layoutListener == null) return;
        try {
            ViewTreeObserver observer = session.decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(session.layoutListener);
        } catch (Throwable ignored) { }
        session.decor = null;
        session.layoutListener = null;
        session.rescanQueued = false;
    }

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try { callback.after(chain.getThisObject(), args); }
                catch (Throwable error) { module.log(Log.WARN, "InstallerSurface", "Lifecycle " + name, error); }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "InstallerSurface", "Cannot hook " + type.getName() + '#' + name, error);
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
