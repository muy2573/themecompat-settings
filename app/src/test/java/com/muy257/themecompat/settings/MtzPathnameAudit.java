package com.muy257.themecompat.settings;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Standalone audit of every inner ZIP in an MTZ. */
public final class MtzPathnameAudit {
    private MtzPathnameAudit() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("theme.mtz");
        int modules = 0;
        int changedModules = 0;
        int changedPaths = 0;
        try (ZipInputStream outer = new ZipInputStream(new FileInputStream(Path.of(args[0]).toFile()))) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = outer.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = outer.read(buffer)) != -1) bytes.write(buffer, 0, read);
                byte[] payload = bytes.toByteArray();
                if (payload.length < 4 || payload[0] != 'P' || payload[1] != 'K') continue;
                modules++;
                ZipPathnameNormalizer.Result result = ZipPathnameNormalizer.normalize(payload);
                if (result.changed()) {
                    changedModules++;
                    changedPaths += result.normalizedPathnames;
                    System.out.println(entry.getName() + ": normalized="
                            + result.normalizedPathnames + " fallback=" + result.legacyFallbacks);
                }
            }
        }
        System.out.println("PASS innerZips=" + modules + " changedModules=" + changedModules
                + " normalizedPaths=" + changedPaths);
    }
}
