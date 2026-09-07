package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.graphics.Color;
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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Exact host cleanup for SoundRecorderSettings.  Unlike the recorder's normal
 * screen, this preference page is not inside MiuixNavigationLayout: it creates
 * its own full-screen FrameLayout/SpringBackLayout/RecyclerView host, which
 * remains opaque above the already-resolved theme window image.
 */
final class RecorderSettingsSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.android.soundrecorder";
    private static final String ACTIVITY_SUFFIX = ".SoundRecorderSettings";
    private static final String LIGHT_DRAWABLE = "miuix_appcompat_settings_window_bg_light";
    private static final String DARK_DRAWABLE = "miuix_appcompat_settings_window_bg_dark";

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new WeakHashMap<>());

    RecorderSettingsSurfaceAdapter(XposedModule module) {
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
                && activity.getClass().getName().endsWith(ACTIVITY_SUFFIX)
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity)) return;
        synchronized (scheduled) {
            if (scheduled.containsKey(activity)) return;
            scheduled.put(activity, Boolean.TRUE);
        }
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            release(activity);
            return;
        }
        content.post(() -> applyAndRelease(activity, false, "post"));
        content.postDelayed(() -> applyAndRelease(activity, false, "350ms"), 350L);
        content.postDelayed(() -> applyAndRelease(activity, true, "1000ms"), 1000L);
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
            View contentView = activity.findViewById(android.R.id.content);
            if (!(contentView instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) contentView;
            Drawable source = resolveThemeDrawable(activity);
            if (source == null) {
                module.log(Log.INFO, "RecorderSettings", pass + " no non-colour settings theme drawable");
                return;
            }
            Session session = sessions.get(activity);
            if (session != null && session.host != content) {
                session.restore();
                sessions.remove(activity);
                session = null;
            }
            if (session == null) {
                session = new Session(content);
                sessions.put(activity, session);
            }
            if (session.layer == null) {
                session.layer = new ThemeImageLayer(activity);
                session.layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                content.addView(session.layer, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            session.layer.setImageDrawable(copyDrawable(source, activity));
            clearFullScreenHosts(content, content, session, 0);
            module.log(Log.INFO, "RecorderSettings", pass + " attached settings theme layer and cleared host surfaces");
        } catch (Throwable error) {
            module.log(Log.ERROR, "RecorderSettings", "Cannot prepare recorder settings surface", error);
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
            module.log(Log.WARN, "RecorderSettings", "Cannot resolve active settings theme drawable", error);
            return null;
        }
    }

    private static Drawable copyDrawable(Drawable source, Activity activity) {
        Drawable.ConstantState state = source.getConstantState();
        return state == null ? source.mutate()
                : state.newDrawable(activity.getResources(), activity.getTheme()).mutate();
    }

    private void clearFullScreenHosts(View root, View view, Session session, int depth) {
        if (view == null || depth > 3 || view.getVisibility() != View.VISIBLE) return;
        if (isFullScreenStructuralHost(root, view, depth)) session.clearToTransparent(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child != session.layer) clearFullScreenHosts(root, child, session, depth + 1);
            }
        }
    }

    private static boolean isFullScreenStructuralHost(View root, View view, int depth) {
        if (!(view instanceof ViewGroup) || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        if (view.getWidth() < root.getWidth() * 0.90f || view.getHeight() < root.getHeight() * 0.10f) return false;
        String className = view.getClass().getName().toLowerCase();
        if (className.contains("card") || className.contains("cell") || className.contains("button")) return false;
        // The target hierarchy is content -> FrameLayout -> SpringBackLayout -> RecyclerView.
        return depth <= 3 && (className.contains("framelayout") || className.contains("springback")
                || className.contains("recyclerview"));
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
                catch (Throwable error) { module.log(Log.WARN, "RecorderSettings", "Lifecycle " + name, error); }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "RecorderSettings", "Cannot hook " + type.getName() + '#' + name, error);
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
        final ViewGroup host;
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();
        ThemeImageLayer layer;

        Session(ViewGroup host) {
            this.host = host;
        }

        void clearToTransparent(View view) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds.put(view, view.getBackground());
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        void restore() {
            for (Map.Entry<View, Drawable> entry : originalBackgrounds.entrySet()) {
                try { entry.getKey().setBackground(entry.getValue()); }
                catch (Throwable ignored) { }
            }
            originalBackgrounds.clear();
            try {
                if (layer != null) {
                    ViewParent parent = layer.getParent();
                    if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(layer);
                }
            } catch (Throwable ignored) { }
            layer = null;
        }
    }
}
