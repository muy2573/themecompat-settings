package com.muy257.themecompat.settings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Inflates every outer and inner entry so java.util.zip verifies all CRCs. */
public final class NestedZipIntegrityAudit {
    private NestedZipIntegrityAudit() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("theme.mtz");
        byte[] buffer = new byte[64 * 1024];
        int outerCount = 0;
        int innerZips = 0;
        int innerEntries = 0;
        try (ZipInputStream outer = new ZipInputStream(new FileInputStream(Path.of(args[0]).toFile()))) {
            ZipEntry outerEntry;
            while ((outerEntry = outer.getNextEntry()) != null) {
                outerCount++;
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                int read;
                while ((read = outer.read(buffer)) != -1) payload.write(buffer, 0, read);
                byte[] value = payload.toByteArray();
                if (value.length >= 4 && value[0] == 'P' && value[1] == 'K') {
                    innerZips++;
                    try (ZipInputStream inner = new ZipInputStream(new ByteArrayInputStream(value))) {
                        ZipEntry innerEntry;
                        while ((innerEntry = inner.getNextEntry()) != null) {
                            innerEntries++;
                            while (inner.read(buffer) != -1) { }
                            inner.closeEntry();
                        }
                    }
                }
                outer.closeEntry();
            }
        }
        System.out.println("PASS outerEntries=" + outerCount + " innerZips=" + innerZips
                + " innerEntries=" + innerEntries + " allCRCsValid=true");
    }
}
