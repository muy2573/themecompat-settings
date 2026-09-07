package com.muy257.themecompat.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * Reads images supplied by the *currently applied* theme modules.
 *
 * Theme files under /data/system/theme are intentionally private to
 * system_theme, so a Camera-process hook cannot read them directly.  Root only
 * copies one selected image into Camera's own cache directory, changes the
 * owner back to Camera's UID, and the hook then decodes that file normally.
 * No theme image is packaged in this module.
 */
final class RootThemeBackgroundLoader {
    static final String CAMERA_PACKAGE = "com.android.camera";
    static final String EXTRACTOR_RECEIVER =
            "com.muy257.themecompat.settings.RootThemeExtractorReceiver";
    static final String EXTRA_NIGHT = "com.muy257.themecompat.extra.NIGHT";
    static final String EXTRA_TARGET_PACKAGE =
            "com.muy257.themecompat.extra.TARGET_PACKAGE";
    private static final String THEME_ARCHIVE = "/data/system/theme/com.android.camera";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";
    private static final String BILIBILI_THEME_ARCHIVE = "/data/system/theme/" + BILIBILI_PACKAGE;
    private static final String MIPAY_PACKAGE = "com.mipay.wallet";
    private static final String MIPAY_THEME_ARCHIVE = "/data/system/theme/" + MIPAY_PACKAGE;
    private static final long ROOT_TIMEOUT_SECONDS = 4L;
    private static final int MAX_COMMAND_OUTPUT = 256 * 1024;
    private static final boolean[] EXTRACTION_REQUESTED = new boolean[2];
    private static final boolean[] BILIBILI_EXTRACTION_REQUESTED = new boolean[2];
    private static final boolean[] MIPAY_EXTRACTION_REQUESTED = new boolean[2];
    private static final Pattern ZIP_ENTRY = Pattern.compile("\\s([^\\s]+)$");
    private static final Pattern FALLBACK_DRAWABLE = Pattern.compile(
            "<drawable\\s+[^>]*name\\s*=\\s*['\\\"]([^'\\\"]+)[^>]*>\\s*([^<]+?)\\s*</drawable>",
            Pattern.CASE_INSENSITIVE);

    private RootThemeBackgroundLoader() {}

    /**
     * Runs in the DenyList-protected Camera process. It never executes su: it
     * asks our companion process to refresh the cache, then reads only files
     * that are owned by Camera itself.
     */
    static Drawable load(Activity activity, boolean night, XposedModule module) {
        if (activity == null || activity.getCacheDir() == null) return null;
        File output = cacheFile(activity, night);
        File ready = readyFile(activity, night);
        try {
            if (!ready.isFile()) {
                requestExtraction(activity, night, module);
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(output.getAbsolutePath());
            if (bitmap == null) {
                ready.delete();
                module.log(Log.WARN, "CameraRootTheme",
                        "Camera cache contained no decodable active-theme bitmap");
                return null;
            }
            BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
            drawable.setGravity(Gravity.FILL);
            module.log(Log.INFO, "CameraRootTheme", "Loaded Root-refreshed Camera cache mode="
                    + (night ? "dark" : "light") + " size="
                    + bitmap.getWidth() + 'x' + bitmap.getHeight());
            return drawable;
        } catch (Throwable error) {
            module.log(Log.WARN, "CameraRootTheme",
                    "Cannot read Camera-owned Root theme cache", error);
            return null;
        }
    }

    /**
     * Reads Bilibili's own active theme image into a Bilibili-owned cache.
     * The image remains supplied by the selected theme; no art is packaged in
     * this compatibility APK.
     */
    static Drawable loadForBilibili(Activity activity, boolean night, XposedModule module) {
        if (activity == null || activity.getCacheDir() == null
                || !BILIBILI_PACKAGE.equals(activity.getPackageName())) return null;
        File output = biliCacheFile(activity, night);
        File ready = biliReadyFile(activity, night);
        try {
            if (!ready.isFile()) {
                requestBilibiliExtraction(activity, night, module);
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(output.getAbsolutePath());
            if (bitmap == null) {
                ready.delete();
                module.log(Log.WARN, "BilibiliRootTheme",
                        "Bilibili cache contained no decodable active-theme bitmap");
                return null;
            }
            BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
            drawable.setGravity(Gravity.CENTER);
            module.log(Log.INFO, "BilibiliRootTheme", "Loaded Root-refreshed Bilibili cache mode="
                    + (night ? "dark" : "light") + " size="
                    + bitmap.getWidth() + 'x' + bitmap.getHeight());
            return drawable;
        } catch (Throwable error) {
            module.log(Log.WARN, "BilibiliRootTheme",
                    "Cannot read Bilibili-owned Root theme cache", error);
            return null;
        }
    }

    /**
     * Supplies Xiaomi Wallet's existing theme window image to its self-drawn
     * home surface.  Wallet no longer exposes the historical window_bg
     * resource ID, so the app cannot request this image through normal theme
     * resolution.  The image still comes only from the currently applied
     * com.mipay.wallet module; this compatibility APK ships no artwork.
     */
    static Drawable loadForMipay(Activity activity, boolean night, XposedModule module) {
        if (activity == null || activity.getCacheDir() == null
                || !MIPAY_PACKAGE.equals(activity.getPackageName())) return null;
        File output = mipayCacheFile(activity, night);
        File ready = mipayReadyFile(activity, night);
        try {
            if (!ready.isFile()) {
                requestMipayExtraction(activity, night, module);
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(output.getAbsolutePath());
            if (bitmap == null) {
                ready.delete();
                module.log(Log.WARN, "MipayRootTheme",
                        "Wallet cache contained no decodable active-theme bitmap");
                return null;
            }
            BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
            drawable.setGravity(Gravity.FILL);
            module.log(Log.INFO, "MipayRootTheme", "Loaded active-theme Wallet cache mode="
                    + (night ? "dark" : "light") + " size="
                    + bitmap.getWidth() + 'x' + bitmap.getHeight());
            return drawable;
        } catch (Throwable error) {
            module.log(Log.WARN, "MipayRootTheme",
                    "Cannot read Wallet-owned Root theme cache", error);
            return null;
        }
    }

    /** Runs in the companion module-app process, which is not Camera's DenyList process. */
    static void extractForCameraCache(Context context, boolean night) throws Exception {
        Context cameraContext = context.createPackageContext(CAMERA_PACKAGE, 0);
        File output = cacheFile(cameraContext, night);
        File ready = readyFile(cameraContext, night);
        int cameraUid = context.getPackageManager().getApplicationInfo(CAMERA_PACKAGE, 0).uid;
        List<String> entries = listEntries(THEME_ARCHIVE);
        String entry = chooseEntry(entries, night);
        if (entry == null) {
            throw new IllegalStateException("No compatible window background in active Camera theme module");
        }
        copyAsOwnedFile(THEME_ARCHIVE, entry, output, cameraUid);
        markReady(ready, cameraUid);
        Log.i("ThemeCompatRoot", "Extracted active Camera theme entry=" + entry
                + " mode=" + (night ? "dark" : "light"));
    }

    /** Runs in the companion module-app process, outside Bilibili's process. */
    static void extractForBilibiliCache(Context context, boolean night) throws Exception {
        Context biliContext = context.createPackageContext(BILIBILI_PACKAGE, 0);
        File output = biliCacheFile(biliContext, night);
        File ready = biliReadyFile(biliContext, night);
        int biliUid = context.getPackageManager().getApplicationInfo(BILIBILI_PACKAGE, 0).uid;
        List<String> entries = listEntries(BILIBILI_THEME_ARCHIVE);
        String entry = chooseBilibiliEntry(entries, night);
        if (entry == null) {
            throw new IllegalStateException("No Bilibili splash image in active theme module");
        }
        copyAsOwnedFile(BILIBILI_THEME_ARCHIVE, entry, output, biliUid);
        markReady(ready, biliUid);
        Log.i("ThemeCompatRoot", "Extracted active Bilibili theme entry=" + entry
                + " mode=" + (night ? "dark" : "light"));
    }

    /** Runs in the companion module-app process, outside Wallet's process. */
    static void extractForMipayCache(Context context, boolean night) throws Exception {
        Context mipayContext = context.createPackageContext(MIPAY_PACKAGE, 0);
        File output = mipayCacheFile(mipayContext, night);
        File ready = mipayReadyFile(mipayContext, night);
        int mipayUid = context.getPackageManager().getApplicationInfo(MIPAY_PACKAGE, 0).uid;
        List<String> entries = listEntries(MIPAY_THEME_ARCHIVE);
        String entry = chooseMipayEntry(entries, night);
        if (entry == null) {
            throw new IllegalStateException("No compatible window background in active Wallet theme module");
        }
        copyAsOwnedFile(MIPAY_THEME_ARCHIVE, entry, output, mipayUid);
        markReady(ready, mipayUid);
        Log.i("ThemeCompatRoot", "Extracted active Wallet theme entry=" + entry
                + " mode=" + (night ? "dark" : "light"));
    }

    private static File cacheFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? "themecompat-camera-window-dark.png"
                : "themecompat-camera-window-light.png");
    }

    private static File readyFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? ".themecompat-camera-window-dark.ready"
                : ".themecompat-camera-window-light.ready");
    }

    private static File biliCacheFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? "themecompat-bilibili-splash-dark.png"
                : "themecompat-bilibili-splash-light.png");
    }

    private static File biliReadyFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? ".themecompat-bilibili-splash-dark.ready"
                : ".themecompat-bilibili-splash-light.ready");
    }

    private static File mipayCacheFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? "themecompat-mipay-window-dark.png"
                : "themecompat-mipay-window-light.png");
    }

    private static File mipayReadyFile(Context context, boolean night) {
        return new File(context.getCacheDir(), night
                ? ".themecompat-mipay-window-dark.ready"
                : ".themecompat-mipay-window-light.ready");
    }

    private static void requestExtraction(Activity activity, boolean night, XposedModule module) {
        int mode = night ? 1 : 0;
        synchronized (EXTRACTION_REQUESTED) {
            if (EXTRACTION_REQUESTED[mode]) return;
            EXTRACTION_REQUESTED[mode] = true;
        }
        File ready = readyFile(activity, night);
        // Camera owns this file, so it can invalidate the old ready marker before
        // requesting a fresh extraction without any Root operation in this process.
        ready.delete();
        Intent request = new Intent();
        request.setComponent(new ComponentName("com.muy257.themecompat", EXTRACTOR_RECEIVER));
        request.putExtra(EXTRA_NIGHT, night);
        request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            activity.sendBroadcast(request);
            module.log(Log.INFO, "CameraRootTheme", "Requested companion extraction mode="
                    + (night ? "dark" : "light"));
        } catch (Throwable error) {
            module.log(Log.WARN, "CameraRootTheme", "Cannot request companion extraction", error);
        }
    }

    private static void requestBilibiliExtraction(Activity activity, boolean night,
            XposedModule module) {
        int mode = night ? 1 : 0;
        synchronized (BILIBILI_EXTRACTION_REQUESTED) {
            if (BILIBILI_EXTRACTION_REQUESTED[mode]) return;
            BILIBILI_EXTRACTION_REQUESTED[mode] = true;
        }
        File ready = biliReadyFile(activity, night);
        ready.delete();
        Intent request = new Intent();
        request.setComponent(new ComponentName("com.muy257.themecompat", EXTRACTOR_RECEIVER));
        request.putExtra(EXTRA_NIGHT, night);
        request.putExtra(EXTRA_TARGET_PACKAGE, BILIBILI_PACKAGE);
        request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            activity.sendBroadcast(request);
            module.log(Log.INFO, "BilibiliRootTheme",
                    "Requested companion extraction mode=" + (night ? "dark" : "light"));
        } catch (Throwable error) {
            module.log(Log.WARN, "BilibiliRootTheme",
                    "Cannot request companion extraction", error);
        }
    }

    private static void requestMipayExtraction(Activity activity, boolean night,
            XposedModule module) {
        int mode = night ? 1 : 0;
        synchronized (MIPAY_EXTRACTION_REQUESTED) {
            if (MIPAY_EXTRACTION_REQUESTED[mode]) return;
            MIPAY_EXTRACTION_REQUESTED[mode] = true;
        }
        File ready = mipayReadyFile(activity, night);
        ready.delete();
        Intent request = new Intent();
        request.setComponent(new ComponentName("com.muy257.themecompat", EXTRACTOR_RECEIVER));
        request.putExtra(EXTRA_NIGHT, night);
        request.putExtra(EXTRA_TARGET_PACKAGE, MIPAY_PACKAGE);
        request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            activity.sendBroadcast(request);
            module.log(Log.INFO, "MipayRootTheme",
                    "Requested companion extraction mode=" + (night ? "dark" : "light"));
        } catch (Throwable error) {
            module.log(Log.WARN, "MipayRootTheme", "Cannot request companion extraction", error);
        }
    }

    private static List<String> listEntries(String archive) throws Exception {
        String listing = runRoot("unzip -l " + quote(archive));
        List<String> entries = new ArrayList<>();
        for (String line : listing.split("\\r?\\n")) {
            Matcher match = ZIP_ENTRY.matcher(line);
            if (!match.find()) continue;
            String path = match.group(1);
            if (path.startsWith("res/") || path.startsWith("nightmode/res/")) entries.add(path);
        }
        return entries;
    }

    private static String chooseEntry(List<String> entries, boolean night) throws Exception {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (night) {
            candidates.addAll(Arrays.asList(
                    "nightmode/res/drawable-xxhdpi/miuix_appcompat_settings_window_bg_dark.9.png",
                    "nightmode/res/drawable/miuix_appcompat_settings_window_bg_dark.9.png",
                    "nightmode/res/window_bg_dark.9.png",
                    "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_dark.9.png",
                    "nightmode/res/drawable/miuix_appcompat_window_bg_dark.9.png",
                    "res/drawable-xxhdpi/miuix_appcompat_settings_window_bg_dark.9.png",
                    "res/drawable/miuix_appcompat_settings_window_bg_dark.9.png",
                    "res/window_bg_dark.9.png",
                    "res/drawable-xxhdpi/miuix_appcompat_window_bg_dark.9.png",
                    "res/drawable/miuix_appcompat_window_bg_dark.9.png"));
        } else {
            candidates.addAll(Arrays.asList(
                    "res/drawable-xxhdpi/miuix_appcompat_window_bg_light.9.png",
                    "res/drawable/miuix_appcompat_window_bg_light.9.png",
                    "res/drawable-xxhdpi/miuix_appcompat_settings_window_bg_light.9.png",
                    "res/drawable/miuix_appcompat_settings_window_bg_light.9.png",
                    "res/window_bg_light.9.png"));
        }
        candidates.addAll(fallbackTargets(night));
        for (String candidate : candidates) {
            String hit = findEntry(entries, candidate, night);
            if (hit != null) return hit;
        }

        // Last resort for third-party themes with a different fallback table:
        // choose a mode-specific window background, never an arbitrary image.
        String marker = night ? "dark" : "light";
        for (String entry : entries) {
            if (entry.endsWith(".png") && entry.contains("window_bg")
                    && entry.toLowerCase().contains(marker)
                    && (night == entry.startsWith("nightmode/") || !night)) return entry;
        }
        return null;
    }

    private static String chooseBilibiliEntry(List<String> entries, boolean night) {
        String preferred = night ? "nightmode/res/wVL.png" : "res/wVL.png";
        String hit = findEntry(entries, preferred, night);
        if (hit != null) return hit;
        // Keep this fallback specific to the existing Bilibili module's splash
        // naming convention.  Arbitrary theme images are never selected.
        String alternate = night ? "nightmode/res/0HZ.png" : "res/0HZ.png";
        return findEntry(entries, alternate, night);
    }

    private static String chooseMipayEntry(List<String> entries, boolean night) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (night) {
            candidates.addAll(Arrays.asList(
                    "nightmode/res/drawable-xxhdpi/window_bg_dark.9.png",
                    "nightmode/res/drawable-xhdpi/window_bg_dark.9.png",
                    "nightmode/res/drawable-xxhdpi/miuix_appcompat_immersion_window_bg_dark.9.png",
                    "nightmode/res/drawable-xhdpi/miuix_appcompat_immersion_window_bg_dark.9.png"));
        }
        candidates.addAll(Arrays.asList(
                "res/drawable-xxhdpi/window_bg_light.9.png",
                "res/drawable-xhdpi/window_bg_light.9.png",
                "res/drawable-xxhdpi/miuix_appcompat_immersion_window_bg_light.9.png",
                "res/drawable-xhdpi/miuix_appcompat_immersion_window_bg_light.9.png"));
        for (String candidate : candidates) {
            String hit = findEntry(entries, candidate, night);
            if (hit != null) return hit;
        }
        return null;
    }

    private static List<String> fallbackTargets(boolean night) throws Exception {
        String xml;
        try {
            xml = runRoot("unzip -p " + quote(THEME_ARCHIVE) + " "
                    + quote(night ? "nightmode/theme_fallback.xml" : "theme_fallback.xml"));
        } catch (Throwable ignored) {
            // Third-party modules may omit a fallback table; exact ZIP-name
            // candidates and the mode-specific fallback scan still work.
            return new ArrayList<>();
        }
        String desired = night ? "dark" : "light";
        List<String> results = new ArrayList<>();
        Matcher matcher = FALLBACK_DRAWABLE.matcher(xml);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String target = matcher.group(2).trim();
            if (name.contains("window_bg") && name.toLowerCase().contains(desired)
                    && target.endsWith(".png")) {
                results.add(target);
            }
        }
        return results;
    }

    private static String findEntry(List<String> entries, String candidate, boolean night) {
        if (entries.contains(candidate)) return candidate;
        String fileName = candidate.substring(candidate.lastIndexOf('/') + 1);
        String preferredPrefix = night ? "nightmode/res/" : "res/";
        for (String entry : entries) {
            if (entry.startsWith(preferredPrefix) && entry.endsWith('/' + fileName)) return entry;
        }
        for (String entry : entries) {
            if (entry.endsWith('/' + fileName)) return entry;
        }
        return null;
    }

    private static void copyAsOwnedFile(String archive, String entry, File output, int ownerUid)
            throws Exception {
        File parent = output.getParentFile();
        if (parent == null) throw new IllegalStateException("Camera cache has no parent");
        File temporary = new File(parent, output.getName() + ".tmp");
        String command = "mkdir -p " + quote(parent.getAbsolutePath())
                + " && rm -f " + quote(temporary.getAbsolutePath())
                + " && unzip -p " + quote(archive) + ' ' + quote(entry)
                + " > " + quote(temporary.getAbsolutePath())
                + " && chown " + ownerUid + ':' + ownerUid + ' ' + quote(temporary.getAbsolutePath())
                + " && chmod 600 " + quote(temporary.getAbsolutePath())
                + " && mv -f " + quote(temporary.getAbsolutePath()) + ' ' + quote(output.getAbsolutePath());
        runRoot(command);
        if (!output.isFile() || output.length() == 0) {
            throw new IllegalStateException("Root did not create theme cache file");
        }
    }

    private static void markReady(File ready, int ownerUid) throws Exception {
        File parent = ready.getParentFile();
        if (parent == null) throw new IllegalStateException("Camera cache has no parent");
        File temporary = new File(parent, ready.getName() + ".tmp");
        String command = "rm -f " + quote(temporary.getAbsolutePath())
                + " && touch " + quote(temporary.getAbsolutePath())
                + " && chown " + ownerUid + ':' + ownerUid + ' ' + quote(temporary.getAbsolutePath())
                + " && chmod 600 " + quote(temporary.getAbsolutePath())
                + " && mv -f " + quote(temporary.getAbsolutePath()) + ' ' + quote(ready.getAbsolutePath());
        runRoot(command);
    }

    private static String runRoot(String command) throws Exception {
        java.lang.Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1 && output.length() < MAX_COMMAND_OUTPUT) {
                output.append(buffer, 0, count);
            }
        }
        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Root command timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Root command exit=" + process.exitValue()
                    + " output=" + output);
        }
        return output.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }
}
