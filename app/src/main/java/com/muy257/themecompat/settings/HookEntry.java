package com.muy257.themecompat.settings;

import android.app.Application;
import android.app.Instrumentation;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** Entry point for the final, adapter-only HyperOS compatibility profile. */
public final class HookEntry extends XposedModule {
    /*
     * HyperOS may load a second package context inside an existing process.
     * Deduplicate by package instead of relying on isFirstPackage().
     */
    private static final Set<String> INSTALLED_PACKAGES = new HashSet<>();
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String PHONE_PACKAGE = "com.android.phone";
    private static final String MILINK_PACKAGE = "com.milink.service";
    private static final String HOME_PACKAGE = "com.miui.home";
    private static final String CALENDAR_PACKAGE = "com.android.calendar";
    private static final String CLOCK_PACKAGE = "com.android.deskclock";
    private static final String FILE_EXPLORER_PACKAGE = "com.android.fileexplorer";
    private static final String MESSAGING_PACKAGE = "com.android.mms";
    private static final String CONTACTS_PACKAGE = "com.android.contacts";
    private static final String RECORDER_PACKAGE = "com.android.soundrecorder";
    private static final String GALLERY_PACKAGE = "com.miui.gallery";
    private static final String PACKAGE_INSTALLER_PACKAGE = "com.miui.packageinstaller";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";
    private static final String THEME_MANAGER_PACKAGE = "com.android.thememanager";
    private static final String THEME_STORE_PACKAGE = "com.miui.themestore";
    private static final String CONTENT_EXTENSION_PACKAGE = "com.miui.contentextension";
    private static final String MISHARE_PACKAGE = "com.miui.mishare.connectivity";
    private static final String CLEAN_MASTER_PACKAGE = "com.miui.cleanmaster";
    private static final String MISOUND_PACKAGE = "com.miui.misound";
    private static final String CLOUD_SERVICE_PACKAGE = "com.miui.cloudservice";
    private static final String AIASST_SERVICE_PACKAGE = "com.xiaomi.aiasst.service";
    private static final String AI_TRANSLATE_PACKAGE = "com.xiaomi.aiasst.vision";
    private static final String UPDATER_PACKAGE = "com.android.updater";
    private static final String DOWNLOADS_UI_PACKAGE = "com.android.providers.downloads.ui";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String SYSTEM_UI_PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String SECURITY_CENTER_PACKAGE = "com.miui.securitycenter";
    private static final String GREENGUARD_PACKAGE = "com.miui.greenguard";

    private static final String[] SUPPORTED_PACKAGES = {
            SETTINGS_PACKAGE, MILINK_PACKAGE, PHONE_PACKAGE,
            "com.xiaomi.account", HOME_PACKAGE, SECURITY_CENTER_PACKAGE,
            "com.miui.powerkeeper", "com.xiaomi.misettings", CONTACTS_PACKAGE,
            CALENDAR_PACKAGE, CAMERA_PACKAGE, CLOCK_PACKAGE, FILE_EXPLORER_PACKAGE,
            MESSAGING_PACKAGE, RECORDER_PACKAGE, GALLERY_PACKAGE, PACKAGE_INSTALLER_PACKAGE,
            BILIBILI_PACKAGE, THEME_MANAGER_PACKAGE, THEME_STORE_PACKAGE,
            CONTENT_EXTENSION_PACKAGE, MISHARE_PACKAGE, CLEAN_MASTER_PACKAGE, MISOUND_PACKAGE,
            CLOUD_SERVICE_PACKAGE, AI_TRANSLATE_PACKAGE, UPDATER_PACKAGE, DOWNLOADS_UI_PACKAGE,
            SYSTEM_UI_PACKAGE, SYSTEM_UI_PLUGIN_PACKAGE,
            "com.miui.compass", "com.xiaomi.aiasst.service", GREENGUARD_PACKAGE
    };

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (packageName == null || packageName.isEmpty() || !isSupported(packageName)) return;
        HookSwitches.bindIfPossible(this);
        boolean hookEnabled = HookSwitches.enabled(packageName);
        log(android.util.Log.INFO, "HookEntry",
                "switch " + packageName + " enabled=" + hookEnabled);
        if (!hookEnabled) {
            return;
        }
        installWhenApplicationReady(packageName, param.getDefaultClassLoader());
    }

    private void installWhenApplicationReady(String packageName, ClassLoader loader) {
        Application current = currentApplication();
        if (current != null) {
            installForTheme(packageName, loader);
            return;
        }
        try {
            Method method = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                if (args.length > 0 && args[0] instanceof Application) {
                    installForTheme(packageName, loader);
                }
                return result;
            });
        } catch (Throwable error) {
            log(android.util.Log.WARN, "HookEntry",
                    "cannot wait for application context; hooks skipped package=" + packageName,
                    error);
        }
    }

    private Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installForTheme(String packageName, ClassLoader loader) {
        synchronized (INSTALLED_PACKAGES) {
            if (!INSTALLED_PACKAGES.add(packageName)) return;
        }
        ThemeCompatibilityGate.Status theme = ThemeCompatibilityGate.inspect();
        log(android.util.Log.INFO, "HookEntry",
                "theme " + packageName + " status=" + theme);
        if (theme == ThemeCompatibilityGate.Status.INCOMPATIBLE) {
            log(android.util.Log.WARN, "HookEntry",
                    "adapted-theme marker missing; hooks skipped package=" + packageName);
            return;
        }
        CardAlpha.bind(this);
        if (theme == ThemeCompatibilityGate.Status.UNKNOWN) {
            // This adapter first acquires a non-solid themed window canvas and
            // only then clears covering hosts. It is the sole fail-safe path
            // when secure theme metadata cannot be read.
            new SettingsSurfaceAdapter(this, loader).install();
            log(android.util.Log.WARN, "HookEntry",
                    "theme unknown; installed safe surface-only path package=" + packageName);
            return;
        }

        // Camera has a self-contained, exact Activity adapter and must not be
        // gated by initialization of unrelated generic card/page adapters.
        // Route it first so a vendor class failure elsewhere cannot silently
        // prevent CameraPreferenceActivity lifecycle hooks from installing.
        if (CAMERA_PACKAGE.equals(packageName)) {
            log(android.util.Log.INFO, "HookEntry", "installing CameraSurface adapter");
            new CameraSurfaceAdapter(this).install();
            return;
        }

        if (BusinessCardSurfaceAdapter.supports(packageName)) {
            new BusinessCardSurfaceAdapter(this, packageName).install();
        }
        // jadx on both shipped APKs confirmed the Mi Share and Cloud white
        // rows: an obfuscated preference ItemDecoration paints each card with
        // one inherited Paint fed from a card ColorDrawable field.  Hook the
        // shared obfuscated base onDraw once per package; the adapter holds
        // the full evidence chain.
        if (ObfuscatedCardDecorationAdapter.supports(packageName)) {
            new ObfuscatedCardDecorationAdapter(this, loader,
                    packageName).install();
        }

        // These four packages were observed on this phone to paint the opaque
        // light card in a concrete RecyclerView ItemDecoration.  Install this
        // before the broad page-surface adapter; it edits no View background
        // and makes no dark-mode write.
        if (VerifiedLightCardDecorationAdapter.supports(packageName)) {
            new VerifiedLightCardDecorationAdapter(this, loader, packageName).install();
        }

        // Exact full-page activities proved to need the Settings surface
        // adapter. The resource and hierarchy traces used to discover them are
        // deliberately absent from the finished build.
        if (SettingsSurfaceAdapter.supportsHostedSettingsPackage(packageName)
                || SECURITY_CENTER_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, loader).install();
            if (SECURITY_CENTER_PACKAGE.equals(packageName)) {
                new SecurityCenterCardSurfaceAdapter(this, loader).install();
            }
            if (AI_TRANSLATE_PACKAGE.equals(packageName)) {
                // Translate home: wallpaper already applied by the surface
                // adapter; only the btn_group1 tiles keep opaque white cards.
                new AiasstTranslateCardAdapter(this).install();
            }
            if (AIASST_SERVICE_PACKAGE.equals(packageName)) {
                // AI call settings rows are HyperCellLayout views with an
                // opaque white NinePatch background (canvas-traced).
                new AiasstServiceCardAdapter(this).install();
            }
            return;
        }
        if (SETTINGS_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, loader).install();
            return;
        }
        if (PHONE_PACKAGE.equals(packageName)) {
            // MobileNetworkSettings uses the source-named MIUIX FrameDecoration.
            // Its verified white group drawable and companion Canvas Paint are
            // handled together by this adapter; the generic surface adapter
            // keeps the themed page bitmap beneath the resulting translucent card.
            new SettingsSurfaceAdapter(this, loader).install();
            new PreferenceCardPaintAdapter(this, loader, packageName).install();
            new MobileNetworkSimCardAdapter(this).install();
            return;
        }
        if (MILINK_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, loader).install();
            new PreferenceCardPaintAdapter(this, loader, packageName).install();
            return;
        }
        if (HOME_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, loader).install();
            new PreferenceCardPaintAdapter(this, loader, packageName).install();
            return;
        }
        if (CALENDAR_PACKAGE.equals(packageName)) {
            new CalendarSurfaceAdapter(this).install();
            return;
        }
        if (CLOCK_PACKAGE.equals(packageName)) {
            new ClockSurfaceAdapter(this).install();
            return;
        }
        if (FILE_EXPLORER_PACKAGE.equals(packageName)) {
            new FileExplorerSurfaceAdapter(this).install();
            return;
        }
        if (MESSAGING_PACKAGE.equals(packageName)) {
            new MessagingSurfaceAdapter(this).install();
            // v0.33.25 evidence: this page's flat grey is the theme's own
            // 3x3 flatshow stub, so the owner chose the shared conversation
            // artwork plus transparent action-bar blocks; the card adapter
            // now enforces alpha per draw because list rebinds overwrite it.
            new MessagingVerificationThemeAdapter(this).install();
            new MessagingVerificationCardAdapter(this, loader).install();
            return;
        }
        if (CONTACTS_PACKAGE.equals(packageName)) {
            new MessagingSurfaceAdapter(this, CONTACTS_PACKAGE, "ContactsSurface").install();
            new ContactsCardSurfaceAdapter(this, loader).install();
            return;
        }
        if (RECORDER_PACKAGE.equals(packageName)) {
            new RecorderSettingsSurfaceAdapter(this).install();
            new MessagingSurfaceAdapter(this, RECORDER_PACKAGE, "RecorderSurface").install();
            new RecorderListSurfaceAdapter(this, loader).install();
            return;
        }
        if (GALLERY_PACKAGE.equals(packageName)) {
            new GalleryThemeLayerAdapter(this).install();
            new MessagingSurfaceAdapter(this, GALLERY_PACKAGE, "GallerySurface").install();
            return;
        }
        if (PACKAGE_INSTALLER_PACKAGE.equals(packageName)) {
            new PackageInstallerSurfaceAdapter(this).install();
            return;
        }
        if (THEME_MANAGER_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, loader).install();
            return;
        }

        if (SYSTEM_UI_PACKAGE.equals(packageName)) {
            new SystemUiShadeArtAdapter(this, loader).install();
            return;
        }

        // Bilibili, the store process and the SystemUI plugin have no final
        // runtime transformation. They remain selectable only for future
        // manual work, without resource collection or view hooks.
        if (BILIBILI_PACKAGE.equals(packageName) || THEME_STORE_PACKAGE.equals(packageName)
                || SYSTEM_UI_PLUGIN_PACKAGE.equals(packageName)) {
            return;
        }
        new SettingsSurfaceAdapter(this, loader).install();
    }

    private static boolean isSupported(String packageName) {
        for (String value : SUPPORTED_PACKAGES) {
            if (value.equals(packageName)) return true;
        }
        return false;
    }
}
