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
import android.view.ViewParent;
import android.view.ViewTreeObserver;
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
 * Exposes an already-resolved File Explorer theme window drawable.  The K70U
 * hierarchy contains an outer MIUIX host and a second host inside
 * MiuixNavigationLayout.  The home tabs and the directory browser attach their
 * own surfaces after the Activity is already resumed, so the precise hosts are
 * cleaned whenever that hierarchy changes.
 */
final class FileExplorerSurfaceAdapter {
    private static final String PACKAGE = "com.android.fileexplorer";
    private static final String MAIN_ACTIVITY = "com.android.fileexplorer.FileExplorerTabActivity";
    private static final float CARD_CORNER_RADIUS_PX = 40f;

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Activity> scheduled = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile int cachedCategoryListId;

    FileExplorerSurfaceAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        /*
         * The Browse page's "file type" grid cards are plain ViewGroups whose
         * opaque rounded fill is set per bind, so like the Calendar agenda
         * rows the frost is enforced on the frame instead of at layout time.
         */
        try {
            java.lang.reflect.Method draw = View.class.getDeclaredMethod("draw",
                    android.graphics.Canvas.class);
            draw.setAccessible(true);
            module.hook(draw).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (receiver instanceof View && ((View) receiver).getBackground() != null) {
                    try {
                        frostCategoryCard((View) receiver);
                    } catch (Throwable error) {
                        module.log(Log.WARN, "FileExplorerSurface", "draw frost", error);
                    }
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
            log("per-draw category card guard installed");
        } catch (Throwable error) {
            module.log(Log.ERROR, "FileExplorerSurface", "Cannot hook View.draw", error);
        }
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
        return activity != null && PACKAGE.equals(activity.getPackageName())
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
        ensureLiveRescan(activity, decor);
        decor.post(() -> applyAndRelease(activity, false, "post"));
        decor.postDelayed(() -> applyAndRelease(activity, false, "350ms"), 350L);
        decor.postDelayed(() -> applyAndRelease(activity, true, "1000ms"), 1000L);
    }

    private void applyAndRelease(Activity activity, boolean release, String pass) {
        if (isTarget(activity)) apply(activity, pass);
        if (release) synchronized (scheduled) { scheduled.remove(activity); }
    }

    private void apply(Activity activity, String pass) {
        Session session = sessionFor(activity);
        if (session.applying) return;
        session.applying = true;
        try {
            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null) {
                log(pass + " decor=absent");
                return;
            }
            Drawable decorBackground = decor.getBackground();
            boolean hasThemeDrawable = decorBackground != null && !(decorBackground instanceof ColorDrawable);
            List<View> overlays = findOverlays(activity, decor);
            if (!"layout".equals(pass)) {
                log(pass + " overlays=" + overlays.size() + " decor=" + describe(decorBackground)
                        + " hasThemeDrawable=" + hasThemeDrawable);
            }
            for (int index = 0; index < overlays.size(); index++) {
                clearNestedFallback(activity, decor, overlays.get(index), hasThemeDrawable, pass, index);
            }
            if (hasThemeDrawable) {
                clearNamedSurface(activity, decor, "file_browse_frame", pass);
                clearNamedSurface(activity, decor, "nested_header_layout", pass);
                clearNamedSurface(activity, decor, "springbacklayout", pass);
                clearNamedSurface(activity, decor, "navi_bar", pass);
                clearNamedSurface(activity, decor, "action_bar_container", pass);
                clearAndroidNamedSurface(activity, decor, "list", pass);
                clearNamedSurface(activity, decor, "miuix_bottom_navigation_bar", pass);
            }
            decor.invalidate();
        } catch (Throwable error) {
            module.log(Log.ERROR, "FileExplorerSurface", "Cannot apply File Explorer surface cleanup", error);
        } finally {
            session.applying = false;
        }
    }

    private void clearNestedFallback(Activity activity, View decor, View overlay, boolean hasThemeDrawable,
                                     String pass, int index) {
        Drawable background = overlay.getBackground();
        String prefix = pass + " overlay[" + index + "] id=" + entryName(activity, overlay)
                + " class=" + overlay.getClass().getName() + " frame=" + overlay.getWidth() + 'x'
                + overlay.getHeight();
        boolean viewport = overlay.getWidth() >= decor.getWidth() * 0.90f
                && overlay.getHeight() >= decor.getHeight() * 0.90f;
        boolean nestedNavigationHost = hasNavigationAncestor(overlay);
        if (!viewport || !nestedNavigationHost || !hasThemeDrawable) {
            log(prefix + " retain viewport=" + viewport + " nestedNavigation=" + nestedNavigationHost
                    + " themedDecor=" + hasThemeDrawable + " bg=" + describe(background));
            return;
        }
        if (!(background instanceof ColorDrawable)) {
            log(prefix + " retain=non-neutral-or-transparent bg=" + describe(background));
            return;
        }
        int color = ((ColorDrawable) background).getColor();
        if (!isOpaqueNeutral(color)) {
            log(prefix + " retain=non-neutral-colour bg=" + hex(color));
            return;
        }
        Session session = sessionFor(activity);
        session.save(overlay, background);
        overlay.setBackground(null);
        if (!"layout".equals(pass)) log(prefix + " cleared-exact-nested-fallback=" + hex(color));
    }

    /**
     * These ids were read from the running K70U hierarchy.  They are page
     * surfaces, not individual rows or cards: File Explorer replaces this
     * subtree when opening a directory.  Removing only a neutral drawable
     * would miss the themed LayerDrawable used by the bottom navigator, so a
     * named surface is cleared regardless of its drawable type.
     */
    private void clearNamedSurface(Activity activity, View decor, String entry, String pass) {
        int id = activity.getResources().getIdentifier(entry, "id", PACKAGE);
        clearSurfaceById(activity, decor, id, entry, pass);
    }

    private void clearAndroidNamedSurface(Activity activity, View decor, String entry, String pass) {
        int id = activity.getResources().getIdentifier(entry, "id", "android");
        clearSurfaceById(activity, decor, id, "android:" + entry, pass);
    }

    private void clearSurfaceById(Activity activity, View decor, int id, String entry, String pass) {
        if (id == 0) return;
        List<View> matches = new ArrayList<>();
        collectById(decor, id, matches, 0);
        for (View view : matches) {
            if (!hasNavigationAncestor(view)) continue;
            Drawable background = view.getBackground();
            if (background == null) continue;
            Session session = sessionFor(activity);
            session.save(view, background);
            view.setBackground(null);
            log(pass + " cleared-page-surface=" + entry + " class="
                    + view.getClass().getName() + " frame=" + view.getWidth() + 'x'
                    + view.getHeight() + " bg=" + describe(background));
        }
    }

    /**
     * The category grid cards are direct children of file_category_entries.
     * Their original fill is saved per view and replaced with the uniform
     * translucent rounded white the other card adapters use, re-tinted per
     * mode; RecyclerView rebinds re-enter here through the draw guard.
     */
    private void frostCategoryCard(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) return;
        int listId = cachedCategoryListId;
        if (listId == 0) {
            listId = view.getResources().getIdentifier("file_category_entries", "id", PACKAGE);
            cachedCategoryListId = listId;
        }
        if (listId == 0 || ((ViewGroup) parent).getId() != listId) return;
        boolean night = (view.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int wanted = Color.argb(night ? CardAlpha.dark() : CardAlpha.light(), 255, 255, 255);
        Drawable background = view.getBackground();
        if (isAlreadyFrosted(background, wanted)) return;
        Activity activity = activityOf(view);
        if (activity != null) sessionFor(activity).save(view, background);
        GradientDrawable frost = new GradientDrawable();
        frost.setShape(GradientDrawable.RECTANGLE);
        frost.setCornerRadius(CARD_CORNER_RADIUS_PX);
        frost.setColor(wanted);
        view.setBackground(frost);
        log("frosted category card class=" + view.getClass().getName()
                + " frame=" + view.getWidth() + 'x' + view.getHeight()
                + (night ? " night" : "") + " was=" + describe(background));
    }

    private static boolean isAlreadyFrosted(Drawable background, int wanted) {
        if (!(background instanceof GradientDrawable)) return false;
        android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
        return colors != null && colors.getDefaultColor() == wanted;
    }

    private static Activity activityOf(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private List<View> findOverlays(Activity activity, View decor) {
        int id = activity.getResources().getIdentifier("action_bar_overlay_layout", "id", PACKAGE);
        List<View> result = new ArrayList<>();
        collectOverlays(decor, id, result, 0);
        return result;
    }

    private void collectOverlays(View view, int overlayId, List<View> result, int depth) {
        if (view == null || depth > 20) return;
        boolean named = overlayId != 0 && view.getId() == overlayId;
        boolean classMatch = view.getClass().getName().toLowerCase().contains("actionbaroverlaylayout");
        if (named || classMatch) result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectOverlays(group.getChildAt(i), overlayId, result, depth + 1);
            }
        }
    }

    private void collectById(View view, int id, List<View> result, int depth) {
        if (view == null || depth > 24) return;
        if (view.getId() == id) result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectById(group.getChildAt(i), id, result, depth + 1);
            }
        }
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
            log("installed live hierarchy listener");
        } catch (Throwable error) {
            session.layoutListener = null;
            module.log(Log.WARN, "FileExplorerSurface", "Cannot observe File Explorer hierarchy", error);
        }
    }

    private void removeLiveRescan(Session session) {
        if (session.decor == null || session.layoutListener == null) return;
        try {
            ViewTreeObserver observer = session.decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(session.layoutListener);
        } catch (Throwable ignored) {
            // The view may already be detached while the Activity is stopping.
        }
        session.decor = null;
        session.layoutListener = null;
        session.rescanQueued = false;
    }

    private static boolean hasNavigationAncestor(View view) {
        ViewParent current = view == null ? null : view.getParent();
        for (int depth = 0; current instanceof View && depth < 16; depth++) {
            View ancestor = (View) current;
            if (ancestor.getClass().getName().contains("MiuixNavigationLayout")) return true;
            current = ancestor.getParent();
        }
        return false;
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
            try {
                entry.getKey().setBackground(entry.getValue());
                log("restored id=" + entryName(activity, entry.getKey()) + " bg=" + describe(entry.getValue()));
            } catch (Throwable error) {
                module.log(Log.WARN, "FileExplorerSurface", "Cannot restore File Explorer background", error);
            }
        }
    }

    private String entryName(Activity activity, View view) {
        if (view == null || view.getId() == 0 || view.getId() == View.NO_ID) return "";
        try {
            return activity.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isOpaqueNeutral(int color) {
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
    }

    private static String describe(Drawable drawable) {
        if (drawable == null) return "null";
        if (drawable instanceof ColorDrawable) return "ColorDrawable(" + hex(((ColorDrawable) drawable).getColor()) + ')';
        return drawable.getClass().getName() + '(' + drawable.getIntrinsicWidth() + 'x'
                + drawable.getIntrinsicHeight() + ')';
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
    }

    private void log(String message) {
        module.log(Log.INFO, "FileExplorerSurface", message);
    }

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try {
                    callback.after(chain.getThisObject(), args);
                } catch (Throwable error) {
                    module.log(Log.WARN, "FileExplorerSurface", "Lifecycle " + name, error);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "FileExplorerSurface", "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After {
        void after(Object receiver, Object[] args) throws Throwable;
    }

    private static final class Session {
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();
        View decor;
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean rescanQueued;
        boolean applying;

        void save(View view, Drawable drawable) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds.put(view, drawable);
        }
    }
}
