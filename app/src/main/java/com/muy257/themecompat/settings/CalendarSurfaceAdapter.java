package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Calendar light-page cleanup plus uniform frosted month cards.
 *
 * Both modes clear the neutral fallbacks the ROM paints above the themed
 * window bitmap (Day-page hosts in light, the month list's fallback in both,
 * plus the month sheet's miuix smooth background, the translucent white
 * home_root scrim and the grey top mask in light only), and replace each
 * bottom list row's background with a uniform translucent rounded white
 * (0x48 light, 0x3D dark — the owner's reference values), which also removes
 * the grey gradient band the original LayerDrawable showed once frosted.
 * Light-only clears are restored at night and on the Day tab; dark-only
 * clears are restored in daylight; rows keep their frost across mode
 * switches (re-tinted per mode) and are restored only when the window dies.
 * Because agenda rows rebind at unpredictable times (async event loads), a
 * per-draw guard re-frosts any list row that reaches a frame unfrosted.
 */
final class CalendarSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.android.calendar";
    private static final String ALL_IN_ONE_ACTIVITY =
            "com.android.calendar.homepage.AllInOneActivity";
    private static final String[] LIGHT_DAY_HOST_IDS = {
            "home_root", "desk_container_inflate", "action_bar_stb"
    };
    private static final int LIGHT_CARD_ALPHA = 0x48;
    private static final int NIGHT_CARD_ALPHA = 0x3D;
    private static final float CARD_CORNER_RADIUS_PX = 44f;
    private static final String LOG_TAG = "CalendarSurface";

    private final XposedModule module;
    private final Map<Activity, Session> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Activity, Boolean> scheduled =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile int cachedAgendaListId;
    private volatile int cachedRootListId;
    private volatile int cachedIndicatorLayoutId;
    private volatile int cachedIndicatorContainerId;

    CalendarSurfaceAdapter(XposedModule module) {
        this.module = module;
    }

    void install() {
        try {
            Method draw = View.class.getDeclaredMethod("draw", android.graphics.Canvas.class);
            draw.setAccessible(true);
            module.hook(draw).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (receiver instanceof View) {
                    View view = (View) receiver;
                    if (view.getBackground() != null) {
                        try {
                            enforceRowFrost(view);
                        } catch (Throwable error) {
                            module.log(Log.WARN, LOG_TAG, "draw frost", error);
                        }
                    }
                }
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
            log("per-draw month card guard installed");
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot hook View.draw", error);
        }
        hookAfter(Instrumentation.class, "callActivityOnCreate",
                new Class<?>[]{Activity.class, Bundle.class},
                (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnResume",
                new Class<?>[]{Activity.class},
                (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnStop",
                new Class<?>[]{Activity.class},
                (receiver, args) -> restore((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnDestroy",
                new Class<?>[]{Activity.class},
                (receiver, args) -> restore((Activity) args[0]));
        hookAfter(Activity.class, "onContentChanged", new Class<?>[]{}, (receiver, args) -> {
            if (receiver instanceof Activity) schedule((Activity) receiver);
        });
        hookAfter(Activity.class, "onConfigurationChanged", new Class<?>[]{Configuration.class},
                (receiver, args) -> {
                    if (!(receiver instanceof Activity)) return;
                    schedule((Activity) receiver);
                });
    }

    private boolean isCalendarActivity(Activity activity) {
        return activity != null && PACKAGE_NAME.equals(activity.getPackageName())
                && ALL_IN_ONE_ACTIVITY.equals(activity.getClass().getName())
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static boolean isNight(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void schedule(Activity activity) {
        if (!isCalendarActivity(activity)) return;
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
        if (isCalendarActivity(activity)) apply(activity, pass);
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
            boolean night = isNight(activity);
            if (night) {
                // Light-only clears step aside for dark; the dark list
                // fallback goes instead so frosted rows sit on the artwork.
                restoreLightOnly(activity, decor, session, pass);
                clearNightMonthSurfaces(activity, decor, session, pass);
            } else {
                restoreDarkOnly(session);
                for (String idName : LIGHT_DAY_HOST_IDS) {
                    clearExactHost(activity, decor, idName, pass);
                }
                clearMonthLightSurfaces(activity, decor, session, pass);
            }
            decor.invalidate();
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot clean Calendar surfaces", error);
        } finally {
            session.applying = false;
        }
    }

    private void clearExactHost(Activity activity, View decor, String idName, String pass) {
        int id = activity.getResources().getIdentifier(idName, "id", PACKAGE_NAME);
        View host = id == 0 ? null : decor.findViewById(id);
        if (host == null) {
            if (!"layout".equals(pass)) log(pass + " host=" + idName + " absent");
            return;
        }
        Drawable background = host.getBackground();
        if (!(background instanceof ColorDrawable)) {
            if (!"layout".equals(pass)) {
                log(pass + " retain host=" + idName + " non-colour=" + describe(background));
            }
            return;
        }
        int color = ((ColorDrawable) background).getColor();
        if (!isOpaqueNeutral(color)) {
            if (!"layout".equals(pass)) {
                log(pass + " retain host=" + idName + " non-neutral=" + hex(color));
            }
            return;
        }
        Session session = sessionFor(activity);
        session.saveLight(host, background);
        host.setBackground(null);
        log(pass + " cleared exact light Day host=" + idName + " class="
                + host.getClass().getName() + " frame=" + host.getWidth() + 'x'
                + host.getHeight() + " fallback=" + hex(color));
    }

    /**
     * Month-tab light clears: the translucent white scrim on home_root, the
     * miuix smooth sheet, the grey top mask strip, and the month list's
     * #F7F7F7 fallback.  Each is saved once and restored off the month tab,
     * at night, and on stop.
     */
    private void clearMonthLightSurfaces(Activity activity, View decor, Session session,
                                         String pass) {
        int homeRootId = activity.getResources().getIdentifier("home_root", "id", PACKAGE_NAME);
        View homeRoot = homeRootId == 0 ? null : decor.findViewById(homeRootId);
        Drawable scrim = homeRoot == null ? null : homeRoot.getBackground();
        if (scrim instanceof ColorDrawable) {
            int color = ((ColorDrawable) scrim).getColor();
            int alpha = Color.alpha(color);
            int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
            int min = Math.min(red, Math.min(green, blue));
            int max = Math.max(red, Math.max(green, blue));
            if (alpha > 0 && alpha < 255 && max - min <= 24) {
                session.saveLight(homeRoot, scrim);
                homeRoot.setBackground(null);
                log(pass + " cleared home_root scrim=" + hex(color));
            }
        }
        int swipeId = activity.getResources().getIdentifier("swipeLayout", "id", PACKAGE_NAME);
        View swipe = swipeId == 0 ? null : decor.findViewById(swipeId);
        Drawable sheet = swipe == null ? null : swipe.getBackground();
        if (sheet != null && sheet.getClass().getName().startsWith("miuix.smooth")) {
            session.saveLight(swipe, sheet);
            swipe.setBackground(null);
            log(pass + " cleared month sheet id=swipeLayout class=" + sheet.getClass().getName());
        }
        int maskId = activity.getResources().getIdentifier("month_top_mask_view",
                "id", PACKAGE_NAME);
        View mask = maskId == 0 ? null : decor.findViewById(maskId);
        if (mask != null && mask.getVisibility() != View.INVISIBLE) {
            mask.setVisibility(View.INVISIBLE);
            log(pass + " hid month_top_mask_view (grey strip)");
        }
        // The month list keeps the same neutral #F7F7F7 fallback the Day page
        // carries on its hosts; clear it so the frosted rows sit on artwork.
        clearExactHost(activity, decor, "list", pass);
        // The empty trailing week-row cells paint opaque white over the
        // artwork at the sheet's bottom edge; clear those too.
        clearWhiteWeekCells(decor, session, pass, 0);
    }

    /**
     * Night counterpart of the light clears: the month list keeps an opaque
     * dark fallback at night which would sit between the frosted rows and
     * the dark artwork, and the miuix smooth sheet is the opaque backdrop
     * behind the agenda once the month grid is collapsed.  Both are cleared
     * into the dark map so returning to daylight puts them back.
     */
    private void clearNightMonthSurfaces(Activity activity, View decor, Session session,
                                         String pass) {
        int listId = activity.getResources().getIdentifier("list", "id", PACKAGE_NAME);
        View list = listId == 0 ? null : decor.findViewById(listId);
        if (list != null) {
            Drawable background = list.getBackground();
            if (background instanceof ColorDrawable) {
                int color = ((ColorDrawable) background).getColor();
                if (Color.alpha(color) != 0 && isOpaqueNeutral(color)) {
                    session.saveDark(list, background);
                    list.setBackground(null);
                    log(pass + " cleared night list fallback=" + hex(color));
                }
            } else if (background != null) {
                log(pass + " night retain host=list non-colour=" + describe(background));
            }
        }
        int swipeId = activity.getResources().getIdentifier("swipeLayout", "id", PACKAGE_NAME);
        View swipe = swipeId == 0 ? null : decor.findViewById(swipeId);
        Drawable sheet = swipe == null ? null : swipe.getBackground();
        if (sheet != null && sheet.getClass().getName().startsWith("miuix.smooth")) {
            session.saveDark(swipe, sheet);
            swipe.setBackground(null);
            log(pass + " cleared night sheet id=swipeLayout class=" + sheet.getClass().getName());
        }
    }

    private void restoreDarkOnly(Session session) {
        for (Map.Entry<View, Drawable> entry : session.darkOriginals.entrySet()) {
            if (entry.getKey().getBackground() == null) {
                entry.getKey().setBackground(entry.getValue());
            }
        }
    }

    private void clearWhiteWeekCells(View view, Session session, String pass, int depth) {
        if (view == null || depth > 30) return;
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        Drawable background = view.getBackground();
        if (background != null && xy[1] > 1100 && xy[1] < 1450
                && view.getWidth() >= 140 && isOpaqueNearWhiteDrawable(background)) {
            session.saveLight(view, background);
            view.setBackground(null);
            log(pass + " cleared white month cell class=" + view.getClass().getName()
                    + " xy=" + xy[0] + ',' + xy[1] + " frame=" + view.getWidth() + 'x'
                    + view.getHeight());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                clearWhiteWeekCells(group.getChildAt(index), session, pass, depth + 1);
            }
        }
    }

    private static boolean isOpaqueNearWhiteDrawable(Drawable background) {
        if (background instanceof ColorDrawable) {
            return isOpaqueNeutral(((ColorDrawable) background).getColor());
        }
        if (background instanceof GradientDrawable) {
            android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
            if (colors == null) return false;
            int color = colors.getDefaultColor();
            if (Color.alpha(color) != 255) return false;
            int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
            int min = Math.min(red, Math.min(green, blue));
            int max = Math.max(red, Math.max(green, blue));
            return min >= 224 && max - min <= 24;
        }
        return false;
    }

    /**
     * Puts back every light-mode-only clear (Day hosts, scrim, sheet, mask)
     * so the native dark surfaces — or the accepted Day-tab look — show.
     * Row frosts are intentionally kept; they carry the mode's own alpha.
     */
    private void restoreLightOnly(Activity activity, View decor, Session session, String pass) {
        for (Map.Entry<View, Drawable> entry : session.lightOriginals.entrySet()) {
            if (entry.getKey().getBackground() == null) {
                entry.getKey().setBackground(entry.getValue());
            }
        }
        int maskId = activity.getResources().getIdentifier("month_top_mask_view",
                "id", PACKAGE_NAME);
        View mask = maskId == 0 ? null : decor.findViewById(maskId);
        if (mask != null && mask.getVisibility() == View.INVISIBLE) {
            mask.setVisibility(View.VISIBLE);
        }
        log(pass + " restored light-only clears (" + session.lightOriginals.size() + ")");
    }

    private void restore(Activity activity) {
        Session session;
        synchronized (sessions) {
            session = sessions.remove(activity);
        }
        if (session == null) return;
        removeLiveRescan(session);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        for (Map.Entry<View, Drawable> entry : session.lightOriginals.entrySet()) {
            try {
                entry.getKey().setBackground(entry.getValue());
            } catch (Throwable ignored) {
                // The window may already be torn down.
            }
        }
        for (Map.Entry<View, Drawable> entry : session.darkOriginals.entrySet()) {
            try {
                entry.getKey().setBackground(entry.getValue());
            } catch (Throwable ignored) {
                // The window may already be torn down.
            }
        }
        int maskId = activity.getResources().getIdentifier("month_top_mask_view",
                "id", PACKAGE_NAME);
        View mask = maskId == 0 || decor == null ? null : decor.findViewById(maskId);
        if (mask != null) mask.setVisibility(View.VISIBLE);
        for (Map.Entry<View, Drawable> entry : session.rowOriginals.entrySet()) {
            try {
                entry.getKey().setBackground(entry.getValue());
            } catch (Throwable ignored) {
                // The window may already be torn down.
            }
        }
    }

    /**
     * Per-draw guard for the agenda area: rows bind at unpredictable times
     * (the async event query rebinds them after every layout pass has run),
     * so like the other card adapters the frost is enforced on the frame.
     * The agenda tree nests — the month list's direct children are the lunar
     * header (id root) and an outer container (also id root) that wraps the
     * nested event RecyclerView; the event cards themselves (id root_list)
     * live inside that nesting.  Event cards match by id, the lunar header
     * by being a direct child of the month list that does not wrap the
     * nested list, and the container itself only gets its own fill dropped.
     * Inside the agenda tree every opaque section fill (the lunar title
     * strip, the event-card section containers) is cleared as well — those
     * sit on top of the row frost and turn it back into a solid block,
     * which is exactly the "two stacked cards" the owner called out.
     */
    private void enforceRowFrost(View view) {
        Drawable background = view.getBackground();
        boolean night = (view.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int id = view.getId();
        if ((id != 0 && id == rootListId(view))
                || (view.getParent() instanceof ViewGroup
                && ((ViewGroup) view.getParent()).getId() == agendaListId(view)
                && !wrapsChildList(view))) {
            frostRow(view, background, night);
            return;
        }
        if (isBottomTabBar(view)) {
            clearBottomBarFill(view, background, night);
            return;
        }
        if (isIndicatorStrip(view)) {
            clearIndicatorStrip(view, background, night);
            return;
        }
        if (!insideAgendaList(view)) return;
        if (isFillLikeDrawable(unwrapCurrent(background))) {
            clearInteriorFill(view, background, night);
        } else if (background instanceof LayerDrawable) {
            stripInteriorLayers(view, background);
        } else {
            logRetainedInterior(view, background);
        }
    }

    /**
     * The bottom tab bar paints an opaque black strip at night while
     * daylight leaves it clear over the artwork (owner-measured: no veil in
     * either mode).  It carries no resource id, so it is matched
     * geometrically — a full-width, short container pinned to the window
     * bottom — and its dark fill is dropped like the card section fills.
     */
    private static boolean isBottomTabBar(View view) {
        if (!(view instanceof ViewGroup)) return false;
        int width = view.getWidth(), height = view.getHeight();
        if (width < 1000 || height < 120 || height > 280) return false;
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        return xy[1] >= 2400;
    }

    private void clearBottomBarFill(View view, Drawable background, boolean night) {
        if (!night) return;
        boolean fillLike = isFillLikeDrawable(unwrapCurrent(background));
        if (!fillLike && background instanceof LayerDrawable) {
            Drawable mutated = background.mutate();
            if (mutated instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) mutated;
                for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                    Drawable layer = layers.getDrawable(index);
                    if (layer != null && isFillLikeDrawable(unwrapCurrent(layer))) {
                        fillLike = true;
                        break;
                    }
                }
            }
        }
        if (!fillLike) {
            logRetainedInterior(view, background, "retain bottom bar class=");
            return;
        }
        Activity activity = activityOf(view);
        if (activity != null) sessionFor(activity).saveDark(view, background);
        view.setBackground(null);
        log("cleared bottom tab bar fill night");
    }

    /**
     * The strip between the month grid and the agenda cards is the drag
     * indicator: layout_indicator.xml gives indicator_layout a solid
     * motion_container_bg_color fill and indicator_container an opaque
     * vector (white in daylight, black at night) — that is the stubborn
     * "grey band" the owner kept seeing, not a canvas fill.  Both carry no
     * content of their own, so both backgrounds go in either mode and only
     * the handle pill stays.
     */
    private boolean isIndicatorStrip(View view) {
        int id = view.getId();
        if (id == 0) return false;
        int layout = cachedIndicatorLayoutId;
        if (layout == 0) {
            layout = view.getResources().getIdentifier("indicator_layout", "id", PACKAGE_NAME);
            cachedIndicatorLayoutId = layout;
        }
        if (id == layout) return true;
        int container = cachedIndicatorContainerId;
        if (container == 0) {
            container = view.getResources().getIdentifier("indicator_container", "id", PACKAGE_NAME);
            cachedIndicatorContainerId = container;
        }
        return id == container;
    }

    private void clearIndicatorStrip(View view, Drawable background, boolean night) {
        Activity activity = activityOf(view);
        if (activity != null) {
            Session session = sessionFor(activity);
            if (night) session.saveDark(view, background);
            else session.saveLight(view, background);
        }
        view.setBackground(null);
        log("cleared indicator strip bg class=" + view.getClass().getName()
                + (night ? " night" : ""));
    }

    /**
     * The lunar title strip and the event-card backdrops do not use plain
     * colour fills — they carry LayerDrawables (fill + stroke/ripple layers)
     * or StateListDrawables.  Zeroing the alpha of just the opaque fill
     * layers keeps corner strokes and press ripples while letting the row
     * frost and the artwork show through.
     */
    private void stripInteriorLayers(View view, Drawable background) {
        Drawable mutated = background.mutate();
        if (!(mutated instanceof LayerDrawable)) return;
        LayerDrawable layers = (LayerDrawable) mutated;
        boolean changed = false;
        for (int index = 0; index < layers.getNumberOfLayers(); index++) {
            Drawable layer = layers.getDrawable(index);
            if (layer == null || layer.getAlpha() == 0) continue;
            if (isFillLikeDrawable(unwrapCurrent(layer))) {
                layer.setAlpha(0);
                changed = true;
            }
        }
        if (!changed) {
            logRetainedInterior(view, background);
            return;
        }
        view.invalidate();
        logRetainedInterior(view, background, "dimmed agenda section layers class=");
    }

    /** Peels state/inset wrappers so the fill underneath can be inspected. */
    private static Drawable unwrapCurrent(Drawable drawable) {
        Drawable current = drawable;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (current instanceof StateListDrawable) {
                current = ((StateListDrawable) current).getCurrent();
            } else if (current instanceof InsetDrawable) {
                current = ((InsetDrawable) current).getDrawable();
            } else {
                break;
            }
        }
        return current;
    }

    private void frostRow(View view, Drawable background, boolean night) {
        int wanted = Color.argb(night ? CardAlpha.dark() : CardAlpha.light(), 255, 255, 255);
        if (isAlreadyFrosted(background, wanted)) return;
        Activity activity = activityOf(view);
        if (activity != null) sessionFor(activity).saveRow(view, background);
        GradientDrawable frost = new GradientDrawable();
        frost.setShape(GradientDrawable.RECTANGLE);
        frost.setCornerRadius(CARD_CORNER_RADIUS_PX);
        frost.setColor(wanted);
        view.setBackground(frost);
        log("draw frost row class=" + view.getClass().getName()
                + (night ? " night" : "") + " argb=" + hex(wanted));
    }

    private void clearInteriorFill(View view, Drawable background, boolean night) {
        Activity activity = activityOf(view);
        if (activity != null) {
            Session session = sessionFor(activity);
            if (night) session.saveDark(view, background);
            else session.saveLight(view, background);
        }
        view.setBackground(null);
        log("cleared agenda interior fill class=" + view.getClass().getName()
                + (night ? " night" : ""));
    }

    private boolean insideAgendaList(View view) {
        Object node = view.getParent();
        for (int depth = 0; depth < 14 && node instanceof ViewGroup; depth++) {
            View group = (View) node;
            if (group.getId() != 0 && group.getId() == agendaListId(group)) return true;
            node = group.getParent();
        }
        return false;
    }

    /**
     * True for the opaque neutral fills that read as card sections: dark
     * strips and white sheets.  Mid-grey or coloured fills (the yi/ji chips,
     * countdown badges) are design, not blocks, and survive.
     */
    private static boolean isFillLikeDrawable(Drawable background) {
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
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        int min = Math.min(red, Math.min(green, blue));
        int max = Math.max(red, Math.max(green, blue));
        if (max - min > 24) return false;
        return max <= 80 || min >= 224;
    }

    private final Map<View, Boolean> retainLogged =
            Collections.synchronizedMap(new WeakHashMap<>());

    private void logRetainedInterior(View view, Drawable background) {
        logRetainedInterior(view, background, "retain agenda interior class=");
    }

    private void logRetainedInterior(View view, Drawable background, String prefix) {
        synchronized (retainLogged) {
            if (retainLogged.put(view, Boolean.TRUE) != null) return;
        }
        log(prefix + view.getClass().getName() + " bg=" + describe(background));
    }

    private int agendaListId(View view) {
        int id = cachedAgendaListId;
        if (id == 0) {
            id = view.getResources().getIdentifier("list", "id", PACKAGE_NAME);
            cachedAgendaListId = id;
        }
        return id;
    }

    private int rootListId(View view) {
        int id = cachedRootListId;
        if (id == 0) {
            id = view.getResources().getIdentifier("root_list", "id", PACKAGE_NAME);
            cachedRootListId = id;
        }
        return id;
    }

    private static boolean wrapsChildList(View view) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getClass().getName().endsWith("RecyclerView")) {
                return true;
            }
        }
        return false;
    }

    private static Activity activityOf(View view) {
        Context context = view.getContext();
        for (int depth = 0; depth < 6 && context instanceof ContextWrapper; depth++) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static boolean isAlreadyFrosted(Drawable background, int wanted) {
        if (!(background instanceof GradientDrawable)) return false;
        android.content.res.ColorStateList colors = ((GradientDrawable) background).getColor();
        return colors != null && colors.getDefaultColor() == wanted;
    }

    private static boolean isOpaqueNeutral(int color) {
        if (Color.alpha(color) != 255) return false;
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
    }

    private void ensureLiveRescan(Activity activity, View decor) {
        Session session = sessionFor(activity);
        if (session.decor == decor && session.layoutListener != null) return;
        removeLiveRescan(session);
        session.decor = decor;
        session.layoutListener = () -> {
            if (!isCalendarActivity(activity) || session.applying || session.rescanQueued) return;
            session.rescanQueued = true;
            decor.post(() -> {
                session.rescanQueued = false;
                if (isCalendarActivity(activity) && session.decor == decor) {
                    apply(activity, "layout");
                }
            });
        };
        try {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(session.layoutListener);
        } catch (Throwable error) {
            session.layoutListener = null;
            module.log(Log.WARN, LOG_TAG, "Cannot observe Calendar hierarchy", error);
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

    private void hookAfter(Class<?> type, String name, Class<?>[] parameters, After callback) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try {
                    callback.after(chain.getThisObject(), args);
                } catch (Throwable error) {
                    module.log(Log.WARN, LOG_TAG, "Lifecycle " + name, error);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After { void after(Object receiver, Object[] args) throws Throwable; }

    private static String describe(Drawable drawable) {
        if (drawable == null) return "null";
        if (drawable instanceof ColorDrawable) {
            return "ColorDrawable(" + hex(((ColorDrawable) drawable).getColor()) + ')';
        }
        return drawable.getClass().getName() + '(' + drawable.getIntrinsicWidth() + 'x'
                + drawable.getIntrinsicHeight() + ')';
    }

    private static String hex(int color) { return String.format("#%08X", color); }

    private void log(String text) { module.log(Log.INFO, LOG_TAG, text); }

    private static final class Session {
        final Map<View, Drawable> lightOriginals = new IdentityHashMap<>();
        final Map<View, Drawable> darkOriginals = new IdentityHashMap<>();
        final Map<View, Drawable> rowOriginals = new IdentityHashMap<>();
        View decor;
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean rescanQueued;
        boolean applying;

        void saveLight(View view, Drawable background) {
            if (!lightOriginals.containsKey(view)) lightOriginals.put(view, background);
        }

        void saveDark(View view, Drawable background) {
            if (!darkOriginals.containsKey(view)) darkOriginals.put(view, background);
        }

        void saveRow(View view, Drawable background) {
            if (!rowOriginals.containsKey(view)) rowOriginals.put(view, background);
        }
    }
}
