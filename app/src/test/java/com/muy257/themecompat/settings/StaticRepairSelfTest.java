package com.muy257.themecompat.settings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Standalone regression check for the two historical dark-window routes. */
public final class StaticRepairSelfTest {
    private StaticRepairSelfTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("original-theme.mtz");
        Map<String, byte[]> theme = entries(Files.readAllBytes(Path.of(args[0])));
        byte[] settings = require(theme, "com.android.settings");

        Map<String, byte[]> mms = entries(MtzCompatibilityPatcher.patchModuleForTest(
                "com.android.mms", require(theme, "com.android.mms"), settings));
        byte[] typo = require(mms,
                "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_drak.9.png");
        check(Arrays.equals(typo, require(mms,
                "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_dark.9.png")),
                "MMS dark alias does not use its authored typo asset");
        check(Arrays.equals(typo, require(mms,
                "nightmode/res/drawable-xxhdpi/miuix_appcompat_settings_window_bg_dark.9.png")),
                "MMS settings dark alias missing");
        check(!mms.containsKey("res/drawable-xxhdpi/window_bg_verification_list.9.png"),
                "MMS verification white 9-patch was not removed");

        Map<String, byte[]> files = entries(MtzCompatibilityPatcher.patchModuleForTest(
                "com.android.fileexplorer", require(theme, "com.android.fileexplorer"), settings));
        byte[] intended = require(files,
                "nightmode/res/drawable-xxhdpi/miuix_appcompat_window_bg_light.9.png");
        for (String name : new String[]{"window_bg_dark.9.png",
                "miuix_appcompat_window_bg_dark.9.png",
                "miuix_appcompat_settings_window_bg_dark.9.png",
                "miuix_appcompat_window_bg_secondary_dark.9.png"}) {
            check(Arrays.equals(intended, require(files,
                    "nightmode/res/drawable-xxhdpi/" + name)),
                    "File Explorer dark alias is not its authored night canvas: " + name);
        }
        String fallback = new String(require(files, "nightmode/theme_fallback.xml"),
                StandardCharsets.UTF_8);
        check(fallback.contains("miuix_appcompat_window_bg_light.9.png"),
                "File Explorer fallback was not redirected to its existing night canvas");
        System.out.println("PASS static repairs: MMS aliases/verification, File Explorer aliases/fallback");
    }

    private static Map<String, byte[]> entries(byte[] archive) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) result.put(entry.getName(), readAll(input));
                input.closeEntry();
            }
        }
        return result;
    }

    private static byte[] readAll(ZipInputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static byte[] require(Map<String, byte[]> entries, String name) {
        byte[] value = entries.get(name);
        if (value == null) throw new AssertionError("missing entry: " + name);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
