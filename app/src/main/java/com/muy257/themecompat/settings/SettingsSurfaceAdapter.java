package com.muy257.themecompat.settings;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Settings-side, renderer-agnostic cleanup directly ported from the verified
 * HyperBackground model. It never creates a wallpaper view or reads a background source.
 * It only clears the same host/page surfaces so any existing renderer can remain underneath.
 */
final class SettingsSurfaceAdapter {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String MILINK_PACKAGE = "com.milink.service";
    private static final String PHONE_PACKAGE = "com.android.phone";
    private static final String ACCOUNT_PACKAGE = "com.xiaomi.account";
    private static final String THEME_MANAGER_PACKAGE = "com.android.thememanager";
    private static final String HOME_PACKAGE = "com.miui.home";
    private static final String SECURITY_CENTER_PACKAGE = "com.miui.securitycenter";
    private static final String POWER_KEEPER_PACKAGE = "com.miui.powerkeeper";
    private static final String MI_SETTINGS_PACKAGE = "com.xiaomi.misettings";
    private static final String CONTACTS_PACKAGE = "com.android.contacts";
    // These packages were measured with the read-only v0.25.5 audit.  They
    // are intentionally not broad process allow-lists: each is restricted to
    // one concrete, full-page system settings activity below.
    private static final String CONTENT_EXTENSION_PACKAGE = "com.miui.contentextension";
    private static final String MISHARE_PACKAGE = "com.miui.mishare.connectivity";
    private static final String CLEAN_MASTER_PACKAGE = "com.miui.cleanmaster";
    private static final String COMPASS_PACKAGE = "com.miui.compass";
    private static final String AIASST_SERVICE_PACKAGE = "com.xiaomi.aiasst.service";
    private static final String MISOUND_PACKAGE = "com.miui.misound";
    private static final String CLOUD_SERVICE_PACKAGE = "com.miui.cloudservice";
    private static final String AI_VISION_PACKAGE = "com.xiaomi.aiasst.vision";
    private static final String UPDATER_PACKAGE = "com.android.updater";
    private static final String DOWNLOADS_UI_PACKAGE = "com.android.providers.downloads.ui";
    private static final String GREENGUARD_PACKAGE = "com.miui.greenguard";
    private static final String SETTINGS_HOME = "com.android.settings.MiuiSettings";
    private static final long RESCAN_WINDOW_MS = 2500L;
    /*
     * These are not cleanup candidates.  Xiaomi's Settings theme fallback may attach
     * settings_window_bg_dark / miuix_appcompat_settings_window_bg_dark to either the
     * content root or the nested header root.  They must survive for a normal .mtz
     * wallpaper to be visible when no separate renderer is installed.
     */
    private static final String[] THEME_BACKGROUND_SURFACES = {
            "nestedheaderlayout", "nested_header_layout"
    };
    private static final String[] COMMON_OVERLAY_SURFACES = {
            "scroll_headers", "main_content"
    };
    private static final String[] SECONDARY_SURFACES = {
            "prefs_container", "preference_recyclerview", "recycler_view", "content",
            "content_view", "content_wrapper", "action_bar_activity_content", "area_content",
            "auto_content"
    };
    private static final String[] THEME_DRAWABLE_ALIASES = {
            "settings_window_bg_dark", "window_bg_dark",
            "miuix_appcompat_settings_window_bg_dark",
            "miuix_preference_card_page_background_dark"
    };
    private static final String THEME_PAGE_BACKGROUND =
            "miuix_preference_card_page_background_";
    /*
     * Hosted packages whose adapted .mtz (MtzCompatibilityPatcher) ships
     * nightmode window aliases borrowed from their audited sibling artwork.
     * At night these try their own dark-name resources before the shared
     * Settings dark surface.
     */
    private static final Set<String> NIGHT_OWN_DARK_PACKAGES = setOf(
            AI_VISION_PACKAGE, CLEAN_MASTER_PACKAGE, COMPASS_PACKAGE, AIASST_SERVICE_PACKAGE,
            SECURITY_CENTER_PACKAGE);

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private final XposedModule module;
    private final Map<Activity, LayerSession> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Activity> scheduled =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Activity> modeRetryPending =
            Collections.newSetFromMap(new WeakHashMap<>());

    SettingsSurfaceAdapter(XposedModule module, ClassLoader targetClassLoader) {
        this.module = module;
    }

    /**
     * Packages whose concrete full-page activities were established by the
     * v0.25.5 resource audit. MiShare is included because both modes resolve
     * its own bitmap but a large list host still paints above it.
     */
    static boolean supportsHostedSettingsPackage(String packageName) {
        return CONTENT_EXTENSION_PACKAGE.equals(packageName)
                || MISHARE_PACKAGE.equals(packageName)
                || CLEAN_MASTER_PACKAGE.equals(packageName)
                || MISOUND_PACKAGE.equals(packageName)
                || CLOUD_SERVICE_PACKAGE.equals(packageName)
                || AI_VISION_PACKAGE.equals(packageName)
                || UPDATER_PACKAGE.equals(packageName)
                || DOWNLOADS_UI_PACKAGE.equals(packageName)
                || COMPASS_PACKAGE.equals(packageName)
                || AIASST_SERVICE_PACKAGE.equals(packageName)
                || GREENGUARD_PACKAGE.equals(packageName);
    }

    /**
     * Source routing follows the assets actually present in the original .mtz.
     * Five hosted modules ship only a light window bitmap; Downloads ships both.
     * A missing dark bitmap must intentionally fall through to Settings' dark
     * surface, rather than accepting the app's stock black ColorDrawable.
     */
    private static boolean hasOwnModuleWindowBitmap(String packageName, boolean night) {
        if (DOWNLOADS_UI_PACKAGE.equals(packageName)) return true;
        // Security Center ships its own full-screen window art whose aspect
        // matches this display exactly; the Settings fallback bitmap is a
        // different illustration and reads as the wrong wallpaper there.
        return !night && (CONTENT_EXTENSION_PACKAGE.equals(packageName)
                || CLEAN_MASTER_PACKAGE.equals(packageName)
                || MISOUND_PACKAGE.equals(packageName)
                || CLOUD_SERVICE_PACKAGE.equals(packageName)
                || AI_VISION_PACKAGE.equals(packageName)
                || COMPASS_PACKAGE.equals(packageName)
                || AIASST_SERVICE_PACKAGE.equals(packageName)
                || SECURITY_CENTER_PACKAGE.equals(packageName));
    }

    void install() {
        hookAfter(Instrumentation.class, "callActivityOnCreate",
                new Class<?>[]{Activity.class, Bundle.class}, (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnResume",
                new Class<?>[]{Activity.class}, (receiver, args) -> schedule((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnStop",
                new Class<?>[]{Activity.class}, (receiver, args) -> stop((Activity) args[0]));
        hookAfter(Instrumentation.class, "callActivityOnDestroy",
                new Class<?>[]{Activity.class}, (receiver, args) -> destroy((Activity) args[0]));
        hookAfter(Activity.class, "onContentChanged", new Class<?>[]{}, (receiver, args) -> {
            if (receiver instanceof Activity) schedule((Activity) receiver);
        });
        // Some HyperOS settings pages retain their Activity while changing
        // day/night resources. Re-run the existing exact session so it does
        // not retain the bitmap selected on its first launch.
        hookAfter(Activity.class, "onConfigurationChanged",
                new Class<?>[]{android.content.res.Configuration.class}, (receiver, args) -> {
                    if (receiver instanceof Activity) schedule((Activity) receiver);
                });
        // Several HyperOS pages retain their activity and do not dispatch its
        // configuration callback.  Application always receives the process-level
        // mode change, so refresh only the exact activities already in a session.
        hookAfter(Application.class, "onConfigurationChanged",
                new Class<?>[]{android.content.res.Configuration.class}, (receiver, args) -> {
                    if (receiver instanceof Application) {
                        scheduleSessionsForApplication((Application) receiver);
                    }
                });
    }

    private void schedule(Activity activity) {
        if (!isTarget(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (scheduled) {
            if (!scheduled.add(activity)) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            applyAndRelease(activity, true);
            return;
        }
        // MIUIX adds portions of the header/card hierarchy after the first layout pass.
        decor.post(() -> applyAndRelease(activity, false));
        decor.postDelayed(() -> applyAndRelease(activity, false), 300L);
        decor.postDelayed(() -> applyAndRelease(activity, true), 900L);
    }

    private void applyAndRelease(Activity activity, boolean release) {
        if (!activity.isFinishing() && !activity.isDestroyed()) apply(activity);
        if (release) {
            synchronized (scheduled) {
                scheduled.remove(activity);
            }
        }
    }

    /**
     * MIUI's ThemeResources update is asynchronous after uiMode changes. Keep
     * the already working layer until the replacement bitmap is really
     * obtainable, then retry at a quiet point without re-entering lifecycle
     * hooks or altering unrelated activities.
     */
    private void scheduleModeRetry(Activity activity) {
        synchronized (modeRetryPending) {
            if (!modeRetryPending.add(activity)) return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        Runnable retry = () -> {
            synchronized (modeRetryPending) {
                modeRetryPending.remove(activity);
            }
            if (!isTarget(activity) || activity.isFinishing() || activity.isDestroyed()) return;
            apply(activity);
        };
        if (decor != null) decor.postDelayed(retry, 1500L);
        else retry.run();
    }

    private void scheduleSessionsForApplication(Application application) {
        List<Activity> active;
        synchronized (sessions) {
            active = new ArrayList<>(sessions.keySet());
        }
        int refreshed = 0;
        for (Activity activity : active) {
            if (activity != null && activity.getApplication() == application) {
                refreshed++;
                schedule(activity);
            }
        }
        if (refreshed > 0) {
            module.log(Log.INFO, "ThemeCompat", "Application configuration changed; refreshing "
                    + refreshed + " active themed surface(s)");
        }
    }

    /**
     * This is the allow-list from HyperBackground's published source, retained as an
     * allow-list instead of applying a wallpaper to every Activity in a process.  In
     * particular, pairing/login/permission activities keep their native window and
     * only full-screen settings-style pages take the active Settings theme drawable.
     */
    private boolean isTarget(Activity activity) {
        if (activity == null) return false;
        String packageName = activity.getPackageName();
        String className = activity.getClass().getName();
        if (isSensitiveTransientActivity(className) || isSensitiveTransientWindow(activity)) return false;

        if (SETTINGS_PACKAGE.equals(packageName)) return true;
        if (MILINK_PACKAGE.equals(packageName)) {
            return className.contains(".ui.connectivitysettings.")
                    || className.equals("com.milink.ui.setting.SettingActivity")
                    || className.endsWith(".NetWorkingActivity");
        }
        if (PHONE_PACKAGE.equals(packageName)) return matchesPhoneSettings(className);
        if (ACCOUNT_PACKAGE.equals(packageName)) return matchesAccountSettings(className);
        if (HOME_PACKAGE.equals(packageName)) return matchesHomeSettings(className);
        if (SECURITY_CENTER_PACKAGE.equals(packageName)) return matchesSecurityCenterSettings(className);
        if (POWER_KEEPER_PACKAGE.equals(packageName)) return true;
        if (MI_SETTINGS_PACKAGE.equals(packageName)) return matchesMiSettings(className);
        if (THEME_MANAGER_PACKAGE.equals(packageName)) {
            return className.equals("com.android.thememanager.ThemeResourceProxyTabActivity")
                    || className.equals("com.android.thememanager.module.detail.view.ThemeDetailActivity")
                    || className.equals(
                    "com.android.thememanager.settings.personalize.activity.PersonalizeActivity");
        }
        if (CONTENT_EXTENSION_PACKAGE.equals(packageName)) {
            return className.equals("com.miui.contentextension.setting.activity.MainSettingsActivity");
        }
        if (MISHARE_PACKAGE.equals(packageName)) {
            return className.equals("com.miui.mishare.activity.MiShareSettingsActivity");
        }
        if (CLEAN_MASTER_PACKAGE.equals(packageName)) {
            return className.equals("com.miui.optimizecenter.MainActivity")
                    || className.equals("com.miui.optimizecenter.deepclean.DeepCleanActivity");
        }
        if (MISOUND_PACKAGE.equals(packageName)) {
            return className.equals("com.miui.misound.HeadsetSettingsActivity");
        }
        if (CLOUD_SERVICE_PACKAGE.equals(packageName)) {
            return className.equals("com.miui.cloudservice.ui.MiCloudMainActivity");
        }
        if (AI_VISION_PACKAGE.equals(packageName)) {
            return className.equals("com.xiaomi.aiasst.vision.ui.MainActivity");
        }
        if (UPDATER_PACKAGE.equals(packageName)) {
            return className.equals("com.android.updater.UpdateActivity");
        }
        if (DOWNLOADS_UI_PACKAGE.equals(packageName)) {
            return className.equals("com.android.providers.downloads.ui.DownloadList");
        }
        // The compass app is a single-purpose full-screen host; every activity
        // shows the wallpaper canvas (compass + level pages).
        if (COMPASS_PACKAGE.equals(packageName)) return true;
        // 未成年人模式: only the page Settings opens (the guardian guide) is
        // confirmed; the app's direct MainActivity sits behind an account
        // login wall and the kid-mode sub-flows keep their own art.
        if (GREENGUARD_PACKAGE.equals(packageName)) {
            return "com.miui.minors.feature.presentation.ui.guide.GuideHomePageActivity"
                    .equals(className);
        }
        if (AIASST_SERVICE_PACKAGE.equals(packageName)) {
            return className.equals("com.xiaomi.aiasst.service.aicall.settings.main.MiAiSettingsActivity")
                    || className.equals("com.xiaomi.aiasst.service.aicall.settings.main.CallLogAndSettingsActivity");
        }
        // Contacts aliases its normal contacts/recent-calls/dialpad host to PeopleActivity.
        return CONTACTS_PACKAGE.equals(packageName)
                && "com.android.contacts.activities.PeopleActivity".equals(className);
    }

    private void apply(Activity activity) {
        apply(activity, null);
    }

    private void apply(Activity activity, LoadedThemeDrawable preparedDrawable) {
        /*
         * The theme-detail page already renders the module's window art as its
         * own window background; only its top bar wrongly draws the same art
         * fit-XY inside the strip.  Clear-only mode strips the bar fills and
         * never adds a second bitmap layer on top of the app's own one.
         */
        boolean clearOnly = THEME_MANAGER_PACKAGE.equals(activity.getPackageName())
                && "com.android.thememanager.module.detail.view.ThemeDetailActivity"
                        .equals(activity.getClass().getName());
        if (clearOnly) preparedDrawable = null;
        View contentView = activity.findViewById(android.R.id.content);
        if (!(contentView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) contentView;
        boolean home = SETTINGS_PACKAGE.equals(activity.getPackageName())
                && SETTINGS_HOME.equals(activity.getClass().getName());
        ViewGroup host = selectLayerHost(activity, content, home);
        boolean transparentTopBar = host != content;
        boolean night = isNightMode(activity);

        LayerSession old = sessions.get(activity);
        if (old != null && old.host == host) {
            if (old.night != night) {
                LoadedThemeDrawable replacement = preparedDrawable;
                if (!MISHARE_PACKAGE.equals(activity.getPackageName()) && !clearOnly && replacement == null) {
                    replacement = loadActiveThemeDrawable(activity);
                }
                if (!MISHARE_PACKAGE.equals(activity.getPackageName()) && !clearOnly && replacement == null) {
                    module.log(Log.INFO, "ThemeCompat", "Theme resource still changing; retaining prior layer "
                            + "package=" + activity.getPackageName() + " night=" + night);
                    scheduleModeRetry(activity);
                    return;
                }
                remove(old);
                sessions.remove(activity);
                module.log(Log.INFO, "ThemeCompat", "Rebuilding themed surface after mode change package="
                        + activity.getPackageName() + " activity=" + activity.getClass().getName()
                        + " night=" + night);
                apply(activity, replacement);
                return;
            }
            old.refresh(activity, home);
            return;
        }
        try {
            View originalRoot = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            View themeLayer = clearOnly ? null : attachThemeLayer(activity, host, preparedDrawable);
            if (themeLayer == null && !clearOnly) {
                // A mode switch refreshes ThemeResources asynchronously.  Never remove a
                // valid existing layer while its replacement is temporarily unavailable:
                // otherwise the content roots below become the full black/white page that
                // the user sees after toggling dark mode.
                module.log(Log.WARN, "ThemeCompat", "No usable active Settings theme drawable; retaining "
                        + (old == null ? "native page" : "prior layer") + " and retrying"
                        + " package=" + activity.getPackageName() + " night=" + night);
                if (old != null) scheduleModeRetry(activity);
                return;
            }
            LayerSession session = new LayerSession(themeLayer, night);

            // attachThemeLayer succeeded before this point.  Only now is it safe
            // to restore and detach a layer whose host changed during recreation.
            if (old != null) {
                remove(old);
                sessions.remove(activity);
            }

            // This is the order used by the verified HyperBackground source: a
            // background must exist at child index 0 before the opaque roots are
            // cleared.  Here the background is the active .mtz drawable, not a file
            // bundled in this module.
            if (host != content) session.clear(host);
            session.clear(content);
            if (originalRoot != null) session.clear(originalRoot);
            for (String name : COMMON_OVERLAY_SURFACES) clearNamed(activity, session, name);
            if (!home) for (String name : SECONDARY_SURFACES) clearNamed(activity, session, name);

            session.attach(activity, host, home, transparentTopBar);
            sessions.put(activity, session);
            module.log(Log.INFO, "ThemeCompat", "Applied themed "
                    + (home ? "settings-home" : "settings-surface")
                    + " package=" + activity.getPackageName()
                    + " host=" + host.getClass().getName() + " activity=" + activity.getClass().getName());
        } catch (Throwable error) {
            module.log(Log.ERROR, "ThemeCompat", "Cannot apply theme background", error);
        }
    }

    private ViewGroup selectLayerHost(Activity activity, ViewGroup content, boolean home) {
        if (home) return content;
        ViewParent parent = content.getParent();
        if (parent instanceof ViewGroup && isMiuixActionBarHost(activity, (ViewGroup) parent)) {
            return (ViewGroup) parent;
        }
        int id = activity.getResources().getIdentifier("action_bar_overlay_layout", "id", activity.getPackageName());
        View candidate = id == 0 ? null : activity.findViewById(id);
        if (candidate instanceof ViewGroup && isAncestor((ViewGroup) candidate, content)
                && isMiuixActionBarHost(activity, (ViewGroup) candidate)) {
            return (ViewGroup) candidate;
        }
        return content;
    }

    private boolean isMiuixActionBarHost(Activity activity, ViewGroup view) {
        String className = view.getClass().getName().toLowerCase();
        String idName = resourceEntryName(activity, view);
        return className.contains("miuix.appcompat.internal.app.widget.actionbaroverlaylayout")
                || className.contains("miuix.appcompat.internal.app.widget.actionbarmovablelayout")
                || "action_bar_overlay_layout".equals(idName);
    }

    private boolean isAncestor(ViewGroup ancestor, View child) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private View findNamed(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private void clearNamed(Activity activity, LayerSession session, String name) {
        session.clear(findNamed(activity, name));
    }

    private View attachThemeLayer(Activity activity, ViewGroup host,
                                  LoadedThemeDrawable preparedDrawable) {
        if (MISHARE_PACKAGE.equals(activity.getPackageName())) {
            /* MiShare already owns a themed window image in both modes. */
            View anchor = new View(activity);
            anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(anchor, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            module.log(Log.INFO, "ThemeCompat",
                    "Attached transparent native-window anchor for MiShare");
            return anchor;
        }
        LoadedThemeDrawable loaded = preparedDrawable != null
                ? preparedDrawable : loadActiveThemeDrawable(activity);
        if (loaded == null) return null;
        ThemeDrawableLayer layer = new ThemeDrawableLayer(activity);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.setImageDrawable(loaded.drawable);
        host.addView(layer, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        module.log(Log.INFO, "ThemeCompat", "Attached active theme drawable=" + loaded.name
                + (loaded.id == 0 ? "" : " id=0x" + Integer.toHexString(loaded.id))
                + " type=" + loaded.drawable.getClass().getName());
        return layer;
    }

    /** Resolves a bitmap before tearing down a currently visible layer. */
    private LoadedThemeDrawable loadActiveThemeDrawable(Activity activity) {
        try {
            boolean night = isNightMode(activity);
            Context themeContext;
            String resourcePackage;
            String[] names;
            Drawable source = null;
            String selectedName = null;
            int id = 0;
            if (THEME_MANAGER_PACKAGE.equals(activity.getPackageName())
                    // 系统个性化: the store's theme_window_background is a flat
                    // grey nine-patch in current packs, so the page reads
                    // unhooked; give it the shared Settings artwork instead.
                    && !"com.android.thememanager.settings.personalize.activity.PersonalizeActivity"
                            .equals(activity.getClass().getName())) {
                themeContext = activity;
                resourcePackage = THEME_MANAGER_PACKAGE;
                source = ThemeManagerThemeResourceBridge.load(activity, night, module);
                if (source != null) {
                    selectedName = night
                            ? "theme-stream:nightmode/res/drawable-xxhdpi/window_bg_dark.9.png"
                            : "theme-stream:res/drawable-xxhdpi/window_bg_light.9.png";
                }
                names = night
                        ? new String[]{"window_bg_dark", "miuix_appcompat_window_bg_dark"}
                        : new String[]{"theme_window_background", "window_bg_light",
                        "miuix_appcompat_window_bg_light"};
            } else if (UPDATER_PACKAGE.equals(activity.getPackageName())) {
                /*
                 * System Updater has its own complete light/dark window
                 * bitmaps in the original theme module.  Reading the updater
                 * context avoids Settings' cross-process resource-cache delay.
                 */
                themeContext = activity;
                resourcePackage = UPDATER_PACKAGE;
                names = night
                        ? new String[]{"miuix_appcompat_window_bg_dark"}
                        : new String[]{"miuix_appcompat_window_bg_light"};
            } else if (night && SECURITY_CENTER_PACKAGE.equals(activity.getPackageName())) {
                /*
                 * The pack's nightmode/theme_fallback.xml only carries the
                 * light window aliases, so at night miuix_appcompat_window_
                 * bg_dark resolves to the app's stock black XML and the
                 * module's own dark canvas is unreachable by name.  Read it
                 * straight from the applied module; the name chain below
                 * stays as the fallback for a pack that ships the alias.
                 */
                String[] darkPaths = {"nightmode/res/drawable-xxhdpi/window_bg_dark.9.png",
                        "res/drawable-xxhdpi/window_bg_dark.9.png"};
                source = SettingsThemeResourceStreamBridge.load(activity, SECURITY_CENTER_PACKAGE,
                        darkPaths, true, module, "SecurityCenterThemeStream");
                if (source != null) {
                    selectedName = "theme-stream:" + darkPaths[0];
                }
                themeContext = activity;
                resourcePackage = SECURITY_CENTER_PACKAGE;
                names = new String[]{"miuix_appcompat_window_bg_dark", "window_bg_dark"};
            } else if (hasOwnModuleWindowBitmap(activity.getPackageName(), night)
                    || (night && NIGHT_OWN_DARK_PACKAGES.contains(activity.getPackageName()))) {
                // Use the current module's own .mtz bitmap whenever it exists.
                // Hosted packages whose adapted .mtz gained nightmode aliases
                // (Translate, Clean Master) try their own dark-name resources
                // first at night; a miss keeps the previous fallback chain
                // untouched.  This is deliberately before the Settings
                // fallback below.
                themeContext = activity;
                resourcePackage = activity.getPackageName();
                names = night
                        ? new String[]{"miuix_appcompat_window_bg_dark", "window_bg_dark"}
                        : new String[]{"miuix_appcompat_window_bg_light", "window_bg_light"};
            } else {
                String name = THEME_PAGE_BACKGROUND + (night ? "dark" : "light");
                // Do not make cross-app Resources#getDrawable the only source of
                // the common Settings wallpaper.  On this HyperOS build it can
                // transiently yield the stock ColorDrawable after a uiMode switch,
                // even while ThemeResources still owns the active .mtz archive.
                source = SettingsThemeResourceStreamBridge.load(activity, night, module);
                if (source != null) {
                    selectedName = night
                            ? "theme-stream:res/drawable-xxhdpi/window_bg_dark.9.png"
                            : "theme-stream:res/drawable-xxhdpi/window_bg_light.9.png";
                }
                Context settingsContext = SETTINGS_PACKAGE.equals(activity.getPackageName())
                        ? activity
                        : activity.createPackageContext(SETTINGS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                // A target app can be one configuration callback behind Settings after
                // the system changes day/night.  Start from Settings' own themed
                // resources, then force the requested mode instead of inheriting
                // whichever mode happened to be cached in the target Activity.
                android.content.res.Configuration modeConfiguration =
                        new android.content.res.Configuration(
                                settingsContext.getResources().getConfiguration());
                modeConfiguration.uiMode = (modeConfiguration.uiMode
                        & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        | (night
                        ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                        : android.content.res.Configuration.UI_MODE_NIGHT_NO);
                themeContext = settingsContext.createConfigurationContext(modeConfiguration);
                resourcePackage = SETTINGS_PACKAGE;
                /*
                 * Dark mode prefers the module's window wallpaper aliases
                 * (same artwork set as the light surface) and only falls back
                 * to the card-page background when none resolves.
                 */
                names = night
                        ? new String[]{"settings_window_bg_dark", "window_bg_dark",
                        "miuix_appcompat_settings_window_bg_dark", name}
                        : new String[]{name};
            }
            if (source == null) {
                for (String name : names) {
                    id = themeContext.getResources().getIdentifier(name, "drawable", resourcePackage);
                    if (id == 0) continue;
                    Drawable candidate = themeContext.getResources().getDrawable(id, themeContext.getTheme());
                    if (candidate == null || candidate instanceof android.graphics.drawable.ColorDrawable) continue;
                    source = candidate;
                    selectedName = name;
                    break;
                }
            }
            if (source == null || selectedName == null
                    || source instanceof android.graphics.drawable.ColorDrawable) return null;
            Drawable.ConstantState state = source.getConstantState();
            Drawable drawable = state == null ? source.mutate()
                    : state.newDrawable(themeContext.getResources(), themeContext.getTheme()).mutate();
            return new LoadedThemeDrawable(drawable, resourcePackage + ":" + selectedName, id);
        } catch (Throwable error) {
            module.log(Log.ERROR, "ThemeCompat", "Cannot resolve active theme drawable", error);
            return null;
        }
    }

    private static final class LoadedThemeDrawable {
        final Drawable drawable;
        final String name;
        final int id;

        LoadedThemeDrawable(Drawable drawable, String name, int id) {
            this.drawable = drawable;
            this.name = name;
            this.id = id;
        }
    }

    private static boolean isNightMode(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Reads a named bitmap from an applied theme module through HyperOS' own
     * ThemeResources API.
     *
     * The original theme maps the public preference-background resource names to
     * these two window files in theme_fallback.xml.  Unlike Resources#getDrawable,
     * this path asks the ThemeResourcesPackage for a fresh stream after explicitly
     * selecting day/night, so it does not inherit a target application's stale
     * Drawable cache during a live mode switch.  No artwork is bundled here.
     */
    private static final class SettingsThemeResourceStreamBridge {
        private static final String LIGHT_PATH = "res/drawable-xxhdpi/window_bg_light.9.png";
        /*
         * The active Settings module keeps its dark wallpaper under
         * nightmode/ (the light one sits at res/); probing the legacy res/
         * location too keeps older module layouts resolvable.
         */
        private static final String[] DARK_PATHS = {
                "nightmode/res/drawable-xxhdpi/window_bg_dark.9.png",
                "res/drawable-xxhdpi/window_bg_dark.9.png"};

        static Drawable load(Activity activity, boolean night, XposedModule module) {
            return load(activity, SETTINGS_PACKAGE,
                    night ? DARK_PATHS : new String[]{LIGHT_PATH}, night, module,
                    "SettingsThemeStream");
        }

        static Drawable load(Activity activity, String packageName, String[] paths,
                             boolean night, XposedModule module, String tag) {
            try {
                Class<?> resourcesType = Class.forName("android.content.res.MiuiResources", false, null);
                Object resources = activity.getResources();
                if (!resourcesType.isInstance(resources)) {
                    module.log(Log.WARN, tag, "Host resources are not MiuiResources: "
                            + resources.getClass().getName());
                    return null;
                }
                Class<?> packageType = Class.forName("miui.content.res.ThemeResourcesPackage", false, null);
                Method factory = packageType.getMethod("getThemeResources", resourcesType, String.class);
                Object themeResources = factory.invoke(null, resources, packageName);
                if (themeResources == null) return null;

                Method checkUpdate = findMethod(themeResources.getClass(), "checkUpdate");
                checkUpdate.invoke(themeResources);
                Method modeSetter = findMethod(themeResources.getClass(), "setNightModeEnable", Boolean.TYPE);
                modeSetter.invoke(themeResources, night);
                Method streamMethod = findMethod(themeResources.getClass(), "getThemeStream",
                        String.class, long[].class);
                InputStream stream = null;
                String path = null;
                for (String candidate : paths) {
                    Object value = streamMethod.invoke(themeResources, candidate, new long[1]);
                    if (value instanceof InputStream) {
                        stream = (InputStream) value;
                        path = candidate;
                        break;
                    }
                }
                if (stream == null) {
                    module.log(Log.INFO, tag, "Active stream absent paths="
                            + String.join(", ", paths));
                    return null;
                }
                try (InputStream active = stream) {
                    Bitmap bitmap = BitmapFactory.decodeStream(active);
                    if (bitmap == null) {
                        module.log(Log.WARN, tag, "Undecodable stream path=" + path);
                        return null;
                    }
                    module.log(Log.INFO, tag, "Loaded stream path=" + path
                            + " size=" + bitmap.getWidth() + 'x' + bitmap.getHeight()
                            + " night=" + night);
                    return new BitmapDrawable(activity.getResources(), bitmap);
                }
            } catch (Throwable error) {
                module.log(Log.WARN, tag, "Cannot open theme stream package=" + packageName, error);
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

    /**
     * Opens only a named bitmap from the currently active Theme Manager module.
     * Xiaomi keeps these assets in the .mtz archive without compiling a matching
     * resource ID into the Theme Manager APK, so Resources#getIdentifier cannot
     * address them.  Capturing the framework's own ThemeResourcesPackage retains
     * normal theme switching and avoids packaging any theme artwork in this APK.
     */
    private static final class ThemeManagerThemeResourceBridge {
        private static final String LIGHT_PATH =
                "res/drawable-xxhdpi/window_bg_light.9.png";
        private static final String DARK_PATH =
                "nightmode/res/drawable-xxhdpi/window_bg_dark.9.png";
        private static volatile WeakReference<Object> active = new WeakReference<>(null);
        private static volatile boolean installed;

        static void install(XposedModule module) {
            if (installed) return;
            synchronized (ThemeManagerThemeResourceBridge.class) {
                if (installed) return;
                try {
                    Class<?> type = Class.forName("miui.content.res.ThemeResourcesPackage", false, null);
                    int hooks = 0;
                    for (Method method : type.getDeclaredMethods()) {
                        if (!"getThemeFile".equals(method.getName())
                                || method.getParameterTypes().length == 0) continue;
                        method.setAccessible(true);
                        module.hook(method).intercept(chain -> {
                            Object receiver = chain.getThisObject();
                            if (THEME_MANAGER_PACKAGE.equals(readField(receiver, "mPackageName"))) {
                                active = new WeakReference<>(receiver);
                            }
                            return chain.proceed(chain.getArgs().toArray(new Object[0]));
                        });
                        hooks++;
                    }
                    installed = true;
                    module.log(Log.INFO, "ThemeManagerStream",
                            "Installed ThemeResources bridge methods=" + hooks);
                } catch (Throwable error) {
                    module.log(Log.WARN, "ThemeManagerStream",
                            "Cannot install ThemeResources bridge", error);
                }
            }
        }

        static Drawable load(Activity activity, boolean night, XposedModule module) {
            Object receiver = active.get();
            if (receiver == null) {
                module.log(Log.WARN, "ThemeManagerStream",
                        "No live ThemeResourcesPackage captured; using normal resource fallback");
                return null;
            }
            String path = night ? DARK_PATH : LIGHT_PATH;
            try {
                Method modeSetter = findMethod(receiver.getClass(), "setNightModeEnable", Boolean.TYPE);
                modeSetter.invoke(receiver, night);
                Method streamMethod = findMethod(receiver.getClass(), "getThemeStream",
                        String.class, long[].class);
                Object value = streamMethod.invoke(receiver, path, new long[1]);
                if (!(value instanceof InputStream)) {
                    module.log(Log.WARN, "ThemeManagerStream",
                            "Theme stream absent path=" + path);
                    return null;
                }
                try (InputStream stream = (InputStream) value) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap == null) {
                        module.log(Log.WARN, "ThemeManagerStream",
                                "Theme stream is not a decodable bitmap path=" + path);
                        return null;
                    }
                    module.log(Log.INFO, "ThemeManagerStream", "Loaded active module path=" + path
                            + " size=" + bitmap.getWidth() + 'x' + bitmap.getHeight());
                    return new BitmapDrawable(activity.getResources(), bitmap);
                }
            } catch (Throwable error) {
                module.log(Log.WARN, "ThemeManagerStream",
                        "Cannot open active module path=" + path, error);
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
                    // Continue through the framework implementation hierarchy.
                }
            }
            throw new NoSuchMethodException(type.getName() + '#' + name);
        }

        private static Object readField(Object receiver, String name) {
            for (Class<?> cursor = receiver == null ? null : receiver.getClass(); cursor != null;
                    cursor = cursor.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(receiver);
                } catch (NoSuchFieldException ignored) {
                    // Continue through the runtime class hierarchy.
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    private static boolean isSensitiveTransientWindow(Activity activity) {
        TypedArray attrs = null;
        try {
            attrs = activity.obtainStyledAttributes(new int[]{
                    android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating});
            if (attrs.getBoolean(0, false) || attrs.getBoolean(1, false)) return true;
        } catch (Throwable ignored) {
            // Continue with the window-size check below.
        } finally {
            if (attrs != null) try { attrs.recycle(); } catch (Throwable ignored) { }
        }
        try {
            Window window = activity.getWindow();
            WindowManager.LayoutParams params = window == null ? null : window.getAttributes();
            return params != null
                    && (params.width != WindowManager.LayoutParams.MATCH_PARENT
                    || params.height != WindowManager.LayoutParams.MATCH_PARENT)
                    && params.width > 0 && params.height > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSensitiveTransientActivity(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains("permissionactivity") || name.contains("requirepermission")
                || name.contains("permissiondialog") || name.contains("authorization")
                || name.contains("authorize") || name.contains("accesscheckactivity")
                || name.contains("confirmcredential") || name.contains("credential")
                || name.contains("password") || name.contains("pinactivity")
                || name.contains("payment") || name.contains("wallet") || name.contains("login")
                || name.contains("signin") || name.contains("oauth") || name.contains("passport")
                || name.contains("emergency") || name.contains("dialer") || name.contains("incall")
                || name.contains("confirmdialog") || name.contains("grant")
                || name.endsWith("ctaactivity") || name.contains("transparentactivity")
                || name.contains("dialogactivity");
    }

    private static boolean matchesPhoneSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.startsWith("com.android.phone.settings.") || name.contains("setting")
                || name.contains("calloptions") || name.contains("callbarringoptions")
                || name.contains("callfeaturessetting") || name.contains("callforwardtype")
                || name.contains("callforwardoptions") || name.contains("additionalcalloptions")
                || name.endsWith("fivegnrcasettingactivity") || name.endsWith("nrdisplayactivity");
    }

    private static boolean matchesAccountSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains(".settings.") || name.contains("accountsettings")
                || name.contains("accountsecurity") || name.contains("agreementandprivacy")
                || name.contains("systemadactivity") || name.contains("userdetailinfo")
                || name.contains("userphoneinfo") || name.contains("devicesettinglist")
                || name.contains("devicedetailinfo") || name.contains("snslistactivity")
                || name.contains("snsaccountactivity");
    }

    private static boolean matchesThemeSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains(".settings.") || name.contains("themesettings")
                || name.contains("themepreference") || name.contains("themeabout")
                || name.contains("themeresourceproxy")
                || name.endsWith(".activity.themetabactivity") || name.contains("themeandwallpaper")
                || name.contains("wallpapersettings") || name.contains("wallpapersubsetting")
                || name.contains("wallpapertabactivity") || name.contains("wallpapermiuitab")
                || name.contains("privacysettings") || name.contains("authoritymanagement")
                || name.contains("supportthemeactivity") || name.contains("personalize")
                || name.contains("aifromsettings");
    }

    private static boolean matchesHomeSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains(".settings.") || name.contains("settingsactivity")
                || name.contains("homesettings") || name.contains("launchersettings");
    }

    private static boolean matchesSecurityCenterSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains(".settings.") || name.contains("setting") || name.contains("power")
                || name.contains("battery") || name.contains("autostart") || name.contains("appmanager")
                || name.contains("privacy") || name.contains("permission")
                || name.contains("networkassistant") || name.contains("garbage") || name.contains("optimiz");
    }

    private static boolean matchesMiSettings(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains("healthy") || name.contains("usagestat") || name.contains("focusmode")
                || name.contains("devicelimit") || name.contains("appusage")
                || name.contains("screen") || name.contains("settings");
    }

    private void dumpThemeDrawableAliases(Activity activity) {
        for (String name : THEME_DRAWABLE_ALIASES) {
            dumpThemeDrawableAlias(activity, name, SETTINGS_PACKAGE);
            dumpThemeDrawableAlias(activity, name, "miui");
        }
    }

    private void dumpThemeDrawableAlias(Activity activity, String name, String packageName) {
        try {
            int id = activity.getResources().getIdentifier(name, "drawable", packageName);
            if (id == 0) {
                module.log(Log.INFO, "ThemeCompatResource", packageName + ":" + name + " -> absent");
                return;
            }
            Drawable drawable = activity.getResources().getDrawable(id, activity.getTheme());
            module.log(Log.INFO, "ThemeCompatResource", packageName + ":" + name
                    + " id=0x" + Integer.toHexString(id) + " type="
                    + (drawable == null ? "null" : drawable.getClass().getName())
                    + " size=" + (drawable == null ? "-" : drawable.getIntrinsicWidth() + "x" + drawable.getIntrinsicHeight()));
        } catch (Throwable error) {
            module.log(Log.WARN, "ThemeCompatResource", packageName + ":" + name + " -> error", error);
        }
    }

    private void stop(Activity activity) {
        // No renderer is owned by this module; lifecycle ownership stays with the renderer.
    }

    private void destroy(Activity activity) {
        LayerSession session = sessions.remove(activity);
        if (session != null) remove(session);
    }

    private void remove(LayerSession session) {
        session.detach();
        session.restore();
    }

    private String resourceEntryName(Activity activity, View view) {
        if (view == null || view.getId() == View.NO_ID || view.getId() == 0) return "";
        try {
            return activity.getResources().getResourceEntryName(view.getId()).toLowerCase();
        } catch (Throwable ignored) {
            return "";
        }
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
                    module.log(Log.WARN, "ThemeCompat", "Callback " + name + " failed", error);
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.ERROR, "ThemeCompat", "Cannot hook " + type.getName() + '#' + name, error);
        }
    }

    private interface After {
        void after(Object receiver, Object[] args) throws Throwable;
    }

    /**
     * A settings window is resized while the IME is visible.  A BitmapDrawable used
     * directly as a View background would be stretched to that reduced height.  This
     * layer always scales from width and anchors at the top, so an IME crops the
     * bottom of a tall theme image instead of changing its aspect ratio.
     */
    private static final class ThemeDrawableLayer extends ImageView {
        private final Matrix transform = new Matrix();

        ThemeDrawableLayer(android.content.Context context) {
            super(context);
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

    private static final class LayerSession {
        final Map<View, Drawable> originalBackgrounds = new IdentityHashMap<>();
        final Set<View> protectedViews = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<ActionBarSurface> actionBars = new ArrayList<>();
        View themeLayer;
        ViewGroup host;
        Activity activity;
        boolean transparentTopBar;
        final boolean night;
        Window statusBarWindow;
        int originalStatusBarColor;
        boolean statusBarColorSaved;
        android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        long rescanDeadline;
        boolean personalizeLayoutHooked;
        final ArrayList<View> walkBuffer = new ArrayList<>();
        final ArrayList<Boolean> walkScope = new ArrayList<>();
        int diagnosticPasses;

        LayerSession(View themeLayer, boolean night) {
            this.themeLayer = themeLayer;
            this.night = night;
        }

        void protect(View view) {
            if (view != null) protectedViews.add(view);
        }

        void dumpHierarchy(Activity activity, View root, XposedModule module) {
            // MIUIX creates parts of the hierarchy late.  Trace the three scheduled
            // passes, but never alter a drawable here.
            if (diagnosticPasses >= 3 || root == null) return;
            diagnosticPasses++;
            int[] remaining = {120};
            dumpView(activity, root, 0, remaining, module);
        }

        private void dumpView(Activity activity, View view, int depth, int[] remaining,
                              XposedModule module) {
            if (view == null || remaining[0] <= 0 || depth > 10) return;
            Drawable background = view.getBackground();
            if (background != null && isInteresting(background, view)) {
                remaining[0]--;
                module.log(Log.INFO, "ThemeCompatTree", "d=" + depth
                        + " id=" + entryName(activity, view)
                        + " view=" + view.getClass().getName()
                        + " frame=" + view.getWidth() + "x" + view.getHeight()
                        + " alpha=" + view.getAlpha()
                        + " bg=" + describe(background)
                        + " path=" + parentPath(activity, view));
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && remaining[0] > 0; i++) {
                    dumpView(activity, group.getChildAt(i), depth + 1, remaining, module);
                }
            }
        }

        private String describe(Drawable drawable) {
            String type = drawable.getClass().getName();
            if (drawable instanceof android.graphics.drawable.ColorDrawable) {
                int color = ((android.graphics.drawable.ColorDrawable) drawable).getColor();
                return type + " color=#" + String.format("%08X", color);
            }
            return type + " intrinsic=" + drawable.getIntrinsicWidth() + "x"
                    + drawable.getIntrinsicHeight() + " opacity=" + drawable.getOpacity();
        }

        private boolean isInteresting(Drawable drawable, View view) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            if (drawable instanceof android.graphics.drawable.ColorDrawable) {
                return (((android.graphics.drawable.ColorDrawable) drawable).getColor() >>> 24) != 0;
            }
            return true;
        }

        private String parentPath(Activity activity, View view) {
            StringBuilder path = new StringBuilder();
            View current = view;
            for (int i = 0; current != null && i < 10; i++) {
                if (path.length() > 0) path.insert(0, " <- ");
                path.insert(0, current.getClass().getSimpleName() + "#" + entryName(activity, current));
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return path.toString();
        }

        void clear(View view) {
            if (view == null || protectedViews.contains(view)) return;
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds.put(view, view.getBackground());
            if (view.getBackground() != null) view.setBackground(null);
        }

        void attach(Activity activity, ViewGroup host, boolean home, boolean transparentTopBar) {
            this.activity = activity;
            this.host = host;
            this.transparentTopBar = transparentTopBar;
            if (transparentTopBar) prepareTransparentStatusBar(activity);
            refresh(activity, home);
        }

        void refresh(Activity activity, boolean home) {
            if (host == null) return;
            if (home) return;
            clearThemeManagerDarkBottomNavigation(activity);
            tintPersonalizeCard(activity);
            clearPageSurfaces(activity, host, host, 0);
            if (transparentTopBar) clearActionBarSurfaces(activity, host, 0);
            rescanDeadline = android.os.SystemClock.uptimeMillis() + RESCAN_WINDOW_MS;
            if (layoutListener == null) installLayoutRescan(host);
        }

        /**
         * 系统个性化 keeps two native opaque surfaces above the wallpaper:
         * the bottom entry card, and the full-width 在线主题 section header
         * the recommend list keeps re-inflating as the user scrolls (each
         * bound header carries a fresh opaque background).  Both take the
         * owner-tunable translucent card alpha in light mode; dark mode
         * keeps the native look.
         */
        private void tintPersonalizeCard(Activity activity) {
            if (!"com.android.thememanager.settings.personalize.activity.PersonalizeActivity"
                    .equals(activity.getClass().getName())) return;
            if (isNightMode(activity)) return;
            try {
                View card = activity.findViewById(activity.getResources().getIdentifier(
                        "theme_personlize_card_view", "id", activity.getPackageName()));
                if (card != null) {
                    Drawable cardBackground = card.getBackground();
                    if (cardBackground != null) {
                        if (isCardLikeDrawable(cardBackground)) {
                            applyCardPaintAlpha(cardBackground);
                        } else {
                            setDrawableAlpha(cardBackground, CardAlpha.light());
                            card.invalidate();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!personalizeLayoutHooked) {
                personalizeLayoutHooked = true;
                // The recommend list inflates long after the first refresh
                // (it follows the async data load), so watch the content
                // view's layout passes and pick the RecyclerView up whenever
                // it appears instead of only during the rescan window.
                View root = activity.findViewById(android.R.id.content);
                if (root != null) {
                    root.getViewTreeObserver().addOnGlobalLayoutListener(
                            () -> tintPersonalizeRecycler(activity));
                }
            }
            tintPersonalizeRecycler(activity);
        }

        private void tintPersonalizeRecycler(Activity activity) {
            ViewGroup recycler = activity.findViewById(activity.getResources().getIdentifier(
                    "recyclerView", "id", activity.getPackageName()));
            if (recycler == null) return;
            tintRecommendHeaders(recycler);
        }


        /** Every layout pass re-clamps the online-section surfaces.  The
         *  header band, the stagger-grid item containers and every card in
         *  between come back from recycling with fresh opaque drawables —
         *  most of them miuix CardDrawables, whose draw() uses a private
         *  Paint and ignores Drawable.setAlpha, so their alpha is written
         *  straight into the Paint's colour instead. */
        private void tintRecommendHeaders(ViewGroup recycler) {
            if (!"com.android.thememanager.settings.personalize.activity.PersonalizeActivity"
                    .equals(activity.getClass().getName())) return;
            if (isNightMode(activity)) return;
            android.content.res.Resources res = recycler.getResources();
            String pack = recycler.getContext().getPackageName();
            int headerId = res.getIdentifier("detail_recommend_title", "id", pack);
            int containerId = res.getIdentifier("container", "id", pack);
            walkBuffer.add(recycler);
            walkScope.clear();
            walkScope.add(Boolean.FALSE);
            for (int index = 0; index < walkBuffer.size(); index++) {
                View view = walkBuffer.get(index);
                boolean inGrid = walkScope.get(index);
                try {
                    Drawable background = view.getBackground();
                    if (background != null) {
                        // Every direct child of the recommend list is a card
                        // surface (header, quick-entry block, stagger blocks)
                        // and takes the alpha whatever drawable class it
                        // carries; deeper in the grid the miuix card drawables
                        // and provable white boards (label pads, cell fills)
                        // do.  The id-less first child wraps the already
                        // handled entry card.
                        boolean directChild = view.getParent() == recycler
                                && view.getId() != View.NO_ID;
                        if (isCardLikeDrawable(background) || directChild
                                || (inGrid && isOpaqueWhiteDrawable(background))) {
                            // Several themed drawables ignore Drawable.setAlpha
                            // yet report it faithfully, so the equality check
                            // would freeze them opaque on the first write —
                            // always re-apply, and try the backing Paint too.
                            background.mutate();
                            background.setAlpha(CardAlpha.light());
                            applyCardPaintAlpha(background);
                            view.invalidate();
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    boolean childScope = inGrid
                            || (view.getParent() == recycler
                                    && (view.getId() == containerId || view.getId() == headerId));
                    for (int child = 0; child < group.getChildCount(); child++) {
                        walkBuffer.add(group.getChildAt(child));
                        walkScope.add(childScope);
                    }
                }
            }
            walkBuffer.clear();
            walkScope.clear();
        }

        /** Colour probe for the common white backings inside the stagger
         *  grid (label boards, cell pads); exotic drawables are skipped. */
        private static boolean isOpaqueWhiteDrawable(Drawable background) {
            if (background.getAlpha() < 255) return false;
            Integer color = null;
            if (background instanceof android.graphics.drawable.ColorDrawable) {
                color = ((android.graphics.drawable.ColorDrawable) background).getColor();
            } else if (background instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList stateList =
                        ((android.graphics.drawable.GradientDrawable) background).getColor();
                if (stateList != null) color = stateList.getDefaultColor();
            }
            if (color == null) return false;
            int red = (color >> 16) & 0xFF;
            int green = (color >> 8) & 0xFF;
            int blue = color & 0xFF;
            return red >= 240 && green >= 240 && blue >= 240;
        }

        /** Card-surface drawables whose draw path uses a private Paint and
         *  ignores Drawable.setAlpha: miuix's support cards and the smooth
         *  stagger-card container. */
        private static boolean isCardLikeDrawable(Drawable background) {
            String name = background.getClass().getName();
            return name.startsWith("com.miui.support.drawable.")
                    || name.startsWith("miuix.smooth.");
        }

        /** com.miui.support.drawable.CardDrawable fills its rounded path from
         *  one private Paint and never consults Drawable.setAlpha; the owner
         *  alpha therefore has to land in the Paint's colour directly. */
        private void applyCardPaintAlpha(Drawable background) {
            int target = CardAlpha.light();
            Class<?> type = background.getClass();
            while (type != null && type != Drawable.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.getType() != android.graphics.Paint.class) continue;
                    field.setAccessible(true);
                    android.graphics.Paint paint;
                    try {
                        paint = (android.graphics.Paint) field.get(background);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (paint == null) continue;
                    int color = paint.getColor();
                    if (((color >>> 24) & 0xFF) != target) {
                        paint.setColor((target << 24) | (color & 0x00FFFFFF));
                        background.invalidateSelf();
                    }
                    return;
                }
                type = type.getSuperclass();
            }
        }

        private void setDrawableAlpha(Drawable background, int target) {
            if (background.getAlpha() == target) return;
            background.mutate();
            background.setAlpha(target);
        }

        /**
         * ThemeResourceProxyTabActivity owns a distinct bottom-navigation host.
         * In dark mode its #nav_container draws an opaque black ColorDrawable
         * above the now-valid theme window image. Clear only this named host;
         * tab icons and all content descendants remain untouched.
         */
        private void clearThemeManagerDarkBottomNavigation(Activity activity) {
            if (activity == null || !THEME_MANAGER_PACKAGE.equals(activity.getPackageName())) return;
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            if (!night) return;
            int id = activity.getResources().getIdentifier("nav_container", "id",
                    THEME_MANAGER_PACKAGE);
            if (id != 0) clear(activity.findViewById(id));
        }

        private void installLayoutRescan(final ViewGroup root) {
            try {
                android.view.ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer == null || !observer.isAlive()) return;
                layoutListener = () -> {
                    if (activity == null || host == null || activity.isFinishing() || activity.isDestroyed()) {
                        removeLayoutRescan();
                        return;
                    }
                    clearPageSurfaces(activity, host, host, 0);
                    if (transparentTopBar) clearActionBarSurfaces(activity, host, 0);
                    if (android.os.SystemClock.uptimeMillis() > rescanDeadline) removeLayoutRescan();
                };
                observer.addOnGlobalLayoutListener(layoutListener);
            } catch (Throwable ignored) {
                layoutListener = null;
            }
        }

        private void removeLayoutRescan() {
            if (layoutListener == null) return;
            try {
                if (host != null) {
                    android.view.ViewTreeObserver observer = host.getViewTreeObserver();
                    if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
                }
            } catch (Throwable ignored) {
                // The session is being discarded.
            }
            layoutListener = null;
        }

        void detach() {
            removeLayoutRescan();
            if (themeLayer != null) {
                try {
                    ViewParent parent = themeLayer.getParent();
                    if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(themeLayer);
                } catch (Throwable ignored) {
                    // The host can be detached before activity destruction.
                }
            }
            themeLayer = null;
            host = null;
            activity = null;
        }

        void restore() {
            for (Map.Entry<View, Drawable> item : originalBackgrounds.entrySet()) {
                try {
                    item.getKey().setBackground(item.getValue());
                } catch (Throwable ignored) {
                    // The activity can be half-destroyed here.
                }
            }
            originalBackgrounds.clear();
            protectedViews.clear();
            for (ActionBarSurface state : actionBars) state.restore();
            actionBars.clear();
            if (statusBarColorSaved && statusBarWindow != null) {
                try {
                    statusBarWindow.setStatusBarColor(originalStatusBarColor);
                } catch (Throwable ignored) {
                    // Window is gone.
                }
            }
            statusBarWindow = null;
            statusBarColorSaved = false;
        }

        private void prepareTransparentStatusBar(Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window == null) return;
                if (!statusBarColorSaved) {
                    statusBarWindow = window;
                    originalStatusBarColor = window.getStatusBarColor();
                    statusBarColorSaved = true;
                }
                window.setStatusBarColor(Color.TRANSPARENT);
            } catch (Throwable ignored) {
                // Some aliases use a system-owned window.
            }
        }

        private void clearPageSurfaces(Activity activity, View view, View root, int depth) {
            if (view == null || view.getVisibility() != View.VISIBLE) return;
            if (isKeptOpaqueSurface(activity, view)) return;
            if (isPageSurface(activity, view, root, depth)) clear(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    clearPageSurfaces(activity, group.getChildAt(i), root, depth + 1);
                }
            }
        }

        /**
         * AppManagerMainActivity pins a full-width search + quick-entry card
         * (top_container) over a full-screen app list.  Its native opaque
         * window-color background is the only thing hiding the rows that slide
         * beneath it, the card lands on a blank region of the theme artwork,
         * and a see-through card makes scrolling read as overlapping text.
         * Keep the entire card subtree native on this one activity.
         */
        private boolean isKeptOpaqueSurface(Activity activity, View view) {
            if (!"com.miui.appmanager.AppManagerMainActivity".equals(
                    activity.getClass().getName())
                    || !"top_container".equals(entryName(activity, view))) return false;
            // The container's own white background is fully overpainted by two
            // full-width children: the search row (themed app_manager_white
            // resolves to #F7F7F7) and AMMainTopView (miuix_window_color
            // #F7F7F7 around its white inner card).  Whiten all three or the
            // grey band against the pure-white title area above remains.
            // Native night chrome is already uniform with the dark page.
            if (!isNightMode(activity)) {
                whiten(view);
                whiten(activity.findViewById(activity.getResources().getIdentifier(
                        "am_search_view", "id", activity.getPackageName())));
                whiten(activity.findViewById(activity.getResources().getIdentifier(
                        "top_view", "id", activity.getPackageName())));
                // The visible grey strip is the search field itself:
                // inputArea's themed miuixAppcompatSearchModeEditTextBackground,
                // plus the grey rounded frame of its parent search_mode_stub
                // (Widget.SearchActionMode) showing around the inset field.
                whiten(activity.findViewById(android.R.id.inputArea));
                whiten(activity.findViewById(activity.getResources().getIdentifier(
                        "search_mode_stub", "id", activity.getPackageName())));
            }
            return true;
        }

        private static void whiten(View view) {
            if (!(view instanceof View) || view == null) return;
            if (isOpaqueWhiteBackground(view.getBackground())) return;
            view.setBackgroundColor(Color.WHITE);
        }

        private static boolean isOpaqueWhiteBackground(Drawable background) {
            return background instanceof android.graphics.drawable.ColorDrawable
                    && ((android.graphics.drawable.ColorDrawable) background).getColor()
                    == Color.WHITE;
        }

        private boolean isPageSurface(Activity activity, View view, View root, int depth) {
            // The root is deliberately retained: it can hold the .mtz's window image.
            if (view == root || protectedViews.contains(view)) return false;
            Drawable background = view.getBackground();
            if (background == null) return false;
            int rootWidth = Math.max(root.getWidth(), activity.getResources().getDisplayMetrics().widthPixels);
            int rootHeight = Math.max(root.getHeight(), activity.getResources().getDisplayMetrics().heightPixels);
            int width = view.getWidth();
            int height = view.getHeight();
            String packageName = activity.getPackageName();
            String id = entryName(activity, view);

            // Direct source port: MiLink's connectivity settings are assembled from one
            // broad host around inset cards.  Only the broad host is made transparent.
            if (MILINK_PACKAGE.equals(packageName)
                    && view instanceof ViewGroup
                    && width >= (int) (rootWidth * 0.965f)
                    && height >= (int) (rootHeight * 0.05f)
                    && !containsAny(id, "card", "button", "switch", "checkbox", "icon", "image", "banner")) {
                return true;
            }

            // Phone/Account/Theme/Security/PowerKeeper/MiSettings split settings pages into
            // several large host panels.  This is deliberately stronger than the Settings-only
            // rule but still excludes cards, controls and image content.
            boolean externalSettingsPage = PHONE_PACKAGE.equals(packageName)
                    || ACCOUNT_PACKAGE.equals(packageName)
                    || SECURITY_CENTER_PACKAGE.equals(packageName)
                    || POWER_KEEPER_PACKAGE.equals(packageName)
                    || MI_SETTINGS_PACKAGE.equals(packageName)
                    || supportsHostedSettingsPackage(packageName);
            if (externalSettingsPage
                    && view instanceof ViewGroup
                    && depth <= 8
                    && width >= (int) (rootWidth * 0.94f)
                    && height >= (int) (rootHeight * 0.15f)
                    && !containsAny(id, "card", "button", "switch", "checkbox", "icon", "image", "banner")) {
                return true;
            }

            // Late-created neutral panels are the black/white slabs visible above the
            // wallpaper in several system setting hosts.  Sample a cloned drawable only,
            // so clearing never mutates a shared MIUIX card drawable.
            if (externalSettingsPage
                    && width >= (int) (rootWidth * 0.5f)
                    && height >= (int) (rootHeight * 0.05f)
                    && isOpaqueNeutralColorDrawable(background)) {
                return true;
            }

            boolean large = width >= (int) (rootWidth * 0.72f) && height >= (int) (rootHeight * 0.32f);
            if (!large) return false;
            String className = view.getClass().getName().toLowerCase();
            if (containsAny(id, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner",
                    "header_card") || containsAny(className, "cardview", "button", "switch", "checkbox", "imageview")) {
                return false;
            }
            if (containsAny(id, "content", "container", "recycler", "list", "prefs", "preference",
                    "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true;
            if (containsAny(className, "recyclerview", "nestedscrollview", "scrollview", "listview",
                    "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true;
            return view instanceof ViewGroup && width >= (int) (rootWidth * 0.90f)
                    && height >= (int) (rootHeight * 0.62f);
        }

        private boolean isOpaqueNeutralColorDrawable(Drawable background) {
            if (background instanceof android.graphics.drawable.ColorDrawable) {
                return isOpaqueNeutral(((android.graphics.drawable.ColorDrawable) background).getColor());
            }
            Integer sampled = sampleDrawableColor(background);
            return sampled != null && isOpaqueNeutral(sampled);
        }

        private Integer sampleDrawableColor(Drawable background) {
            try {
                Drawable.ConstantState state = background == null ? null : background.getConstantState();
                if (state == null) return null;
                Drawable copy = state.newDrawable().mutate();
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                copy.setBounds(0, 0, 1, 1);
                copy.draw(canvas);
                int color = bitmap.getPixel(0, 0);
                bitmap.recycle();
                return color;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean isOpaqueNeutral(int color) {
            if (Color.alpha(color) != 255) return false;
            int red = Color.red(color);
            int green = Color.green(color);
            int blue = Color.blue(color);
            return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 24;
        }

        private void clearActionBarSurfaces(Activity activity, View view, int depth) {
            if (view == null || depth > 8) return;
            String id = entryName(activity, view);
            String className = view.getClass().getName().toLowerCase();
            if (containsAny(id, "action_bar_overlay_layout", "action_bar_container", "action_bar", "app_bar",
                    "collapsing_toolbar", "support_action_bar") || containsAny(className,
                    "actionbaroverlaylayout", "actionbarmovablelayout", "actionbarcontainer", "appbarlayout",
                    "collapsingtoolbarlayout")) {
                clear(view);
                clearMiuixPrimaryBackground(view, className);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    clearActionBarSurfaces(activity, group.getChildAt(i), depth + 1);
                }
            }
        }

        private void clearMiuixPrimaryBackground(View view, String className) {
            if (!className.contains("actionbarcontainer")) return;
            for (ActionBarSurface state : actionBars) {
                if (state.view == view) {
                    state.clear();
                    return;
                }
            }
            ActionBarSurface state = ActionBarSurface.create(view);
            if (state != null) {
                actionBars.add(state);
                state.clear();
            }
        }

        private String entryName(Activity activity, View view) {
            if (view.getId() == View.NO_ID || view.getId() == 0) return "";
            try {
                return activity.getResources().getResourceEntryName(view.getId()).toLowerCase();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private boolean containsAny(String value, String... parts) {
            if (value == null || value.isEmpty()) return false;
            for (String part : parts) if (value.contains(part)) return true;
            return false;
        }
    }

    private static final class ActionBarSurface {
        final View view;
        final Method setter;
        final Drawable original;

        private ActionBarSurface(View view, Method setter, Drawable original) {
            this.view = view;
            this.setter = setter;
            this.original = original;
        }

        static ActionBarSurface create(View view) {
            try {
                Method getter = view.getClass().getMethod("getPrimaryBackground");
                Method setter = view.getClass().getMethod("setPrimaryBackground", Drawable.class);
                Object value = getter.invoke(view);
                return new ActionBarSurface(view, setter, value instanceof Drawable ? (Drawable) value : null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        void clear() {
            try {
                setter.invoke(view, new Object[]{null});
            } catch (Throwable ignored) {
                // This MIUIX build exposes no writable primary background.
            }
        }

        void restore() {
            try {
                setter.invoke(view, original);
            } catch (Throwable ignored) {
                // Window is being destroyed.
            }
        }
    }
}
