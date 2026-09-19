package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
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
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Lets the active Camera theme window drawable remain visible behind the
 * CameraPreferenceActivity's own opaque preference cells.  This is the
 * cleanup half of HyperBackground's published implementation: no wallpaper
 * view, resource replacement, image file, tint, or layout is introduced.
 */
final class CameraSurfaceAdapter {
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String PREFERENCE_ACTIVITY =
            "com.android.camera.CameraPreferenceActivity";
    private static final String ACTION_BAR_OVERLAY_LAYOUT =
            "miuix.appcompat.internal.app.widget.ActionBarOverlayLayout";
    private static final long RESCAN_WINDOW_MS = 2500L;

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Activity> scheduled =
            Collections.newSetFromMap(new WeakHashMap<>());

    CameraSurfaceAdapter(XposedModule module) {
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
                new Class<?>[]{Activity.class}, (receiver, args) -> destroy((Activity) args[0]));
        hookAfter(Activity.class, "onContentChanged", new Class<?>[]{}, (receiver, args) -> {
            if (receiver instanceof Activity) schedule((Activity) receiver);
        });
        // A task-restored CameraPreferenceActivity can already be resumed before a
        // freshly updated LSPosed module is attached.  Its first focused window is
        // nevertheless guaranteed to pass here, so this covers that exact path.
        hookAfter(Activity.class, "onWindowFocusChanged", new Class<?>[]{Boolean.TYPE},
                (receiver, args) -> {
                    if (receiver instanceof Activity && args.length == 1
                            && Boolean.TRUE.equals(args[0])) {
                        schedule((Activity) receiver);
                    }
                });
        hookAfter(Activity.class, "onConfigurationChanged", new Class<?>[]{Configuration.class},
                (receiver, args) -> {
                    if (receiver instanceof Activity) schedule((Activity) receiver);
                });
        module.log(Log.INFO, "CameraSurface", "Installed CameraPreferenceActivity lifecycle hooks");
    }

    private void schedule(Activity activity) {
        if (activity != null && CAMERA_PACKAGE.equals(activity.getPackageName())) {
            module.log(Log.INFO, "CameraSurface", "Lifecycle candidate activity="
                    + activity.getClass().getName());
        }
        if (!isTarget(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (scheduled) {
            if (!scheduled.add(activity)) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            module.log(Log.WARN, "CameraSurface", "No decor for target activity");
            release(activity);
            return;
        }
        apply(activity);
        decor.post(() -> apply(activity));
        decor.postDelayed(() -> apply(activity), 280L);
        decor.postDelayed(() -> apply(activity), 900L);
        decor.postDelayed(() -> apply(activity), 1_500L);
        decor.postDelayed(() -> {
            apply(activity);
            release(activity);
        }, 2_300L);
    }

    private void release(Activity activity) {
        synchronized (scheduled) {
            scheduled.remove(activity);
        }
    }

    private boolean isTarget(Activity activity) {
        return activity != null && CAMERA_PACKAGE.equals(activity.getPackageName())
                && PREFERENCE_ACTIVITY.equals(activity.getClass().getName());
    }

    private void apply(Activity activity) {
        if (!isTarget(activity) || activity.isFinishing() || activity.isDestroyed()) {
            module.log(Log.INFO, "CameraSurface", "Skipped apply target=" + isTarget(activity)
                    + " finishing=" + (activity != null && activity.isFinishing())
                    + " destroyed=" + (activity != null && activity.isDestroyed()));
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            module.log(Log.WARN, "CameraSurface", "No ViewGroup content="
                    + (content == null ? "null" : content.getClass().getName()));
            return;
        }
        Session session = sessions.get(activity);
        if (session == null) {
            session = new Session(module, activity, (ViewGroup) content);
            sessions.put(activity, session);
        }
        session.rearmRescan();
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!night) session.clearOpaqueCardGroupDecorations();
        int changed = session.clearPreferencePageBackgrounds();
        if (changed > 0) {
            module.log(Log.INFO, "CameraSurface", "Cleared Camera preference page background holders=" + changed
                    + " activity=" + activity.getClass().getName());
        }
    }

    private void restore(Activity activity) {
        if (activity == null) return;
        Session session = sessions.get(activity);
        if (session != null) session.restore();
    }

    private void destroy(Activity activity) {
        if (activity == null) return;
        Session session = sessions.remove(activity);
        if (session != null) session.dispose();
        release(activity);
    }

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try {
                    callback.run(chain.getThisObject(), args);
                } catch (Throwable error) {
                    module.log(Log.WARN, "CameraSurface", "Lifecycle callback failed " + name, error);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "CameraSurface", "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After {
        void run(Object receiver, Object[] args) throws Throwable;
    }

    private static final class Session {
        private final XposedModule module;
        private final Activity activity;
        private final ViewGroup root;
        private final Map<View, Drawable> originals = new IdentityHashMap<>();
        private final Map<Object, DecorationState> decorationOriginals = new IdentityHashMap<>();
        private final Set<View> diagnosed = Collections.newSetFromMap(new IdentityHashMap<>());
        private ViewTreeObserver.OnGlobalLayoutListener listener;
        private long rescanDeadline;

        Session(XposedModule module, Activity activity, ViewGroup root) {
            this.module = module;
            this.activity = activity;
            this.root = root;
            installRescan();
        }

        int clearOpaqueCardGroupDecorations() {
            return clearCardDecorations(root, 0);
        }

        int clearPreferencePageBackgrounds() {
            int changed = clearPreferencePageBackgrounds(root, 0);
            // android.R.id.content is *inside* ActionBarOverlayLayout.  Gb() obtains
            // the overlay through Activity.findViewById(), so it is an ancestor of
            // this root rather than a descendant of it.
            for (ViewParent parent = root.getParent(); parent instanceof View;
                    parent = parent.getParent()) {
                changed += clearPreferencePageBackground((View) parent);
            }
            return changed;
        }

        private int clearPreferencePageBackgrounds(View view, int depth) {
            if (view == null || view.getVisibility() != View.VISIBLE || depth > 16) return 0;
            int changed = 0;
            // MiuixPreferenceFragment.Gb() writes preferenceCardPageBackground to
            // precisely this view.  It is the opaque layer under the preference
            // cards, not a generic layout or row background.
            changed += clearPreferencePageBackground(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    changed += clearPreferencePageBackgrounds(group.getChildAt(i), depth + 1);
                }
            }
            return changed;
        }

        private int clearPreferencePageBackground(View view) {
            if (!ACTION_BAR_OVERLAY_LAYOUT.equals(view.getClass().getName())) return 0;
            Drawable background = view.getBackground();
            if (background == null) return 0;
            if (background instanceof ColorDrawable
                    && Color.alpha(((ColorDrawable) background).getColor()) == 0) {
                return 0;
            }
            if (!originals.containsKey(view)) originals.put(view, background);
            view.setBackground(new ColorDrawable(Color.TRANSPARENT));
            return 1;
        }

        private int clearCardDecorations(View view, int depth) {
            if (view == null || view.getVisibility() != View.VISIBLE || depth > 16) return 0;
            int[] changed = {0};
            if (isRecyclerView(view)) clearCardDecoration(view, changed);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    changed[0] += clearCardDecorations(group.getChildAt(i), depth + 1);
                }
            }
            return changed[0];
        }

        private void clearCardDecoration(View recycler, int[] changed) {
            try {
                // Camera ships a Miuix RecyclerView subclass.  Its AndroidX methods are
                // inherited but are not exposed through getMethod() on this build, so
                // resolve them explicitly through its actual class hierarchy.
                Method countMethod = findMethod(recycler.getClass(), "getItemDecorationCount");
                Method atMethod = findMethod(recycler.getClass(), "getItemDecorationAt", Integer.TYPE);
                Method invalidateMethod = findMethod(recycler.getClass(), "invalidateItemDecorations");
                int count = ((Number) countMethod.invoke(recycler)).intValue();
                for (int index = 0; index < count; index++) {
                    Object decoration = atMethod.invoke(recycler, index);
                    if (!isCameraCardDecoration(decoration)) continue;
                    java.lang.reflect.Field backgroundField = findCardBackgroundField(decoration.getClass());
                    Object current = backgroundField.get(decoration);
                    if (!(current instanceof Drawable)) continue;
                    Drawable drawable = (Drawable) current;
                    if (diagnosed.add(recycler)) {
                        module.log(Log.INFO, "CameraSurface", "Card decoration="
                                + drawable.getClass().getName() + " color="
                                + describeColor(drawable));
                    }
                    // This is not a general-purpose View background: it is exactly
                    // PreferenceFragment$c's preferenceCardGroupBackground field.
                    // Xiaomi supplies this as a translucent ColorDrawable on this
                    // build, so requiring alpha=255 incorrectly skipped it.
                    if (!isVisibleCardColor(drawable)) continue;
                    if (!decorationOriginals.containsKey(decoration)) {
                        decorationOriginals.put(decoration,
                                new DecorationState(recycler, decoration, backgroundField, drawable));
                    }
                    // Miuix checks this field for null before drawing its group path.
                    backgroundField.set(decoration, null);
                    invalidateMethod.invoke(recycler);
                    changed[0]++;
                }
            } catch (Throwable error) {
                if (diagnosed.add(recycler)) {
                    module.log(Log.WARN, "CameraSurface", "Cannot inspect Camera card decoration recycler="
                            + recycler.getClass().getName() + " error="
                            + error.getClass().getName() + ':' + error.getMessage(), error);
                }
            }
        }

        private Method findMethod(Class<?> type, String name, Class<?>... parameters)
                throws NoSuchMethodException {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    Method method = cursor.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            throw new NoSuchMethodException(type.getName() + '#' + name);
        }

        private java.lang.reflect.Field findCardBackgroundField(Class<?> type)
                throws NoSuchFieldException {
            // This is the field assigned in PreferenceFragment$c.f():
            // bi.d.g(context, xi.t.preferenceCardGroupBackground).
            try {
                java.lang.reflect.Field exact = type.getDeclaredField("f26684j");
                exact.setAccessible(true);
                return exact;
            } catch (NoSuchFieldException ignored) {
                // Xiaomi can renumber obfuscated members between Miuix builds.  In the
                // source-proven decorator class, accept a fallback only when there is
                // exactly one Drawable member; anything ambiguous is deliberately left
                // unchanged.
                java.lang.reflect.Field result = null;
                for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                    for (java.lang.reflect.Field field : cursor.getDeclaredFields()) {
                        if (!Drawable.class.isAssignableFrom(field.getType())) continue;
                        if (result != null) throw new NoSuchFieldException(
                                "ambiguous Drawable member in " + type.getName());
                        field.setAccessible(true);
                        result = field;
                    }
                }
                if (result == null) throw new NoSuchFieldException(
                        "no Drawable member in " + type.getName());
                return result;
            }
        }

        private boolean isRecyclerView(View view) {
            Class<?> cursor = view.getClass();
            while (cursor != null) {
                if ("androidx.recyclerview.widget.RecyclerView".equals(cursor.getName())) return true;
                cursor = cursor.getSuperclass();
            }
            return false;
        }

        private boolean isCameraCardDecoration(Object decoration) {
            return decoration != null
                    && "miuix.preference.PreferenceFragment$c".equals(decoration.getClass().getName());
        }

        private boolean isVisibleCardColor(Drawable drawable) {
            if (!(drawable instanceof ColorDrawable)) return false;
            int color = ((ColorDrawable) drawable).getColor();
            return Color.alpha(color) != 0;
        }

        private String describeColor(Drawable drawable) {
            if (!(drawable instanceof ColorDrawable)) return "n/a";
            return String.format("#%08X", ((ColorDrawable) drawable).getColor());
        }

        void rearmRescan() {
            if (listener == null) installRescan();
            else rescanDeadline = android.os.SystemClock.uptimeMillis() + RESCAN_WINDOW_MS;
        }

        private void installRescan() {
            try {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer == null || !observer.isAlive()) return;
                rescanDeadline = android.os.SystemClock.uptimeMillis() + RESCAN_WINDOW_MS;
                listener = () -> {
                    if (activity.isFinishing() || activity.isDestroyed()
                            || android.os.SystemClock.uptimeMillis() > rescanDeadline) {
                        removeRescan();
                        return;
                    }
                    clearPreferencePageBackgrounds();
                };
                observer.addOnGlobalLayoutListener(listener);
            } catch (Throwable ignored) {
                listener = null;
            }
        }

        void restore() {
            removeRescan();
            for (Map.Entry<View, Drawable> entry : originals.entrySet()) {
                try {
                    entry.getKey().setBackground(entry.getValue());
                } catch (Throwable ignored) {
                    // A recycled row may already be detached.
                }
            }
            originals.clear();
            for (DecorationState state : decorationOriginals.values()) {
                try {
                    state.field.set(state.decoration, state.original);
                    Method invalidate = findMethod(state.recycler.getClass(),
                            "invalidateItemDecorations");
                    invalidate.invoke(state.recycler);
                } catch (Throwable ignored) {
                    // The recycler can already be detached during Activity teardown.
                }
            }
            decorationOriginals.clear();
        }

        void dispose() {
            restore();
        }

        private void removeRescan() {
            if (listener == null) return;
            try {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
            } catch (Throwable ignored) {
                // The root can be detached while the Activity is stopping.
            }
            listener = null;
        }

        private static final class DecorationState {
            final View recycler;
            final java.lang.reflect.Field field;
            final Drawable original;
            final Object decoration;

            DecorationState(View recycler, Object decoration, java.lang.reflect.Field field, Drawable original) {
                this.recycler = recycler;
                this.decoration = decoration;
                this.field = field;
                this.original = original;
            }
        }
    }

}
