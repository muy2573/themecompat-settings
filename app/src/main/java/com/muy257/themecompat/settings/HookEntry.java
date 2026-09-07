package com.muy257.themecompat.settings;

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
        CardAlpha.bind(this);
        HookSwitches.bindIfPossible(this);
        synchronized (INSTALLED_PACKAGES) {
            if (!INSTALLED_PACKAGES.add(packageName)) return;
        }
        boolean hookEnabled = HookSwitches.enabled(packageName);
        log(android.util.Log.INFO, "HookEntry",
                "switch " + packageName + " enabled=" + hookEnabled);
        if (!hookEnabled) {
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
            new ObfuscatedCardDecorationAdapter(this, param.getDefaultClassLoader(),
                    packageName).install();
        }

        // These four packages were observed on this phone to paint the opaque
        // light card in a concrete RecyclerView ItemDecoration.  Install this
        // before the broad page-surface adapter; it edits no View background
        // and makes no dark-mode write.
        if (VerifiedLightCardDecorationAdapter.supports(packageName)) {
            new VerifiedLightCardDecorationAdapter(this, param.getDefaultClassLoader(), packageName).install();
        }

        // Exact full-page activities proved to need the Settings surface
        // adapter. The resource and hierarchy traces used to discover them are
        // deliberately absent from the finished build.
        if (SettingsSurfaceAdapter.supportsHostedSettingsPackage(packageName)
                || SECURITY_CENTER_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            if (SECURITY_CENTER_PACKAGE.equals(packageName)) {
                new SecurityCenterCardSurfaceAdapter(this, param.getDefaultClassLoader()).install();
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
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            return;
        }
        if (PHONE_PACKAGE.equals(packageName)) {
            // MobileNetworkSettings uses the source-named MIUIX FrameDecoration.
            // Its verified white group drawable and companion Canvas Paint are
            // handled together by this adapter; the generic surface adapter
            // keeps the themed page bitmap beneath the resulting translucent card.
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            new PreferenceCardPaintAdapter(this, param.getDefaultClassLoader(), packageName).install();
            new MobileNetworkSimCardAdapter(this).install();
            return;
        }
        if (MILINK_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            new PreferenceCardPaintAdapter(this, param.getDefaultClassLoader(), packageName).install();
            return;
        }
        if (HOME_PACKAGE.equals(packageName)) {
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            new PreferenceCardPaintAdapter(this, param.getDefaultClassLoader(), packageName).install();
            return;
        }
        if (CAMERA_PACKAGE.equals(packageName)) {
            new CameraSurfaceAdapter(this).install();
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
            new MessagingVerificationCardAdapter(this, param.getDefaultClassLoader()).install();
            return;
        }
        if (CONTACTS_PACKAGE.equals(packageName)) {
            new MessagingSurfaceAdapter(this, CONTACTS_PACKAGE, "ContactsSurface").install();
            new ContactsCardSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            return;
        }
        if (RECORDER_PACKAGE.equals(packageName)) {
            new RecorderSettingsSurfaceAdapter(this).install();
            new MessagingSurfaceAdapter(this, RECORDER_PACKAGE, "RecorderSurface").install();
            new RecorderListSurfaceAdapter(this, param.getDefaultClassLoader()).install();
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
            new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
            return;
        }

        if (SYSTEM_UI_PACKAGE.equals(packageName)) {
            new SystemUiShadeArtAdapter(this, param.getDefaultClassLoader()).install();
            return;
        }

        // Bilibili, the store process and the SystemUI plugin have no final
        // runtime transformation. They remain selectable only for future
        // manual work, without resource collection or view hooks.
        if (BILIBILI_PACKAGE.equals(packageName) || THEME_STORE_PACKAGE.equals(packageName)
                || SYSTEM_UI_PLUGIN_PACKAGE.equals(packageName)) {
            return;
        }
        new SettingsSurfaceAdapter(this, param.getDefaultClassLoader()).install();
    }

    private static boolean isSupported(String packageName) {
        for (String value : SUPPORTED_PACKAGES) {
            if (value.equals(packageName)) return true;
        }
        return false;
    }
}
