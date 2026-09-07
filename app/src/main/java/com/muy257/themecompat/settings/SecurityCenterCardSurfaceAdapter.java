package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Card painter used by the confirmed SecurityCenter home pages.  App Manager
 * and Power Center both construct {@code miuix.recyclerview.card.f(Context)};
 * it owns the opaque card drawable and draws it directly on Canvas.
 */
final class SecurityCenterCardSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.miui.securitycenter";
    private static final String DECORATION_CLASS = "miuix.recyclerview.card.f";
    private static final String PRIVACY_ACTIVITY =
            "com.miui.permcenter.privacycenter.PrivacySafetyActivity";
    private static final String APP_DETAILS_ACTIVITY =
            "com.miui.appmanager.ApplicationsDetailsActivity";
    private static final String HYPER_CELL_LAYOUT = "miuix.flexible.view.HyperCellLayout";
    private static final String PREFERENCE_FRAME_DECORATION =
            "miuix.preference.PreferenceFragment$f";
    // 0x48 was too faint in the light setting pages.  0x90 preserves card
    // separation while allowing the themed background to remain visible.
    private static final int LIGHT_CARD_ALPHA = 0x90;

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final Map<Object, Integer> adjustedPrivacyDecorations =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<View, Integer> adjustedPrivacyHeroCards =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Object, Boolean> seenAppDetailDecorations =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<View, Boolean> adjustedAppDetailRows =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile int cachedDetailsRecyclerId;

    SecurityCenterCardSurfaceAdapter(XposedModule module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        /*
         * The application-details preference rows repaint at unpredictable
         * times (async package queries rebind the list after layout), so like
         * the other card adapters the translucent tint is enforced on the
         * frame.  The native drawable keeps its grouped corner radii; only
         * its alpha changes, and dark mode returns it to opaque-native.
         */
        try {
            Method draw = View.class.getDeclaredMethod("draw", android.graphics.Canvas.class);
            draw.setAccessible(true);
            module.hook(draw).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (receiver instanceof View && ((View) receiver).getBackground() != null) {
                    try {
                        guardAppDetailRow((View) receiver);
                    } catch (Throwable error) {
                        write(Log.WARN, "app details draw guard", error);
                    }
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
            write(Log.INFO, "app details per-draw row guard installed", null);
        } catch (Throwable error) {
            write(Log.ERROR, "Cannot hook View.draw for app details rows", error);
        }
        hookPrivacySurfaceLifecycle();
        try {
            Class<?> type = Class.forName(DECORATION_CLASS, false, classLoader);
            Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            module.hook(constructor).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                Context context = args.length > 0 && args[0] instanceof Context
                        ? (Context) args[0] : null;
                apply(chain.getThisObject(), context);
                return result;
            });
            write(Log.INFO, "Hooked miuix.recyclerview.card.f(Context)", null);
        } catch (Throwable error) {
            write(Log.ERROR, "Cannot hook miuix.recyclerview.card.f(Context)", error);
        }
    }

    private void hookPrivacySurfaceLifecycle() {
        try {
            Method resume = Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class);
            resume.setAccessible(true);
            module.hook(resume).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (args.length > 0 && args[0] instanceof Activity) {
                    schedulePrivacySurface((Activity) args[0], "resume");
                }
                return result;
            });
            Method contentChanged = Activity.class.getDeclaredMethod("onContentChanged");
            contentChanged.setAccessible(true);
            module.hook(contentChanged).intercept(chain -> {
                Object result = chain.proceed(chain.getArgs().toArray(new Object[0]));
                if (chain.getThisObject() instanceof Activity) {
                    schedulePrivacySurface((Activity) chain.getThisObject(), "content");
                }
                return result;
            });
            Method configurationChanged = Activity.class.getDeclaredMethod("onConfigurationChanged",
                    Configuration.class);
            configurationChanged.setAccessible(true);
            module.hook(configurationChanged).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (chain.getThisObject() instanceof Activity) {
                    Activity activity = (Activity) chain.getThisObject();
                    schedulePrivacySurface(activity, "configuration");
                }
                return result;
            });
            write(Log.INFO, "Hooked privacy Activity lifecycle for final-card scan", null);
        } catch (Throwable error) {
            write(Log.ERROR, "Cannot hook privacy Activity lifecycle", error);
        }
    }

    private void schedulePrivacySurface(Activity activity, String reason) {
        if (activity == null || !PACKAGE_NAME.equals(activity.getPackageName())
                || (!PRIVACY_ACTIVITY.equals(activity.getClass().getName())
                && !APP_DETAILS_ACTIVITY.equals(activity.getClass().getName()))
                || activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(() -> scanPrivacySurface(activity, decor, reason + ":300ms"), 300L);
        decor.postDelayed(() -> scanPrivacySurface(activity, decor, reason + ":1000ms"), 1000L);
    }

    private void scanPrivacySurface(Activity activity, View root, String pass) {
        if (activity.isFinishing() || activity.isDestroyed() || root.getWidth() <= 0) return;
        if (APP_DETAILS_ACTIVITY.equals(activity.getClass().getName())) {
            scanAppDetailsSurface(activity, root, pass);
            return;
        }
        int candidates = scanPrivacySurface(activity, root, root, pass);
        if (candidates > 0) {
            write(Log.INFO, "privacy card-background scan " + pass + " candidates=" + candidates
                    + " mode=" + (isNight(activity) ? "dark" : "light"), null);
        }
    }

    private int scanPrivacySurface(Activity activity, View root, View view, String pass) {
        if (view == null || view.getVisibility() != View.VISIBLE) return 0;
        int changed = 0;
        applyPrivacyHeroCard(view, root, activity, pass);
        if (HYPER_CELL_LAYOUT.equals(view.getClass().getName())
                && view.getWidth() >= root.getWidth() * 0.80f
                && view.getHeight() >= 96 && view.getHeight() <= root.getHeight() * 0.30f) {
            applyPrivacyPreferenceDecoration(view, activity, pass);
            changed++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                changed += scanPrivacySurface(activity, root, group.getChildAt(index), pass);
            }
        }
        return changed;
    }

    /**
     * The recent-permission hero area is outside the PreferenceFragment. Its
     * three exact card IDs each own a GradientDrawable background, confirmed
     * from the running page: location, microphone and camera.
     */
    private void applyPrivacyHeroCard(View view, View root, Context context, String pass) {
        if (!isPrivacyHeroCard(view, context) || view.getWidth() < root.getWidth() * 0.35f) return;
        // Deep mode is natively semi-transparent on this page. Do not replace
        // its drawable or alpha with a value inferred from light mode.
        if (isNight(context)) return;
        Drawable background = view.getBackground();
        if (!(background instanceof GradientDrawable)) {
            write(Log.WARN, "Privacy hero " + resourceName(context, view.getId()) + " background is "
                    + (background == null ? "null" : background.getClass().getName()), null);
            return;
        }
        int targetAlpha = LIGHT_CARD_ALPHA;
        GradientDrawable cardBackground = (GradientDrawable) background.mutate();
        cardBackground.setAlpha(targetAlpha);
        view.invalidate();
        Integer previous = adjustedPrivacyHeroCards.put(view, targetAlpha);
        if (previous == null || previous != targetAlpha) {
            write(Log.INFO, "privacy hero " + resourceName(context, view.getId())
                    + " GradientDrawable -> alpha=0x" + Integer.toHexString(targetAlpha)
                    + " mode=" + (isNight(context) ? "dark" : "light") + " pass=" + pass, null);
        }
    }

    /**
     * ApplicationsDetailsActivity never constructs miuix.recyclerview.card.f,
     * so its grouped preference blocks are painted either by a
     * PreferenceFragment-style ItemDecoration or by the row views' own
     * backgrounds.  The decoration's proven shape on this ROM is the privacy
     * page's: field {@code s} holding the card ColorDrawable.  Both paths are
     * idempotent and the first observed one wins.
     */
    private void scanAppDetailsSurface(Activity activity, View root, String pass) {
        if (isNight(activity)) return;
        int recyclerId = cachedDetailsRecyclerId;
        if (recyclerId == 0) {
            recyclerId = activity.getResources().getIdentifier("recycler_view", "id", PACKAGE_NAME);
            if (recyclerId == 0) return;
            cachedDetailsRecyclerId = recyclerId;
        }
        View recycler = root.findViewById(recyclerId);
        if (recycler == null) return;
        Object value = readField(recycler, "mItemDecorations");
        if (!(value instanceof List)) return;
        for (Object decoration : (List<?>) value) {
            if (decoration == null || seenAppDetailDecorations.containsKey(decoration)) continue;
            seenAppDetailDecorations.put(decoration, Boolean.TRUE);
            Object card = readField(decoration, "s");
            if (!(card instanceof ColorDrawable)) {
                write(Log.INFO, "app details decoration " + decoration.getClass().getName()
                        + " s=" + (card == null ? "absent" : card.getClass().getSimpleName())
                        + " pass=" + pass, null);
                continue;
            }
            ColorDrawable cardColor = (ColorDrawable) card;
            cardColor.setColor(withAlpha(cardColor.getColor(), LIGHT_CARD_ALPHA));
            recycler.invalidate();
            write(Log.INFO, "app details decoration " + decoration.getClass().getName()
                    + " s -> alpha=0x" + Integer.toHexString(LIGHT_CARD_ALPHA) + " pass=" + pass, null);
        }
    }

    private void guardAppDetailRow(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) return;
        int recyclerId = cachedDetailsRecyclerId;
        if (recyclerId == 0) {
            recyclerId = view.getResources().getIdentifier("recycler_view", "id", PACKAGE_NAME);
            if (recyclerId == 0) return;
            cachedDetailsRecyclerId = recyclerId;
        }
        if (((ViewGroup) parent).getId() != recyclerId) return;
        Drawable background = view.getBackground();
        if (isNight(view.getContext())) {
            if (adjustedAppDetailRows.remove(view) != null && background.getAlpha() != 255) {
                background.setAlpha(255);
                view.invalidate();
            }
            return;
        }
        if (background.getAlpha() != 255) return;
        background.mutate();
        background.setAlpha(LIGHT_CARD_ALPHA);
        adjustedAppDetailRows.put(view, Boolean.TRUE);
        view.invalidate();
    }

    private static boolean isPrivacyHeroCard(View view, Context context) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        String name = resourceName(context, id);
        return "behavior_location".equals(name) || "behavior_mic".equals(name)
                || "behavior_camera".equals(name);
    }

    /**
     * The cards are not View backgrounds. PreferenceFragment$f's onDraw()
     * chooses its {@code s} drawable on this ROM, obtains s.getColor(), then
     * overwrites the Paint before drawing the rounded path.  s is the actual
     * ColorDrawable #FFFFFFFF recorded from the live PrivacySafetyActivity.
     */
    private void applyPrivacyPreferenceDecoration(View view, Context context, String pass) {
        Object decoration = findPrivacyPreferenceDecoration(view);
        if (decoration == null) return;
        // The first cold launch in deep mode already uses the intended MIUIX
        // translucent card state. Only compensate for the opaque light state.
        if (isNight(context)) return;
        int targetAlpha = LIGHT_CARD_ALPHA;
        try {
            Object value = readField(decoration, "s");
            if (!(value instanceof ColorDrawable)) {
                write(Log.WARN, "PrivacySafety frame s is "
                        + (value == null ? "null" : value.getClass().getName()), null);
                return;
            }
            ColorDrawable cardColor = (ColorDrawable) value;
            cardColor.setColor(withAlpha(cardColor.getColor(), targetAlpha));
            // The Canvas decoration is below all child views, so invalidate the
            // RecyclerView rather than only the transparent HyperCellLayout.
            View recycler = findRecyclerView(view);
            if (recycler != null) recycler.invalidate();
            Integer previous = adjustedPrivacyDecorations.put(decoration, targetAlpha);
            if (previous == null || previous != targetAlpha) {
                write(Log.INFO, "privacy preference decoration s(ColorDrawable) -> alpha=0x"
                        + Integer.toHexString(targetAlpha) + " mode="
                        + (isNight(context) ? "dark" : "light") + " pass=" + pass, null);
            }
        } catch (Throwable error) {
            write(Log.WARN, "Cannot adjust PrivacySafety preference decoration", error);
        }
    }

    private static Object findPrivacyPreferenceDecoration(View view) {
        View recycler = findRecyclerView(view);
        if (recycler == null) return null;
        Object value = readField(recycler, "mItemDecorations");
        if (!(value instanceof List)) return null;
        for (Object decoration : (List<?>) value) {
            if (decoration != null && PREFERENCE_FRAME_DECORATION.equals(
                    decoration.getClass().getName())) return decoration;
        }
        return null;
    }

    private static View findRecyclerView(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.getClass().getName().contains("RecyclerView")) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue through inherited RecyclerView types.
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static String resourceName(Context context, int id) {
        if (id == View.NO_ID) return "none";
        try {
            return context.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void apply(Object decoration, Context context) {
        if (decoration == null || context == null || !PACKAGE_NAME.equals(context.getPackageName())) return;
        if (isNight(context)) {
            write(Log.INFO, "skip dark card.f host=" + hostName(context), null);
            return;
        }
        if (!isConfirmedHost(context)) {
            write(Log.INFO, "skip unconfirmed card.f host=" + hostName(context), null);
            return;
        }
        try {
            // JADX calls this field f44452p to avoid name collisions, but the
            // field name in the running DEX is its original one: `p`.
            Field field = decoration.getClass().getDeclaredField("p");
            field.setAccessible(true);
            Object value = field.get(decoration);
            if (!(value instanceof Drawable)) {
                write(Log.WARN, "card.f.p is "
                        + (value == null ? "null" : value.getClass().getName()), null);
                return;
            }
            Drawable cardBackground = (Drawable) value;
            cardBackground.setAlpha(LIGHT_CARD_ALPHA);
            write(Log.INFO, "light card.f.p=" + cardBackground.getClass().getName()
                    + " -> alpha=0x90 host=" + hostName(context), null);
        } catch (Throwable error) {
            write(Log.WARN, "Cannot adjust card.f.p", error);
        }
    }

    private static boolean isConfirmedHost(Context context) {
        String name = hostName(context);
        return "com.miui.appmanager.AppManagerMainActivity".equals(name)
                || "com.miui.powercenter.PowerMainActivity".equals(name)
                || "com.miui.permcenter.privacycenter.PrivacySafetyActivity".equals(name);
    }

    private static String hostName(Context context) {
        Context current = context;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            String name = current.getClass().getName();
            if (name.startsWith("com.miui.")) return name;
            if (!(current instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return context.getClass().getName();
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void write(int level, String message, Throwable error) {
        if (error == null) module.log(level, "SecurityCenterCards", message);
        else module.log(level, "SecurityCenterCards", message, error);
        if (error == null) Log.println(level, "SecurityCenterCards", message);
        else Log.println(level, "SecurityCenterCards",
                message + " " + error.getClass().getSimpleName());
    }
}
