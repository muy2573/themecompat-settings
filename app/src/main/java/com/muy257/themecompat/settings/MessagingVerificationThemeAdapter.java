package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Final look for the SMS verification-code page (FlatMessageListActivity).
 *
 * Evidence collected on v0.33.25: the flat grey sheet covering this page is
 * the theme's own asset, not a leftover system surface.  The author shipped
 * flatshow_bg.9.png as a tiny solid stub while every other Messaging page
 * resolves the full conversation artwork (conversation_bg.9.png, with a
 * nightmode variant), so this page alone renders featureless grey with a
 * hard action-bar block above it.  The owner chose to unify the page with
 * the rest of Messaging.
 *
 * This adapter re-resolves the themed conversation artwork on every apply
 * and pins it on both the DecorView and the Miuix ActionBarOverlayLayout,
 * then keeps re-clearing the neutral action-bar block.  v0.33.27 proved the
 * block is painted after the resume passes, so — like the surface adapter —
 * this adapter rescans on every global layout and clears the container plus
 * any broad neutral descendant background inside it.  Originals are kept per
 * activity and restored when it is destroyed.
 */
final class MessagingVerificationThemeAdapter {
    private static final String PACKAGE_NAME = "com.android.mms";
    private static final String ACTIVITY = "com.android.mms.ui.FlatMessageListActivity";
    private static final String LOG_TAG = "MessagingVerifyTheme";

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new IdentityHashMap<>());

    MessagingVerificationThemeAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        hookLifecycle("callActivityOnCreate", new Class<?>[]{Activity.class, Bundle.class}, false);
        hookLifecycle("callActivityOnResume", new Class<?>[]{Activity.class}, false);
        hookConfigurationChanges();
        hookLifecycle("callActivityOnDestroy", new Class<?>[]{Activity.class}, true);
        module.log(Log.INFO, LOG_TAG, "installed");
    }

    private void hookLifecycle(String name, Class<?>[] parameters, final boolean restore) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (args.length > 0 && args[0] instanceof Activity) {
                    Activity activity = (Activity) args[0];
                    if (restore) restore(activity);
                    else schedule(activity);
                }
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
        ensureLiveRescan(activity, decor);
        // Follow the verified 0/300/900ms cadence for the first inflation.
        decor.post(() -> applyAndRelease(activity, false, "post"));
        decor.postDelayed(() -> applyAndRelease(activity, false, "350ms"), 350L);
        decor.postDelayed(() -> applyAndRelease(activity, true, "1000ms"), 1000L);
    }

    private void applyAndRelease(Activity activity, boolean release, String pass) {
        try {
            if (isTarget(activity)) apply(activity, pass);
        } finally {
            if (release) release(activity);
        }
    }

    private void release(Activity activity) {
        synchronized (scheduled) { scheduled.remove(activity); }
    }

    private void apply(Activity activity, String pass) {
        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor == null) return;
        Drawable artwork = themedConversationArtwork(activity);
        if (artwork == null) {
            module.log(Log.WARN, LOG_TAG, pass + " conversation artwork unresolved");
            return;
        }
        Session session = sessionFor(activity);
        if (session.applying) return;
        session.applying = true;
        try {
            if (session.originalDecor == null) session.originalDecor = decor.getBackground();
            decor.setBackground(artwork);
            View overlay = findOverlayLayout(decor);
            if (overlay != null) {
                if (session.originalOverlay == null) {
                    session.originalOverlay = overlay.getBackground();
                }
                session.overlayLayout = overlay;
                overlay.setBackground(artwork);
            }
            int[] cleared = {0};
            clearNeutralActionBarBlocks(decor, session, cleared, 0);
            if (cleared[0] > 0) {
                module.log(Log.INFO, LOG_TAG, pass + " cleared blocks=" + cleared[0]);
            }
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, pass + " apply failed", error);
        } finally {
            session.applying = false;
        }
    }

    private void restore(Activity activity) {
        Session session;
        synchronized (sessions) {
            session = sessions.remove(activity);
        }
        if (session == null) return;
        removeLiveRescan(session);
        if (session.originalDecor != null) {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.setBackground(session.originalDecor);
        }
        if (session.originalOverlay != null && session.overlayLayout != null) {
            session.overlayLayout.setBackground(session.originalOverlay);
        }
        for (Map.Entry<View, Drawable> entry : session.originalBackgrounds.entrySet()) {
            try {
                entry.getKey().setBackground(entry.getValue());
            } catch (Throwable ignored) {
                // The list may already be torn down; the window dies with it.
            }
        }
        for (Map.Entry<View, Drawable> entry : session.originalPrimaries.entrySet()) {
            try {
                setPrimaryBackground(entry.getKey(), entry.getValue());
            } catch (Throwable ignored) {
                // The list may already be torn down; the window dies with it.
            }
        }
    }

    /**
     * The action-bar block is painted after the resume passes, so rescan on
     * every global layout, coalesced to one apply per layout burst.
     */
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
        } catch (Throwable error) {
            session.layoutListener = null;
            module.log(Log.WARN, LOG_TAG, "cannot observe hierarchy", error);
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

    private void clearNeutralActionBarBlocks(View view, Session session, int[] cleared,
                                             int depth) {
        if (view == null || depth > 24) return;
        boolean actionBarContainer = isActionBarContainer(view);
        Drawable background = view.getBackground();
        if (actionBarContainer) {
            // Miuix draws the bar's primary background straight from onDraw
            // (title_bar_tall_bg attr) without storing it as the view
            // background, so a null getBackground() still paints a full
            // opaque sheet.  Null it through the public setter and keep the
            // original for restore.
            Drawable primary = primaryBackgroundOf(view);
            session.logContainerOnce(module, "bg=" + describeBackground(background)
                    + " primary=" + describeBackground(primary));
            if (primary != null) {
                session.savePrimary(view, primary);
                setPrimaryBackground(view, null);
                cleared[0]++;
            }
            if (background != null && isOpaqueNeutral(background)) {
                session.save(view, background);
                view.setBackground(null);
                cleared[0]++;
            }
            // The stock bar can paint its sheet on an inner, equally broad view
            // (for example a themed title-bar nine-patch host); clear those too
            // while they stay inside the action-bar container.
            if (view instanceof ViewGroup) {
                clearBroadNeutralDescendants(view, (ViewGroup) view, session, cleared);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                clearNeutralActionBarBlocks(group.getChildAt(index), session, cleared, depth + 1);
            }
        }
    }

    private static Drawable primaryBackgroundOf(View view) {
        try {
            Method getter = view.getClass().getMethod("getPrimaryBackground");
            getter.setAccessible(true);
            return (Drawable) getter.invoke(view);
        } catch (Throwable error) {
            return null;
        }
    }

    private static void setPrimaryBackground(View view, Drawable value) {
        try {
            Method setter = view.getClass().getMethod("setPrimaryBackground", Drawable.class);
            setter.setAccessible(true);
            setter.invoke(view, value);
        } catch (Throwable ignored) {
            // Older vendor builds may not expose the setter; the stored
            // background clear still applies.
        }
    }

    private void clearBroadNeutralDescendants(View decor, ViewGroup group, Session session,
                                              int[] cleared) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            Drawable background = child == null ? null : child.getBackground();
            if (background != null && isBroad(decor, child) && isOpaqueNeutral(background)) {
                session.save(child, background);
                child.setBackground(null);
                cleared[0]++;
            }
            if (child instanceof ViewGroup) {
                clearBroadNeutralDescendants(decor, (ViewGroup) child, session, cleared);
            }
        }
    }

    private static boolean isBroad(View decor, View view) {
        return view.getWidth() >= decor.getWidth() * 0.8f && view.getHeight() >= 200;
    }

    private static boolean isActionBarContainer(View view) {
        return view.getClass().getName().contains("ActionBarContainer");
    }

    private static boolean isOpaqueNeutral(Drawable background) {
        int color;
        if (background instanceof ColorDrawable) {
            color = ((ColorDrawable) background).getColor();
        } else if (background instanceof GradientDrawable) {
            android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
            if (colors == null) return false;
            color = colors.getDefaultColor();
        } else {
            return false;
        }
        if (color == 0) return false;
        int alpha = (color >>> 24) & 0xFF;
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        if (alpha != 255) return false;
        int min = Math.min(red, Math.min(green, blue));
        int max = Math.max(red, Math.max(green, blue));
        return max - min <= 24;
    }

    private static String describeBackground(Drawable background) {
        if (background == null) return "null";
        if (background instanceof ColorDrawable) {
            return "ColorDrawable(" + String.format("#%08X", ((ColorDrawable) background).getColor())
                    + ",a=" + background.getAlpha() + ')';
        }
        if (background instanceof GradientDrawable) {
            android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
            return "GradientDrawable(color=" + (colors == null ? "null"
                    : String.format("#%08X", colors.getDefaultColor())) + ",a="
                    + background.getAlpha() + ')';
        }
        return background.getClass().getName() + "(a=" + background.getAlpha() + ')';
    }

    private static View findOverlayLayout(View view) {
        if (view.getClass().getName().contains("ActionBarOverlayLayout")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findOverlayLayout(group.getChildAt(index));
                if (match != null) return match;
            }
        }
        return null;
    }

    /**
     * Resolves the themed conversation artwork afresh on every call so the
     * theme engine's day/night variant is honoured after a mode switch.
     */
    private static Drawable themedConversationArtwork(Activity activity) {
        Resources resources = activity.getResources();
        int id = resources.getIdentifier("conversation_bg", "drawable", PACKAGE_NAME);
        if (id == 0) id = resources.getIdentifier("window_bg_light", "drawable", PACKAGE_NAME);
        if (id == 0) return null;
        try {
            return resources.getDrawable(id);
        } catch (Throwable error) {
            return null;
        }
    }

    private boolean isTarget(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && PACKAGE_NAME.equals(activity.getPackageName())
                && ACTIVITY.equals(activity.getClass().getName());
    }

    private Session sessionFor(Activity activity) {
        Session session = sessions.get(activity);
        if (session == null) {
            session = new Session();
            sessions.put(activity, session);
        }
        return session;
    }

    private static final class Session {
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();
        final Map<View, Drawable> originalPrimaries = new IdentityHashMap<>();
        Drawable originalDecor;
        Drawable originalOverlay;
        View overlayLayout;
        View decor;
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean rescanQueued;
        boolean applying;
        String containerSummary;

        void save(View view, Drawable background) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds.put(view, background);
        }

        void savePrimary(View view, Drawable primary) {
            if (!originalPrimaries.containsKey(view)) originalPrimaries.put(view, primary);
        }

        void logContainerOnce(XposedModule module, String summary) {
            if (summary.equals(containerSummary)) return;
            containerSummary = summary;
            module.log(Log.INFO, LOG_TAG, "action-bar container " + summary);
        }
    }
}
