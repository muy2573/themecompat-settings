package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Bug 4, control-center artwork only (owner dropped the notification-shade
 * picture after the follow attempts): the theme ships
 * qs_control_bg.9.png — byte-identical to notification_panel_window_bg.9.png —
 * but HyperOS 3 never draws that legacy resource, so the picture is inserted
 * as the first child of the control center's content_container and rides the
 * native panel motion from there.
 *
 * Owner visibility spec: the picture shows only while the control-center panel
 * rests fully expanded (it pops in statically, no appear animation); the
 * instant the panel leaves that state — any vertical movement — it fades out
 * very quickly and is then GONE, so nothing lingers over the home screen; and
 * horizontal page switches never touch it.  The lock screen never shows it.
 */
final class SystemUiShadeArtAdapter {
    private static final String TAG = "ShadeArt";
    private static final String HOST_PACKAGE = "com.android.systemui";
    private static final String STATE_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.StatusBarStateControllerImpl";
    private static final String CC_CONTAINER_CLASS =
            "com.miui.systemui.controlcenter.container.ControlCenterContainer";
    private static final String CC_STATE_LISTENER_CLASS =
            "com.miui.systemui.controlcenter.container.ControlCenterContainerController$onExpandChangeListener$1";
    private static final String CC_EXPAND_STATE_CLASS =
            "com.android.systemui.plugins.miui.controlcenter.ControlCenterContent$ExpandState";
    private static final String SHADE_WINDOW_CLASS =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String SWITCH_CONTROLLER_CLASS =
            "com.miui.systemui.shade.ShadeSwitchControllerImpl";
    private static final String CONTENT_CONTAINER_ID = "content_container";
    private static final String CC_LAYER_TAG = "themecompat:cc_art";
    private static final String DAY_PATH =
            "res/drawable-xxhdpi/notification_panel_window_bg.9.png";
    private static final String NIGHT_PATH =
            "nightmode/res/drawable-xxhdpi/notification_panel_window_bg.9.png";

    /** StatusBarState.KEYGUARD: the plain lock screen must never show the art. */
    private static final int KEYGUARD_STATE = 1;
    /**
     * Vertical fade window: the art fades in over roughly the last quarter of
     * the pull (owner: start fading while the pull is finishing, not after it
     * already settled) and collapse still drops the view instantly.
     */
    private static final float FADE_IN_START = 0.75f;

    private final XposedModule module;
    private final ClassLoader hostLoader;

    private static ImageView ccArt;
    /** 1 = notification page, the resting page — a safe default that can
     *  never flash the art the moment the shade is pulled down. */
    private static volatile float switchProgress = 1f;
    private static final java.lang.ref.WeakReference<Object> NO_CONTROLLER =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Object> switchController = NO_CONTROLLER;
    private static Method switchProgressGetter;
    private static volatile float lastLoggedProgress = -1f;
    private static volatile int statusBarState = KEYGUARD_STATE;
    /** Per-frame vertical panel fraction, 0 collapsed .. 1 fully expanded. */
    private static volatile float expandFraction;
    /** Discrete fallback: the CC-side EXPANDED state fires reliably even on
     *  builds whose fraction dispatch never covers the CC-open path. */
    private static volatile boolean ccStateExpanded;
    /** True while the panel reports a slide in progress (probe only). */
    private static volatile boolean ccMoving;
    private static int probeTick;
    private static final Set<String> loggedCcStates = new HashSet<>();

    SystemUiShadeArtAdapter(XposedModule module, ClassLoader hostLoader) {
        this.module = module;
        this.hostLoader = hostLoader;
    }

    void install() {
        hookStatusBarState();
        hookControlCenterContainer();
        hookControlCenterExpandState();
        hookShadeExpandFraction();
        hookShadeSwitchProgress();
    }

    // ---------------------------------------------------- visibility gates

    /**
     * Fires per frame during horizontal page switches; each tick re-evaluates
     * the CC picture so it rides its page and never lingers on the other one.
     */
    private void hookShadeSwitchProgress() {
        try {
            Class<?> type = Class.forName(SWITCH_CONTROLLER_CLASS, false, hostLoader);
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed(chain.getArgs().toArray(new Object[0]));
                    try {
                        switchController = new java.lang.ref.WeakReference<>(chain.getThisObject());
                        switchProgressGetter = chain.getThisObject().getClass()
                                .getMethod("getSwitchProgress");
                        switchProgressGetter.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            }
            Method method = type.getDeclaredMethod("performProgressChanged");
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed(chain.getArgs().toArray(new Object[0]));
                try {
                    Object receiver = chain.getThisObject();
                    if (receiver != null && switchProgressGetter != null) {
                        switchProgress = (Float) switchProgressGetter.invoke(receiver);
                    }
                    float logged = switchProgress;
                    if (Math.abs(logged - lastLoggedProgress) > 0.1f) {
                        lastLoggedProgress = logged;
                        module.log(android.util.Log.INFO, TAG,
                                "switch progress=" + logged);
                    }
                    applyCcVisibility();
                    scheduleReassert();
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.log(android.util.Log.INFO, TAG, "switch-progress hook installed");
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "switch-progress hook failed", error);
        }
    }

    private void hookStatusBarState() {
        try {
            Class<?> type = Class.forName(STATE_CONTROLLER_CLASS, false, hostLoader);
            Method method = type.getDeclaredMethod("setState", int.class, boolean.class);
            module.hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                int state = (Integer) args.get(0);
                Object result = chain.proceed(args.toArray(new Object[0]));
                try {
                    if (state != statusBarState) {
                        module.log(android.util.Log.INFO, TAG,
                                "status state " + statusBarState + " -> " + state);
                        statusBarState = state;
                        applyCcVisibility();
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.log(android.util.Log.INFO, TAG, "status-state hook installed");
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "status-state hook failed", error);
        }
    }

    /**
     * Discrete states only feed bookkeeping now (the collapse reset); the
     * actual show/hide timing is per-frame from the expansion fraction.
     */
    private void hookControlCenterExpandState() {
        try {
            Class<?> stateType = Class.forName(CC_EXPAND_STATE_CLASS, false, hostLoader);
            Class<?> listenerType = Class.forName(CC_STATE_LISTENER_CLASS, false, hostLoader);
            Method method = listenerType.getDeclaredMethod("onExpandStateChanged", stateType);
            module.hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Object result = chain.proceed(args.toArray(new Object[0]));
                try {
                    Object state = args.get(0);
                    String name = state == null ? "null" : state.toString();
                    if (loggedCcStates.add(name)) {
                        module.log(android.util.Log.INFO, TAG, "cc expand state=" + name);
                    }
                    if (name.contains("COLLAPSED")) {
                        // Collapsed always rests back on the notification page.
                        switchProgress = 1f;
                    }
                    // A drag down from the open panel is both "small wobble"
                    // and "collapse start" — the same event, with the intent
                    // only knowable from how it ends.  Hide immediately
                    // either way; if it settles back open quickly, the art
                    // re-enters with the same quiet fade.
                    boolean expanded = name.contains("EXPANDED")
                            && !name.contains("EXPANDING");
                    boolean collapsed = name.contains("COLLAPSED");
                    if (expanded) {
                        if (!ccStateExpanded) {
                            ccStateExpanded = true;
                            module.log(android.util.Log.INFO, TAG, "cc gate page="
                                    + currentSwitchProgress() + " expanded=true");
                            applyCcVisibility();
                        }
                    } else if (collapsed) {
                        if (ccStateExpanded) {
                            ccStateExpanded = false;
                            module.log(android.util.Log.INFO, TAG, "cc gate expanded=false");
                            applyCcVisibility();
                        }
                    } else if (ccStateExpanded) {
                        // Drag or collapse beginning while open.
                        ccStateExpanded = false;
                        applyCcVisibility();
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.log(android.util.Log.INFO, TAG, "cc expand-state hook installed");
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "cc expand-state hook failed", error);
        }
    }

    /**
     * Per-frame vertical fraction of the unified shade (0 collapsed, 1 fully
     * expanded).  The dispatch is private and its exact name varies across
     * HyperOS builds, so hook every declared method of the manager whose
     * first parameter is a float and whose name mentions expansion.
     */
    private void hookShadeExpandFraction() {
        int hooks = 0;
        try {
            Class<?> type = Class.forName(
                    "com.android.systemui.shade.ShadeExpansionStateManager", false, hostLoader);
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                for (Method method : cursor.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 0 || parameters[0] != float.class) continue;
                    if (!method.getName().toLowerCase().contains("xpansion")) continue;
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed(chain.getArgs().toArray(new Object[0]));
                        try {
                            expandFraction = (Float) chain.getArgs().get(0);
                            applyCcVisibility();
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                    hooks++;
                    module.log(android.util.Log.INFO, TAG,
                            "expand-fraction method=" + cursor.getName()
                                    + '#' + method.getName());
                }
            }
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "expand-fraction hook failed", error);
        }
        module.log(android.util.Log.INFO, TAG,
                "expand-fraction hooks installed count=" + hooks);
    }

    // ------------------------------------------------------- visibility core

    // The HyperOS shade opens through a window-level animation, so view
    // coordinates never move during the slide and no view-space probe can
    // time a fade to it.  The art therefore does not exist at all until the
    // panel reports fully expanded, then eases in with a short alpha fade;
    // leaving the expanded state — collapse, page switch, keyguard — hides
    // it instantly.

    private static final long FADE_IN_MILLIS = 200L;

    private void applyCcVisibility() {
        ImageView art = ccArt;
        if (art == null) return;
        // Measured: progress 0 rests on the control-center page, 1 on the
        // notification page; the picture must never appear on the other page.
        // Read live instead of trusting the last cached tick — the first
        // switch tick can lag the EXPANDING event by a frame, which is long
        // enough to flash the art on the notification page.
        boolean onCcPage = currentSwitchProgress() <= 0.5f;
        boolean shown = statusBarState != KEYGUARD_STATE
                && ccStateExpanded && onCcPage;
        if (!shown) {
            art.animate().cancel();
            art.setAlpha(1f);
            art.setTranslationY(0f);
            art.setVisibility(View.GONE);
            return;
        }
        if (art.getVisibility() == View.VISIBLE) {
            // Already easing in or settled — leave a running fade alone.
            return;
        }
        // Every entrance is the same quiet flat fade — no rise, no special
        // case for page switches or drag recoveries.
        art.setAlpha(0f);
        art.setVisibility(View.VISIBLE);
        art.animate().alpha(1f).setDuration(FADE_IN_MILLIS).start();
    }

    /** Live progress read: the cached tick is only a fallback. */
    private static float currentSwitchProgress() {
        Object controller = switchController.get();
        Method getter = switchProgressGetter;
        if (controller != null && getter != null) {
            try {
                float value = (Float) getter.invoke(controller);
                switchProgress = value;
                return value;
            } catch (Throwable ignored) {
            }
        }
        return switchProgress;
    }

    /**
     * The final switch-progress tick can land on a transient value, and no
     * further ticks follow once the page settles — a swipe could then leave
     * the art hidden forever.  One delayed re-check after each tick closes
     * that window.
     */
    private final Runnable reassertVisible = new Runnable() {
        @Override
        public void run() {
            applyCcVisibility();
        }
    };

    private void scheduleReassert() {
        ImageView art = ccArt;
        if (art == null) return;
        art.removeCallbacks(reassertVisible);
        art.postDelayed(reassertVisible, 300L);
    }

    // --------------------------------------------- control-center insertion
    /**
     * Audit-proven anchor: the ControlCenterContainer hosts the CC's
     * content_container; the picture becomes its first child and rides the
     * native panel motion exactly like the card list above it, including
     * horizontal page switches.  Inflation assigns ids after constructors
     * return, hence the deferred retries.
     */
    private void hookControlCenterContainer() {
        try {
            Class<?> type = Class.forName(CC_CONTAINER_CLASS, false, hostLoader);
            int hooks = 0;
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed(chain.getArgs().toArray(new Object[0]));
                    Object instance = chain.getThisObject();
                    if (instance instanceof ViewGroup) {
                        ViewGroup candidate = (ViewGroup) instance;
                        long[] delays = {350L, 1200L, 3000L};
                        for (long delay : delays) {
                            candidate.postDelayed(() -> {
                                try {
                                    ensureCcLayer(candidate);
                                } catch (Throwable ignored) {
                                }
                            }, delay);
                        }
                    }
                    return result;
                });
                hooks++;
            }
            module.log(android.util.Log.INFO, TAG,
                    "control-center container hooks installed count=" + hooks);
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "control-center hook failed", error);
        }
    }

    private void ensureCcLayer(ViewGroup candidate) {
        if (!isContentContainer(candidate)) return;
        // Drop any stale layer from an earlier pass, and never stack doubles.
        ImageView existing = findCcLayer(candidate);
        if (ccArt != null && ccArt.isAttachedToWindow()) {
            if (existing != null && existing != ccArt) candidate.removeView(existing);
            return;
        }
        Context context = candidate.getContext();
        ImageView view = new ImageView(context);
        view.setTag(CC_LAYER_TAG);
        view.setImageBitmap(loadArtBitmap(context, isNight(context)));
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setVisibility(View.GONE);
        candidate.addView(view, 0,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        ccArt = view;
        module.log(android.util.Log.INFO, TAG, "cc art inserted children="
                + candidate.getChildCount());
        applyCcVisibility();
    }

    /** Package-agnostic id check: inflation may come from either package. */
    private static boolean isContentContainer(ViewGroup view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return false;
        try {
            return CONTENT_CONTAINER_ID.equals(
                    view.getResources().getResourceEntryName(id));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ImageView findCcLayer(ViewGroup host) {
        for (int index = 0; index < host.getChildCount(); index++) {
            View child = host.getChildAt(index);
            if (child instanceof ImageView && CC_LAYER_TAG.equals(child.getTag())) {
                return (ImageView) child;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- artwork

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private Bitmap loadArtBitmap(Context context, boolean night) {
        Bitmap streamed = loadThroughThemeStream(context, night);
        if (streamed != null) return streamed;
        try {
            ApplicationInfo info = module.getModuleApplicationInfo();
            Context moduleContext = context.createPackageContext(
                    info.packageName, Context.CONTEXT_IGNORE_SECURITY);
            try (InputStream raw = moduleContext.getResources().openRawResource(
                    moduleContext.getResources().getIdentifier(
                            "notification_panel_window_bg", "raw", info.packageName))) {
                return BitmapFactory.decodeStream(raw);
            }
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "bundled art unavailable", error);
            return null;
        }
    }

    /**
     * Reads the artwork straight from the applied SystemUI theme module through
     * HyperOS' own ThemeResources stream API, so a future author update to the
     * picture is picked up without rebuilding this module.
     */
    private Bitmap loadThroughThemeStream(Context context, boolean night) {
        try {
            Class<?> resourcesType = Class.forName("android.content.res.MiuiResources", false, null);
            Object resources = context.getResources();
            if (!resourcesType.isInstance(resources)) {
                module.log(android.util.Log.WARN, TAG, "Host resources are not MiuiResources: "
                        + resources.getClass().getName());
                return null;
            }
            Class<?> packageType = Class.forName("miui.content.res.ThemeResourcesPackage", false, null);
            Method factory = packageType.getMethod("getThemeResources", resourcesType, String.class);
            Object themeResources = factory.invoke(null, resources, HOST_PACKAGE);
            if (themeResources == null) return null;

            Method checkUpdate = findMethod(themeResources.getClass(), "checkUpdate");
            checkUpdate.invoke(themeResources);
            Method modeSetter = findMethod(themeResources.getClass(), "setNightModeEnable",
                    Boolean.TYPE);
            modeSetter.invoke(themeResources, night);
            Method streamMethod = findMethod(themeResources.getClass(), "getThemeStream",
                    String.class, long[].class);
            Object value = streamMethod.invoke(themeResources, night ? NIGHT_PATH : DAY_PATH,
                    new long[1]);
            if (!(value instanceof InputStream)) {
                module.log(android.util.Log.INFO, TAG,
                        "theme stream absent night=" + night);
                return null;
            }
            try (InputStream stream = (InputStream) value) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (Throwable error) {
            module.log(android.util.Log.WARN, TAG, "theme stream unavailable", error);
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the ThemeResources implementation hierarchy.
            }
        }
        throw new NoSuchMethodException(type.getName() + '#' + name);
    }
}
