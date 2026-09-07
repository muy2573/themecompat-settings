package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * The original Gallery home-page layer: use the currently resolved Gallery
 * drawable below the normal app hierarchy.  It intentionally has no dark-mode
 * fallback; a theme without a dark drawable keeps Gallery's native dark view.
 */
final class GalleryThemeLayerAdapter {
    private static final String PACKAGE_NAME = "com.miui.gallery";
    private static final String LIGHT_DRAWABLE = "miuix_appcompat_window_bg_light";
    private static final String DARK_DRAWABLE = "miuix_appcompat_window_bg_dark";

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new WeakHashMap<>());

    GalleryThemeLayerAdapter(XposedModule module) {
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
        hookAfter(Activity.class, "onConfigurationChanged", new Class<?>[]{Configuration.class},
                (receiver, args) -> { if (receiver instanceof Activity) schedule((Activity) receiver); });
    }

    private boolean isTarget(Activity activity) {
        return activity != null && PACKAGE_NAME.equals(activity.getPackageName())
                && activity.getClass().getName().endsWith(".HomePageActivity")
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (scheduled.containsKey(activity)) return;
            scheduled.put(activity, Boolean.TRUE);
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            release(activity);
            return;
        }
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
        try {
            View decorView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorView;
            Drawable source = resolveThemeDrawable(activity);
            if (source == null) {
                restore(activity);
                module.log(Log.INFO, "GalleryTheme", pass + " no non-colour Gallery theme drawable");
                return;
            }
            Session session = sessions.get(activity);
            if (session != null && session.decor != decor) {
                session.restore();
                sessions.remove(activity);
                session = null;
            }
            if (session == null) {
                session = new Session(decor, decor.getBackground());
                sessions.put(activity, session);
            }
            decor.setBackground(copyDrawable(source, activity));
            if (session.layer == null) {
                session.layer = new ThemeImageLayer(activity);
                session.layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                decor.addView(session.layer, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            session.layer.setImageDrawable(copyDrawable(source, activity));
            module.log(Log.INFO, "GalleryTheme", pass + " attached Gallery home theme layer");
        } catch (Throwable error) {
            module.log(Log.ERROR, "GalleryTheme", "Cannot attach Gallery theme layer", error);
        }
    }

    private Drawable resolveThemeDrawable(Activity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            int id = activity.getResources().getIdentifier(night ? DARK_DRAWABLE : LIGHT_DRAWABLE,
                    "drawable", PACKAGE_NAME);
            if (id == 0) return null;
            Drawable drawable = activity.getResources().getDrawable(id, activity.getTheme());
            return drawable instanceof ColorDrawable ? null : drawable;
        } catch (Throwable error) {
            module.log(Log.WARN, "GalleryTheme", "Cannot resolve Gallery theme drawable", error);
            return null;
        }
    }

    private static Drawable copyDrawable(Drawable source, Activity activity) {
        Drawable.ConstantState state = source.getConstantState();
        return state == null ? source.mutate()
                : state.newDrawable(activity.getResources(), activity.getTheme()).mutate();
    }

    private void restore(Activity activity) {
        Session session = sessions.remove(activity);
        if (session != null) session.restore();
    }

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try { callback.after(chain.getThisObject(), args); }
                catch (Throwable error) { module.log(Log.WARN, "GalleryTheme", "Lifecycle " + name, error); }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "GalleryTheme", "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After { void after(Object receiver, Object[] args) throws Throwable; }

    private static final class ThemeImageLayer extends ImageView {
        private final Matrix transform = new Matrix();

        ThemeImageLayer(Activity activity) {
            super(activity);
            setScaleType(ScaleType.MATRIX);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateTransform(width);
        }

        @Override
        public void setImageDrawable(Drawable drawable) {
            super.setImageDrawable(drawable);
            updateTransform(getWidth());
        }

        private void updateTransform(int width) {
            Drawable drawable = getDrawable();
            if (drawable == null || width <= 0 || drawable.getIntrinsicWidth() <= 0) return;
            float scale = width / (float) drawable.getIntrinsicWidth();
            transform.reset();
            transform.setScale(scale, scale);
            float scaledWidth = drawable.getIntrinsicWidth() * scale;
            transform.postTranslate((width - scaledWidth) * 0.5f, 0f);
            setImageMatrix(transform);
        }
    }

    private static final class Session {
        final ViewGroup decor;
        final Drawable originalBackground;
        ThemeImageLayer layer;

        Session(ViewGroup decor, Drawable originalBackground) {
            this.decor = decor;
            this.originalBackground = originalBackground;
        }

        void restore() {
            try {
                if (layer != null) {
                    ViewParent parent = layer.getParent();
                    if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(layer);
                }
                decor.setBackground(originalBackground);
            } catch (Throwable ignored) { }
            layer = null;
        }
    }
}
