package com.muy257.themecompat.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.muy257.themecompat.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes a derivative MTZ without bundling any image of its own.  For the
 * known K70U surface modules, missing MIUIX window aliases are copied from the
 * selected theme's own window image. Existing resources are never overwritten.
 * Known File Explorer and Messaging filename faults are corrected only when
 * their intended source asset already exists in the selected module.
 */
final class MtzCompatibilityPatcher {
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long MAX_MODULE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_XML_BYTES = 2L * 1024L * 1024L;
    private static final String DESCRIPTION = "description.xml";
    private static final String SETTINGS_MODULE = "com.android.settings";
    private static final String FILE_EXPLORER_MODULE = "com.android.fileexplorer";
    private static final String MMS_MODULE = "com.android.mms";
    private static final String THEME_MANAGER_MODULE = "com.android.thememanager";
    private static final String MIPAY_MODULE = "com.mipay.wallet";
    private static final String SECURITY_CENTER_MODULE = "com.miui.securitycenter";
    private static final String SECURITY_CENTER_NIGHT_FALLBACK = "nightmode/theme_fallback.xml";
    private static final String ACCESSIBILITY_ICON_LIGHT = "res/drawable-xxhdpi/ic_accessibility_function.png";
    private static final String ACCESSIBILITY_ICON_DARK =
            "nightmode/res/drawable-xxhdpi/ic_accessibility_function.png";
    private static final String MIPAY_LIGHT_SPLASH =
            "res/drawable-xxhdpi/app_brand.webp";
    private static final String MIPAY_DARK_SPLASH =
            "nightmode/res/drawable-xxhdpi/app_brand.webp";
    private static final String COMPASS_MODULE = "com.miui.compass";
    private static final String NOTIFICATION_MODULE = "com.miui.notification";
    private static final String AIASST_SERVICE_MODULE = "com.xiaomi.aiasst.service";
    private static final String AI_VISION_MODULE = "com.xiaomi.aiasst.vision";
    private static final String CLEAN_MASTER_MODULE = "com.miui.cleanmaster";
    private static final String MMS_DARK_TYPO_PATH =
            "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_drak.9.png";
    private static final String CONTACTS_DARK_CANVAS =
            "nightmode/framework-miui-res/res/drawable-xxhdpi/window_bg_dark.9.png";
    private static final String THEME_MANAGER_DARK_CANVAS =
            "nightmode/res/drawable-xxhdpi/window_bg_dark.9.png";
    private static final String MMS_VERIFICATION_LIST_BACKGROUND =
            "res/drawable-xxhdpi/window_bg_verification_list.9.png";
    private static final String MMS_CONVERSATION_BACKGROUND =
            "res/drawable-xxhdpi/conversation_bg.9.png";
    /*
     * In the original File Explorer module, this is the intended dark canvas
     * despite its historical "light" filename.  It must never be substituted
     * with the Settings module's global dark donor.
     */
    private static final String FILE_EXPLORER_NIGHT_CANVAS =
            "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_light.9.png";
    private static final String[] TARGET_MODULES = {
            "com.android.settings", "com.android.camera", "com.android.calendar",
            "com.android.deskclock", "com.android.fileexplorer", "com.android.contacts",
            "com.android.mms", "com.android.soundrecorder", "com.miui.gallery",
            "com.miui.packageinstaller", THEME_MANAGER_MODULE, MIPAY_MODULE,
            /*
             * Dark-mode audit additions: these modules display the themed
             * light wallpaper but ship no night canvas of their own.  Each
             * receives a borrowed dark canvas from the sibling module the
             * owner matched it with (see externalDarkDonor).
             */
            "com.miui.compass", "com.miui.notification",
            AIASST_SERVICE_MODULE, AI_VISION_MODULE, CLEAN_MASTER_MODULE
    };
    private static final Set<String> TARGET_MODULE_SET = new HashSet<>(Arrays.asList(TARGET_MODULES));
    private static final String[] BASE_ALIASES = {
            "window_bg_%s.9.png",
            "miuix_appcompat_window_bg_%s.9.png",
            "miuix_appcompat_settings_window_bg_%s.9.png",
            "miuix_appcompat_window_bg_secondary_%s.9.png"
    };
    private static final Pattern VERSION = Pattern.compile(
            "(?s)<version>\\s*<!\\[CDATA\\[([0-9]+)]]>\\s*</version>");
    private static final Pattern TITLE = Pattern.compile(
            "(?s)<title(\\s+[^>]*)?>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</title>");
    private static final Pattern DESCRIPTION_ELEMENT = Pattern.compile(
            "(?s)<description(\\s+[^>]*)?>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</description>");

    private MtzCompatibilityPatcher() { }

    interface Progress {
        void update(String message);
    }

    static final class Result {
        final String outputName;
        final String report;

        Result(String outputName, String report) {
            this.outputName = outputName;
            this.report = report;
        }
    }

    static Result patch(Context context, Uri source, Uri destination, Progress progress) throws IOException {
        if (context == null || source == null || destination == null) {
            throw new IOException("未提供输入或输出主题文件");
        }
        ContentResolver resolver = context.getContentResolver();
        progress.update("步骤 1/4：读取原版 mtz 并扫描深色画布来源…");
        ExternalDonors external = scanExternalDonors(resolver, source, progress);
        progress.update("画布扫描完成：" + donorSummary(external));
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) throw new IOException("应用缓存目录不可用");
        File temporary = File.createTempFile("themecompat-k70u-", ".mtz", cacheDir);
        List<String> changes = new ArrayList<>();
        byte[] a11yLight = readBundledAsset(context, R.raw.ic_accessibility_function_light);
        byte[] a11yDark = readBundledAsset(context, R.raw.ic_accessibility_function_dark);
        try {
            progress.update("步骤 2/4：逐模块写入静态修补（模块多时需要几十秒）…");
            writePatchedArchive(resolver, source, temporary, external, changes, progress,
                    a11yLight, a11yDark);
            progress.update("步骤 3/4：写出适配包到应用缓存（" + temporary.getName() + "）…");
            try (InputStream input = new FileInputStream(temporary);
                 OutputStream output = requireOutput(resolver, destination)) {
                copy(input, output);
            }
            progress.update("步骤 4/4：复制到所选位置完成。");
            String report = buildReport(changes, external.global);
            return new Result("所选位置", report);
        } finally {
            // This is only the app-owned staging file; the original and output documents remain untouched.
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static String donorSummary(ExternalDonors external) {
        StringBuilder summary = new StringBuilder();
        summary.append("设置全局浅色").append(external.global.light == null ? "缺" : "✓")
                .append("/深色").append(external.global.dark == null ? "缺" : "✓");
        appendCanvas(summary, "短信", external.mmsDark);
        appendCanvas(summary, "通讯录", external.contactsDark);
        appendCanvas(summary, "文件管理", external.fileExplorerDark);
        appendCanvas(summary, "主题商店", external.themeManagerDark);
        return summary.toString();
    }

    private static void appendCanvas(StringBuilder summary, String label, byte[] canvas) {
        if (canvas == null) return;
        summary.append("；").append(label).append("深色 ✓(")
                .append(canvas.length / 1024).append("KB)");
    }

    private static void writePatchedArchive(ContentResolver resolver, Uri source, File temporary,
                                            ExternalDonors external, List<String> changes,
                                            Progress progress, byte[] a11yLight,
                                            byte[] a11yDark) throws IOException {
        boolean foundDescription = false;
        int outerEntries = 0;
        try (InputStream raw = requireInput(resolver, source);
             ZipInputStream input = new ZipInputStream(raw);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temporary))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                outerEntries++;
                String name = entry.getName();
                if (!isSafeEntryName(name)) throw new IOException("主题包含不安全 ZIP 路径：" + name);
                if (DESCRIPTION.equals(name)) {
                    progress.update("正在更新 description.xml（版本号 +1，追加适配标记）…");
                    byte[] original = readAll(input, MAX_XML_BYTES);
                    byte[] adapted = patchDescription(original);
                    writeEntry(output, entry, adapted);
                    foundDescription = true;
                } else if (TARGET_MODULE_SET.contains(name)) {
                    byte[] original = readAll(input, MAX_MODULE_BYTES);
                    progress.update("正在修补：" + humanName(name)
                            + "（读取 " + original.length / 1024 + "KB 模块）…");
                    ModulePatch patch = patchModule(name, original, external, progress,
                            a11yLight, a11yDark);
                    writeEntry(output, entry, patch.bytes);
                    if (patch.additions > 0 || patch.fallbacks > 0 || patch.removals > 0) {
                        String line = humanName(name) + "：" + patch.detail;
                        changes.add(line);
                        progress.update("完成：" + line);
                    } else if (patch.duplicateEntries > 0) {
                        progress.update("完成：" + humanName(name)
                                + "（仅忽略 " + patch.duplicateEntries + " 条重复条目，无资源改动）");
                    }
                } else {
                    copyEntry(input, output, entry);
                }
                input.closeEntry();
            }
        }
        if (!foundDescription) throw new IOException("所选文件不是 MTZ：缺少 description.xml");
        if (outerEntries < 3) throw new IOException("所选文件不是完整 MTZ");
    }

    /**
     * One pre-pass over the source archive: the Settings global donors plus
     * the four night canvases the dark-mode audit borrows across modules
     * (Messaging for Compass/Notification, Contacts for the AI call page and
     * Recorder, File Explorer for Clean Master and Translate, theme store for
     * Gallery).
     */
    private static ExternalDonors scanExternalDonors(ContentResolver resolver, Uri source,
                                                     Progress progress) throws IOException {
        ExternalDonors external = new ExternalDonors();
        try (InputStream raw = requireInput(resolver, source);
             ZipInputStream input = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (SETTINGS_MODULE.equals(name)) {
                    progress.update("扫描：设置模块的全局浅/深色画布…");
                    byte[] module = readAll(input, MAX_MODULE_BYTES);
                    external.global.merge(readSnapshot(module).donors);
                } else if (MMS_MODULE.equals(name)) {
                    progress.update("扫描：短信模块夜间画布（" + MMS_DARK_TYPO_PATH + "）…");
                    external.mmsDark = findModuleAsset(input, MMS_DARK_TYPO_PATH);
                } else if ("com.android.contacts".equals(name)) {
                    progress.update("扫描：通讯录模块夜间画布（" + CONTACTS_DARK_CANVAS + "）…");
                    external.contactsDark = findModuleAsset(input, CONTACTS_DARK_CANVAS);
                } else if (FILE_EXPLORER_MODULE.equals(name)) {
                    progress.update("扫描：文件管理模块夜间画布（" + FILE_EXPLORER_NIGHT_CANVAS + "）…");
                    external.fileExplorerDark = findModuleAsset(input, FILE_EXPLORER_NIGHT_CANVAS);
                } else if (THEME_MANAGER_MODULE.equals(name)) {
                    progress.update("扫描：主题商店模块夜间画布（" + THEME_MANAGER_DARK_CANVAS + "）…");
                    external.themeManagerDark = findModuleAsset(input, THEME_MANAGER_DARK_CANVAS);
                }
                input.closeEntry();
            }
        }
        return external;
    }

    /** Streams one already-opened module zip and returns the first wanted entry. */
    private static byte[] findModuleAsset(InputStream module, String wanted) throws IOException {
        try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(readAll(module, MAX_MODULE_BYTES)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (wanted.equals(entry.getName())) {
                    return readAll(input, MAX_MODULE_BYTES);
                }
                input.closeEntry();
            }
        }
        return null;
    }

    /**
     * The borrowed night canvases are deliberate audit decisions: Compass and
     * Notification share Messaging's artwork, the AI call page shares
     * Contacts', and Clean Master and Translate share File Explorer's.
     * Everything else keeps the module's own donor chain, and the Settings
     * global donor is still never borrowed as a generic fallback.
     */
    private static byte[] externalDarkDonor(String moduleName, ExternalDonors external) {
        if (external == null) return null;
        switch (moduleName) {
            case COMPASS_MODULE:
            case NOTIFICATION_MODULE:
                return external.mmsDark;
            case AIASST_SERVICE_MODULE:
            case "com.android.soundrecorder":
                return external.contactsDark;
            case CLEAN_MASTER_MODULE:
            case AI_VISION_MODULE:
                return external.fileExplorerDark;
            case "com.miui.gallery":
                return external.themeManagerDark;
            default:
                return null;
        }
    }

    private static String externalDarkDonorName(String moduleName) {
        switch (moduleName) {
            case COMPASS_MODULE:
            case NOTIFICATION_MODULE:
                return "短信";
            case AIASST_SERVICE_MODULE:
            case "com.android.soundrecorder":
                return "通讯录";
            case CLEAN_MASTER_MODULE:
            case AI_VISION_MODULE:
                return "文件管理";
            case "com.miui.gallery":
                return "主题商店";
            default:
                return null;
        }
    }

    private static ModulePatch patchModule(String moduleName, byte[] original,
                                           ExternalDonors external, Progress progress,
                                           byte[] a11yLight, byte[] a11yDark) throws IOException {
        if (!isZip(original)) throw new IOException(moduleName + " 不是有效的主题模块 ZIP");
        ModuleSnapshot snapshot = readSnapshot(original);
        LinkedHashMap<String, byte[]> additions = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (SETTINGS_MODULE.equals(moduleName)) {
            int a11yAdded = repairSettingsAccessibilityIcon(snapshot, additions, a11yLight, a11yDark);
            if (a11yAdded > 0) notes.add("补齐辅助功能磁贴图标 ×" + a11yAdded);
        }
        int mmsDarkAliases = repairMmsDarkWindowAliases(moduleName, snapshot, additions);
        boolean mmsDarkAliasesHandled = mmsDarkAliases > 0;
        if (mmsDarkAliases > 0) {
            notes.add("修正夜间 drak 拼写并补齐深色别名 ×" + mmsDarkAliases);
        }
        int themeManagerPaths = repairThemeManagerWindowPaths(moduleName, snapshot, additions);
        if (themeManagerPaths > 0) {
            notes.add("补齐无密度窗口路径 ×" + themeManagerPaths);
        }
        if (repairMipayDarkSplash(moduleName, snapshot, additions)) {
            notes.add("补齐夜间启动图 ×1（res→nightmode app_brand.webp）");
        }
        if (!MIPAY_MODULE.equals(moduleName) && !FILE_EXPLORER_MODULE.equals(moduleName)) {
            Donors own = snapshot.donors;
            for (String mode : new String[]{"light", "dark"}) {
                if ("dark".equals(mode) && mmsDarkAliasesHandled) continue;
                /*
                 * A module without its own night canvas may borrow the
                 * sibling canvas chosen by the dark-mode audit; the Settings
                 * global donor is still never borrowed generically.
                 */
                byte[] effectiveDark = own.dark != null
                        ? own.dark
                        : externalDarkDonor(moduleName, external);
                byte[] donor = "light".equals(mode) ? own.light : effectiveDark;
                if (donor == null) continue;
                String prefix = "light".equals(mode) ? "res/drawable-xxhdpi/"
                        : "nightmode/res/drawable-xxhdpi/";
                int added = 0;
                for (String pattern : BASE_ALIASES) {
                    String path = prefix + String.format(Locale.ROOT, pattern, mode);
                    if (!snapshot.names.contains(path)) {
                        additions.put(path, donor);
                        added++;
                    }
                }
                if (added > 0) {
                    if ("light".equals(mode)) {
                        notes.add("补齐浅色别名 ×" + added);
                    } else {
                        String borrowed = own.dark == null ? externalDarkDonorName(moduleName) : null;
                        notes.add("补齐深色别名 ×" + added
                                + (borrowed == null ? "" : "（借自 " + borrowed + "）"));
                    }
                }
            }
        }

        if ("com.android.contacts".equals(moduleName)) {
            int searchAliases = addContactsSearchAlias(snapshot, additions,
                    "res/drawable-xxhdpi/",
                    "search_mode_edit_text_bg_light.9.png",
                    "miuix_appcompat_search_mode_edit_text_bg_light.9.png");
            searchAliases += addContactsSearchAlias(snapshot, additions,
                    "nightmode/res/drawable-xxhdpi/",
                    "search_mode_edit_text_bg_dark.9.png",
                    "miuix_appcompat_search_mode_edit_text_bg_dark.9.png");
            if (searchAliases > 0) notes.add("补齐搜索框别名 ×" + searchAliases);
        }

        Map<String, byte[]> replacements = new HashMap<>();
        Set<String> removals = new HashSet<>();
        if (SECURITY_CENTER_MODULE.equals(moduleName)) {
            int darkAliases = repairSecurityCenterDarkAliases(snapshot, replacements);
            if (darkAliases > 0) notes.add("补齐夜间窗口别名 ×" + darkAliases);
        }
        int removedVerificationSurface = removeMmsVerificationListSurface(moduleName, snapshot, removals);
        if (removedVerificationSurface > 0) {
            notes.add("移除验证码页继承的白色 9-patch ×1");
        }
        int inheritedRemovals = removeInheritedGlobalDarkAliases(moduleName, snapshot,
                external.global, removals);
        if (inheritedRemovals > 0) {
            notes.add("移除误继承的设置深色图 ×" + inheritedRemovals);
        }
        int fallbacks = repairFileExplorerDarkAssets(moduleName, snapshot, external.global,
                additions, replacements);
        if (fallbacks > 0) {
            notes.add("文件管理夜间画布别名修正 ×" + fallbacks);
        }
        int feRoutes = repairFileExplorerDarkWindowRoute(moduleName, snapshot, replacements);
        if (feRoutes > 0) {
            notes.add("文件管理夜间 fallback 路由 ×" + feRoutes);
        }
        fallbacks += feRoutes + inheritedRemovals;
        if (!MIPAY_MODULE.equals(moduleName)) {
            int lightRoutes = addFallbackRoutes(snapshot, additions, replacements, "light");
            int darkRoutes = addFallbackRoutes(snapshot, additions, replacements, "dark");
            if (lightRoutes > 0) notes.add("补齐浅色 fallback 路由 ×" + lightRoutes);
            if (darkRoutes > 0) notes.add("补齐深色 fallback 路由 ×" + darkRoutes);
            fallbacks += lightRoutes + darkRoutes;
        }
        if (additions.isEmpty() && replacements.isEmpty() && removals.isEmpty()) {
            progress.update("跳过：" + humanName(moduleName) + "（所需资源已齐全）");
            return new ModulePatch(original, 0, 0, 0, 0, "");
        }

        progress.update("正在重写：" + humanName(moduleName)
                + "（新增 " + additions.size() + "，替换 " + replacements.size()
                + "，移除 " + removals.size() + "）…");
        ByteArrayOutputStream built = new ByteArrayOutputStream(original.length + additions.size() * 1024);
        int duplicateEntries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(original));
             ZipOutputStream output = new ZipOutputStream(built)) {
            Set<String> writtenNames = new HashSet<>();
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                // Some author-made MTZ modules contain duplicate central-directory
                // entries. Android's theme lookup takes the first matching entry,
                // while ZipOutputStream rejects duplicate output names outright.
                // Preserve that first entry and make the resulting inner ZIP valid.
                if (!writtenNames.add(entry.getName())) {
                    drain(input);
                    duplicateEntries++;
                    input.closeEntry();
                    continue;
                }
                if (removals.contains(entry.getName())) {
                    drain(input);
                    input.closeEntry();
                    continue;
                }
                byte[] replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    drain(input);
                    writeEntry(output, entry, replacement);
                } else {
                    copyEntry(input, output, entry);
                }
                input.closeEntry();
            }
            for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
                if (!writtenNames.add(addition.getKey())) continue;
                ZipEntry additionEntry = new ZipEntry(addition.getKey());
                writeEntry(output, additionEntry, addition.getValue());
            }
        }
        List<String> summaryNotes = new ArrayList<>(notes);
        if (duplicateEntries > 0) summaryNotes.add("忽略重复条目 ×" + duplicateEntries);
        return new ModulePatch(built.toByteArray(), additions.size(), fallbacks, removals.size(),
                duplicateEntries, String.join("；", summaryNotes));
    }

    /**
     * The pack recolors every Settings home entry chip to its own pink except
     * the accessibility one — ic_accessibility_function is absent from the
     * module and the tile keeps the stock blue.  Insert the original chip art
     * re-tinted to the pack's two palettes (sampled from its own adapted
     * entry icons): light is the pale-pink chip with the white glyph, night
     * is the deeper rose chip with the black glyph.  The engine matches by
     * resource name across types, so a bitmap also replaces the stock vector
     * (the same mechanism that carries ic_device_connection).  A future pack
     * that ships its own file wins because both paths are skipped when
     * already present.
     */
    private static int repairSettingsAccessibilityIcon(ModuleSnapshot snapshot,
                                                       Map<String, byte[]> additions,
                                                       byte[] lightIcon, byte[] darkIcon) {
        int added = 0;
        if (lightIcon != null && lightIcon.length > 0
                && !snapshot.names.contains(ACCESSIBILITY_ICON_LIGHT)) {
            additions.put(ACCESSIBILITY_ICON_LIGHT, lightIcon);
            added++;
        }
        if (darkIcon != null && darkIcon.length > 0
                && !snapshot.names.contains(ACCESSIBILITY_ICON_DARK)) {
            additions.put(ACCESSIBILITY_ICON_DARK, darkIcon);
            added++;
        }
        return added;
    }

    /** The bundled accessibility chip art shipped in res/raw. */
    private static byte[] readBundledAsset(Context context, int resId) {
        try (InputStream input = context.getResources().openRawResource(resId)) {
            return readAll(input, 1024 * 1024);
        } catch (IOException | android.content.res.Resources.NotFoundException error) {
            return null;
        }
    }

    /**
     * The pack's nightmode/theme_fallback.xml only routes the light window
     * names (window_bg_light, settings_window_bg_light, …) while the module
     * does ship nightmode/res/drawable-xxhdpi/window_bg_dark.9.png.  Without
     * a dark route, a night lookup of miuix_appcompat_window_bg_dark lands on
     * the app's stock black XML and every Settings-style page of this package
     * loses its authored dark canvas.  Append the dark routes next to the
     * light ones; entries already present are kept untouched.
     */
    private static int repairSecurityCenterDarkAliases(ModuleSnapshot snapshot,
                                                       Map<String, byte[]> replacements) {
        byte[] fallback = snapshot.smallFiles.get(SECURITY_CENTER_NIGHT_FALLBACK);
        if (fallback == null) return 0;
        String xml = new String(fallback, StandardCharsets.UTF_8);
        String closing = "</MIUI_Theme_Values>";
        int at = xml.lastIndexOf(closing);
        if (at < 0) return 0;
        StringBuilder insert = new StringBuilder();
        int added = 0;
        for (String alias : new String[]{
                "miuix_appcompat_window_bg_dark.9.png",
                "miuix_appcompat_settings_window_bg_dark.9.png",
                "miuix_appcompat_window_bg_secondary_dark.9.png",
                "settings_window_bg_dark.9.png"}) {
            if (xml.contains(alias)) continue;
            insert.append("<drawable name=\"").append(alias)
                    .append("\" package=\"miui\">window_bg_dark.9.png</drawable>\n");
            added++;
        }
        if (added == 0) return 0;
        xml = xml.substring(0, at) + insert + xml.substring(at);
        replacements.put(SECURITY_CENTER_NIGHT_FALLBACK, xml.getBytes(StandardCharsets.UTF_8));
        return added;
    }

    /**
     * This theme's Messaging module contains its intended night canvas as
     * miuix_appcompat_window_bg_drak.9.png. HyperOS requests the correctly
     * spelled dark resource for both the tab page and preferences page. Copy
     * the theme's own asset under those two expected names; no image is added
     * from this app and no valid existing resource is overwritten.
     */
    private static int repairMmsDarkWindowAliases(String moduleName, ModuleSnapshot snapshot,
                                                  Map<String, byte[]> additions) {
        if (!MMS_MODULE.equals(moduleName)) return 0;
        byte[] source = snapshot.smallFiles.get(MMS_DARK_TYPO_PATH);
        if (source == null) return 0;
        String prefix = "nightmode/res/drawable-xxhdpi/";
        String[] targets = {
                "miuix_appcompat_window_bg_dark.9.png",
                "miuix_appcompat_settings_window_bg_dark.9.png"
        };
        int added = 0;
        for (String target : targets) {
            String path = prefix + target;
            if (!snapshot.names.contains(path) && !additions.containsKey(path)) {
                additions.put(path, source);
                added++;
            }
        }
        return added;
    }

    /**
     * The theme already routes the verification-code page background to its
     * own conversation artwork in theme_fallback.xml. A tiny inherited white
     * 9-patch at the concrete resource path wins before that route, so light
     * mode never reaches the authored background. Remove it only when both
     * the authored donor and that exact fallback are present.
     */
    private static int removeMmsVerificationListSurface(String moduleName, ModuleSnapshot snapshot,
                                                        Set<String> removals) {
        if (!MMS_MODULE.equals(moduleName) || !snapshot.names.contains(MMS_VERIFICATION_LIST_BACKGROUND)
                || !snapshot.names.contains(MMS_CONVERSATION_BACKGROUND)) return 0;
        byte[] xmlBytes = snapshot.smallFiles.get("theme_fallback.xml");
        if (xmlBytes == null) return 0;
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        if (!hasDrawableRouteTo(xml, "window_bg_verification_list.9.png",
                "conversation_bg.9.png")) return 0;
        removals.add(MMS_VERIFICATION_LIST_BACKGROUND);
        return 1;
    }

    /**
     * HyperOS 3's ThemeResourceProxyTabActivity asks its own theme module for
     * non-density paths such as
     * res/drawable/miuix_appcompat_window_bg_light.9.png. The original theme
     * keeps the full-page artwork only in drawable-xxhdpi, so lookup falls
     * back to opaque white or black. Copy the theme's own window images to the
     * exact light and night paths recorded on the K70U.
     */
    private static int repairThemeManagerWindowPaths(String moduleName, ModuleSnapshot snapshot,
                                                     Map<String, byte[]> additions) {
        if (!THEME_MANAGER_MODULE.equals(moduleName)) return 0;
        int added = 0;
        added += addThemeManagerWindowPath(snapshot, additions,
                "res/drawable/window_bg_light.9.png", snapshot.donors.light);
        added += addThemeManagerWindowPath(snapshot, additions,
                "res/drawable/miuix_appcompat_window_bg_light.9.png", snapshot.donors.light);
        added += addThemeManagerWindowPath(snapshot, additions,
                "res/drawable/miuix_appcompat_settings_window_bg_light.9.png", snapshot.donors.light);
        added += addThemeManagerWindowPath(snapshot, additions,
                "nightmode/res/drawable/window_bg_dark.9.png", snapshot.donors.dark);
        added += addThemeManagerWindowPath(snapshot, additions,
                "nightmode/res/drawable/miuix_appcompat_window_bg_light.9.png", snapshot.donors.dark);
        added += addThemeManagerWindowPath(snapshot, additions,
                "nightmode/res/drawable/miuix_appcompat_window_bg_dark.9.png", snapshot.donors.dark);
        added += addThemeManagerWindowPath(snapshot, additions,
                "nightmode/res/drawable/miuix_appcompat_settings_window_bg_light.9.png", snapshot.donors.dark);
        added += addThemeManagerWindowPath(snapshot, additions,
                "nightmode/res/drawable/miuix_appcompat_settings_window_bg_dark.9.png", snapshot.donors.dark);
        return added;
    }

    private static int addThemeManagerWindowPath(ModuleSnapshot snapshot,
                                                 Map<String, byte[]> additions,
                                                 String target, byte[] source) {
        if (source != null && !snapshot.names.contains(target) && !additions.containsKey(target)) {
            additions.put(target, source);
            return 1;
        }
        return 0;
    }

    /**
     * The verified K70U Wallet fix is deliberately narrow: the original
     * light startup artwork already exists, while the dark route asks for the
     * same filename below nightmode. Do not add generic window aliases here:
     * Wallet's in-app homepage is intentionally left unmodified.
     */
    private static boolean repairMipayDarkSplash(String moduleName, ModuleSnapshot snapshot,
                                                 Map<String, byte[]> additions) {
        if (!MIPAY_MODULE.equals(moduleName) || snapshot.names.contains(MIPAY_DARK_SPLASH)) return false;
        byte[] source = snapshot.smallFiles.get(MIPAY_LIGHT_SPLASH);
        if (source == null) return false;
        additions.put(MIPAY_DARK_SPLASH, source);
        return true;
    }

    private static int addContactsSearchAlias(ModuleSnapshot snapshot, Map<String, byte[]> additions,
                                              String prefix, String sourceName, String targetName) {
        String source = prefix + sourceName;
        String target = prefix + targetName;
        if (!snapshot.names.contains(target) && !additions.containsKey(target)) {
            byte[] bytes = snapshot.smallFiles.get(source);
            if (bytes != null) {
                additions.put(target, bytes);
                return 1;
            }
        }
        return 0;
    }

    /**
     * The original File Explorer module has a dark route whose target,
     * window_bg_dark.9.png, is absent from the complete module.  HyperOS 3's
     * File Explorer asks for miuix_appcompat_window_bg_dark, and consequently
     * falls back to a plain black window.  The theme's intended dark canvas is
     * already present under the historical "light" filename inside nightmode.
     */
    private static int repairFileExplorerDarkAssets(String moduleName, ModuleSnapshot snapshot,
                                                    Donors global,
                                                    Map<String, byte[]> additions,
                                                    Map<String, byte[]> replacements) {
        if (!FILE_EXPLORER_MODULE.equals(moduleName)) return 0;
        byte[] intended = snapshot.smallFiles.get(FILE_EXPLORER_NIGHT_CANVAS);
        if (intended == null) return 0;

        int repaired = 0;
        String prefix = "nightmode/res/drawable-xxhdpi/";
        for (String pattern : BASE_ALIASES) {
            String path = prefix + String.format(Locale.ROOT, pattern, "dark");
            if (!snapshot.names.contains(path)) {
                additions.put(path, intended);
                repaired++;
                continue;
            }
            /*
             * A previously generated package can already contain the wrong
             * Settings image.  Replace only byte-for-byte copies of that
             * global donor; authored File Explorer assets stay untouched.
             */
            byte[] existing = snapshot.smallFiles.get(path);
            if (global.dark != null && existing != null
                    && Arrays.equals(existing, global.dark) && !Arrays.equals(existing, intended)) {
                replacements.put(path, intended);
                repaired++;
            }
        }
        return repaired;
    }

    /**
     * Compatibility packages created before the donor fix can already contain
     * Settings' dark image under a different module's generated aliases.
     * Remove only byte-for-byte copies where that module has no dark donor of
     * its own.  Authored artwork is consequently never removed.
     */
    private static int removeInheritedGlobalDarkAliases(String moduleName, ModuleSnapshot snapshot,
                                                         Donors global, Set<String> removals) {
        if (FILE_EXPLORER_MODULE.equals(moduleName) || MMS_MODULE.equals(moduleName)
                || MIPAY_MODULE.equals(moduleName) || snapshot.donors.dark != null
                || global.dark == null) return 0;
        int removed = 0;
        String prefix = "nightmode/res/drawable-xxhdpi/";
        for (String pattern : BASE_ALIASES) {
            String path = prefix + String.format(Locale.ROOT, pattern, "dark");
            byte[] existing = snapshot.smallFiles.get(path);
            if (existing != null && Arrays.equals(existing, global.dark)) {
                removals.add(path);
                removed++;
            }
        }
        return removed;
    }

    private static int repairFileExplorerDarkWindowRoute(String moduleName, ModuleSnapshot snapshot,
                                                          Map<String, byte[]> replacements) {
        if (!FILE_EXPLORER_MODULE.equals(moduleName)) return 0;
        final String path = "nightmode/theme_fallback.xml";
        final String alias = "miuix_appcompat_window_bg_dark.9.png";
        final String missingTarget = "window_bg_dark.9.png";
        final String intendedTarget = "miuix_appcompat_window_bg_light.9.png";
        final String intendedPath = "nightmode/res/drawable-xxhdpi/" + intendedTarget;
        if (!snapshot.names.contains(intendedPath)) return 0;

        byte[] xmlBytes = snapshot.smallFiles.get(path);
        if (xmlBytes == null) return 0;
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        if (!xml.contains("<MIUI_Theme_Values") || !xml.contains("</MIUI_Theme_Values>")) return 0;

        Pattern brokenRoute = Pattern.compile("(?s)(<drawable\\s+name\\s*=\\s*(['\\\"])"
                + Pattern.quote(alias) + "\\2[^>]*>\\s*)" + Pattern.quote(missingTarget)
                + "(\\s*</drawable>)");
        Matcher matcher = brokenRoute.matcher(xml);
        if (matcher.find()) {
            StringBuffer adapted = new StringBuffer();
            int count = 0;
            do {
                matcher.appendReplacement(adapted, Matcher.quoteReplacement(
                        matcher.group(1) + intendedTarget + matcher.group(3)));
                count++;
            } while (matcher.find());
            matcher.appendTail(adapted);
            replacements.put(path, adapted.toString().getBytes(StandardCharsets.UTF_8));
            return count;
        }
        if (hasDrawableRoute(xml, alias)) return 0;

        String route = "\n<drawable name=\"" + alias + "\">" + intendedTarget + "</drawable>";
        String adapted = xml.replace("</MIUI_Theme_Values>", route + "\n</MIUI_Theme_Values>");
        replacements.put(path, adapted.getBytes(StandardCharsets.UTF_8));
        return 1;
    }

    private static int addFallbackRoutes(ModuleSnapshot snapshot, Map<String, byte[]> additions,
                                         Map<String, byte[]> replacements, String mode) {
        String path = "light".equals(mode) ? "theme_fallback.xml" : "nightmode/theme_fallback.xml";
        byte[] xmlBytes = snapshot.smallFiles.get(path);
        if (xmlBytes == null) return 0;
        byte[] existing = replacements.get(path);
        String xml = new String(existing == null ? xmlBytes : existing, StandardCharsets.UTF_8);
        if (!xml.contains("<MIUI_Theme_Values") || !xml.contains("</MIUI_Theme_Values>")) return 0;
        StringBuilder routes = new StringBuilder();
        int count = 0;
        String prefix = "light".equals(mode) ? "res/drawable-xxhdpi/"
                : "nightmode/res/drawable-xxhdpi/";
        for (String pattern : BASE_ALIASES) {
            String alias = String.format(Locale.ROOT, pattern, mode);
            String resource = prefix + alias;
            if (!snapshot.names.contains(resource) && !additions.containsKey(resource)) continue;
            if (hasDrawableRoute(xml, alias)) continue;
            routes.append("\n<drawable name=\"").append(alias)
                    .append("\" package=\"miui\">").append(alias).append("</drawable>");
            count++;
        }
        if (count == 0) return 0;
        String adapted = xml.replace("</MIUI_Theme_Values>", routes + "\n</MIUI_Theme_Values>");
        replacements.put(path, adapted.getBytes(StandardCharsets.UTF_8));
        return count;
    }

    private static boolean hasDrawableRoute(String xml, String alias) {
        return xml.contains("name=\"" + alias + "\"") || xml.contains("name='" + alias + "'");
    }

    private static boolean hasDrawableRouteTo(String xml, String alias, String target) {
        Pattern route = Pattern.compile("(?s)<drawable\\s+name\\s*=\\s*(['\"])"
                + Pattern.quote(alias) + "\\1[^>]*>\\s*" + Pattern.quote(target)
                + "\\s*</drawable>");
        return route.matcher(xml).find();
    }

    private static ModuleSnapshot readSnapshot(byte[] module) throws IOException {
        ModuleSnapshot snapshot = new ModuleSnapshot();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(module))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (!isSafeEntryName(name)) throw new IOException("模块包含不安全 ZIP 路径：" + name);
                if (!entry.isDirectory()) snapshot.names.add(name);
                if (isDonorCandidate(name)) {
                    byte[] bytes = readAll(input, MAX_MODULE_BYTES);
                    snapshot.donors.consider(name, bytes);
                    if (isSmallTrackedFile(name)) snapshot.smallFiles.put(name, bytes);
                } else if (isSmallTrackedFile(name)) {
                    snapshot.smallFiles.put(name, readAll(input, MAX_XML_BYTES));
                }
                input.closeEntry();
            }
        }
        return snapshot;
    }

    private static boolean isSmallTrackedFile(String name) {
        return "theme_fallback.xml".equals(name) || "nightmode/theme_fallback.xml".equals(name)
                || MIPAY_LIGHT_SPLASH.equals(name)
                || name.endsWith("/search_mode_edit_text_bg_light.9.png")
                || name.endsWith("/search_mode_edit_text_bg_dark.9.png")
                || name.endsWith("/miuix_appcompat_window_bg_drak.9.png")
                || FILE_EXPLORER_NIGHT_CANVAS.equals(name)
                || isFileExplorerDarkAlias(name);
    }

    private static boolean isFileExplorerDarkAlias(String name) {
        String prefix = "nightmode/res/drawable-xxhdpi/";
        for (String pattern : BASE_ALIASES) {
            if ((prefix + String.format(Locale.ROOT, pattern, "dark")).equals(name)) return true;
        }
        return false;
    }

    private static boolean isDonorCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".9.png") && lower.contains("window_bg_")
                && (lower.contains("_light.9.png") || lower.contains("_dark.9.png"));
    }

    private static byte[] patchDescription(byte[] original) throws IOException {
        String xml = new String(original, StandardCharsets.UTF_8);
        if (!xml.contains("<theme") || !xml.contains("</theme>")) {
            throw new IOException("description.xml 不是主题描述文件");
        }
        Matcher version = VERSION.matcher(xml);
        if (version.find()) {
            int current;
            try { current = Integer.parseInt(version.group(1)); }
            catch (NumberFormatException ignored) { current = 1; }
            int next = Math.min(current + 1, 999999);
            xml = version.replaceFirst(Matcher.quoteReplacement(
                    "<version><![CDATA[" + next + "]]></version>"));
        }
        xml = appendTitleMarker(xml);
        xml = appendDescriptionMarker(xml);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String appendTitleMarker(String xml) {
        Matcher matcher = TITLE.matcher(xml);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String title = matcher.group(2).trim();
            if (!title.contains("K70") && !title.contains("适配")) title += " · K70U 适配";
            String replacement = "<title" + nullToEmpty(matcher.group(1)) + "><![CDATA["
                    + title + "]]></title>";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String appendDescriptionMarker(String xml) {
        Matcher matcher = DESCRIPTION_ELEMENT.matcher(xml);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String description = matcher.group(2);
            if (!description.contains("K70U 静态资源别名已补齐")) {
                description = description.trim() + "\nK70U 静态资源别名已补齐；系统页面遮罩由配套兼容模块处理\n";
            }
            String replacement = "<description" + nullToEmpty(matcher.group(1)) + "><![CDATA["
                    + description + "]]></description>";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static void writeEntry(ZipOutputStream output, ZipEntry source, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(source.getName());
        if (source.getTime() >= 0) entry.setTime(source.getTime());
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static void copyEntry(ZipInputStream input, ZipOutputStream output, ZipEntry source)
            throws IOException {
        ZipEntry entry = new ZipEntry(source.getName());
        if (source.getTime() >= 0) entry.setTime(source.getTime());
        output.putNextEntry(entry);
        if (!source.isDirectory()) copy(input, output);
        output.closeEntry();
    }

    private static InputStream requireInput(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IOException("无法读取所选主题");
        return input;
    }

    private static OutputStream requireOutput(ContentResolver resolver, Uri uri) throws IOException {
        OutputStream output = resolver.openOutputStream(uri, "wt");
        if (output == null) throw new IOException("无法写入所选位置");
        return output;
    }

    private static byte[] readAll(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw new IOException("主题模块过大，已停止处理以保护内存");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) { }
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)
                && (bytes[3] == 4 || bytes[3] == 6 || bytes[3] == 8);
    }

    private static boolean isSafeEntryName(String name) {
        return name != null && !name.startsWith("/") && !name.startsWith("\\")
                && !name.contains("../") && !name.contains("..\\") && !name.contains(":");
    }

    private static String buildReport(List<String> changes, Donors donors) {
        StringBuilder report = new StringBuilder();
        report.append("已生成独立适配包；原始 .mtz 未改动。\n");
        report.append("背景来源：").append(donors.light == null ? "未找到浅色全局后备" : "浅色已找到")
                .append("，").append(donors.dark == null ? "未找到深色全局后备" : "深色已找到").append("。\n");
        if (changes.isEmpty()) {
            report.append("目标模块的静态别名和回退映射均已齐全，未复制资源；仅更新主题标识。");
        } else {
            report.append("实际修改：\n");
            for (String change : changes) report.append("• ").append(change).append('\n');
            report.append("\n现有素材均未覆盖，新增别名均来自所选主题自身。");
        }
        return report.toString();
    }

    private static String humanName(String module) {
        if ("com.android.settings".equals(module)) return "设置";
        if ("com.android.camera".equals(module)) return "相机";
        if ("com.android.calendar".equals(module)) return "日历";
        if ("com.android.deskclock".equals(module)) return "时钟";
        if ("com.android.fileexplorer".equals(module)) return "文件管理";
        if ("com.android.contacts".equals(module)) return "联系人";
        if ("com.android.mms".equals(module)) return "短信";
        if ("com.android.soundrecorder".equals(module)) return "录音机";
        if ("com.miui.gallery".equals(module)) return "相册";
        if ("com.miui.packageinstaller".equals(module)) return "安装器";
        if (THEME_MANAGER_MODULE.equals(module)) return "主题商店";
        if (MIPAY_MODULE.equals(module)) return "小米钱包";
        if (COMPASS_MODULE.equals(module)) return "指南针";
        if (NOTIFICATION_MODULE.equals(module)) return "通知";
        if (AIASST_SERVICE_MODULE.equals(module)) return "AI通话";
        if (AI_VISION_MODULE.equals(module)) return "翻译";
        if (CLEAN_MASTER_MODULE.equals(module)) return "垃圾清理";
        return module;
    }

    private static final class ModulePatch {
        final byte[] bytes;
        final int additions;
        final int fallbacks;
        final int removals;
        final int duplicateEntries;
        final String detail;

        ModulePatch(byte[] bytes, int additions, int fallbacks, int removals, int duplicateEntries,
                    String detail) {
            this.bytes = bytes;
            this.additions = additions;
            this.fallbacks = fallbacks;
            this.removals = removals;
            this.duplicateEntries = duplicateEntries;
            this.detail = detail;
        }
    }

    private static final class ModuleSnapshot {
        final Set<String> names = new HashSet<>();
        final Map<String, byte[]> smallFiles = new HashMap<>();
        final Donors donors = new Donors();
    }

    /** Settings global donors plus the audited cross-module night canvases. */
    private static final class ExternalDonors {
        final Donors global = new Donors();
        byte[] mmsDark;
        byte[] contactsDark;
        byte[] fileExplorerDark;
        byte[] themeManagerDark;
    }

    private static final class Donors {
        byte[] light;
        byte[] dark;
        int lightScore;
        int darkScore;

        void consider(String path, byte[] bytes) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith("_light.9.png")) {
                int score = score(path, "light");
                if (score > lightScore) { light = bytes; lightScore = score; }
            } else if (lower.endsWith("_dark.9.png")) {
                int score = score(path, "dark");
                if (score > darkScore) { dark = bytes; darkScore = score; }
            }
        }

        void merge(Donors other) {
            if (other.lightScore > lightScore) { light = other.light; lightScore = other.lightScore; }
            if (other.darkScore > darkScore) { dark = other.dark; darkScore = other.darkScore; }
        }

        boolean complete() { return light != null && dark != null; }

        private static int score(String path, String mode) {
            String normalized = path.replace('\\', '/');
            String suffix = "_" + mode + ".9.png";
            if (normalized.equals("res/drawable-xxhdpi/miuix_appcompat_window_bg" + suffix)) return 100;
            if (normalized.equals("res/drawable-xxhdpi/miuix_appcompat_settings_window_bg" + suffix)) return 95;
            if (normalized.equals("res/drawable-xxhdpi/window_bg" + suffix)) return 90;
            if (normalized.equals("nightmode/res/drawable-xxhdpi/window_bg" + suffix)) return 90;
            if (normalized.contains("framework-miui-res/res/drawable-xxhdpi/window_bg" + suffix)) return 85;
            return 50;
        }
    }
}
