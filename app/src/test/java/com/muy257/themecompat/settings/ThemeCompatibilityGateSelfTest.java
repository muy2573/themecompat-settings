package com.muy257.themecompat.settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain-java regression harness for the payload-marker gate; mirrors the
 * javac-run style of the other self tests in this package.
 *
 * Usage:
 *   java ThemeCompatibilityGateSelfTest          -> runs all cases
 *   java ThemeCompatibilityGateSelfTest <dir>…   -> prints gate status for dirs
 */
public final class ThemeCompatibilityGateSelfTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            for (String dir : args) {
                System.out.println(new File(dir).getName() + " -> "
                        + ThemeCompatibilityGate.evaluate(new File(dir)));
            }
            return;
        }
        File root = Files.createTempDirectory("themecompat-gate").toFile();
        try {
            caseMissingDir();
            caseEmptyDir(root);
            caseStoreThemePayload(root);
            caseAdaptedThemePayload(root);
            caseWrongMarkerContent(root);
            caseEditorWrittenMarker(root);
            System.out.println("PASS " + checked);
        } finally {
            deleteRecursively(root);
        }
    }

    private static int checked = 0;

    private static void expect(ThemeCompatibilityGate.Status actual,
                               ThemeCompatibilityGate.Status wanted, String label) {
        checked++;
        if (actual != wanted) {
            throw new AssertionError(label + ": expected " + wanted + " got " + actual);
        }
        System.out.println("ok " + label + " -> " + actual);
    }

    private static void caseMissingDir() {
        expect(ThemeCompatibilityGate.evaluate(new File("Z:/definitely/not/here")),
                ThemeCompatibilityGate.Status.UNKNOWN, "missing dir");
    }

    private static void caseEmptyDir(File root) throws IOException {
        File dir = new File(root, "empty");
        dir.mkdirs();
        expect(ThemeCompatibilityGate.evaluate(dir), ThemeCompatibilityGate.Status.UNKNOWN, "empty dir");
    }

    /** Store theme: module payload zips exist but carry no marker. */
    private static void caseStoreThemePayload(File root) throws IOException {
        File dir = new File(root, "store");
        dir.mkdirs();
        write(dir, "config.config", "<Config/>".getBytes(StandardCharsets.UTF_8));
        write(dir, "com.android.mms", zipOf(
                Collections.singletonMap("theme_values.xml", "<theme/>".getBytes(StandardCharsets.UTF_8))));
        expect(ThemeCompatibilityGate.evaluate(dir),
                ThemeCompatibilityGate.Status.INCOMPATIBLE, "store payload without marker");
    }

    /** Adapted theme: one rewritten module carries the marker record. */
    private static void caseAdaptedThemePayload(File root) throws IOException {
        File dir = new File(root, "adapted");
        dir.mkdirs();
        write(dir, "config.config", "<Config/>".getBytes(StandardCharsets.UTF_8));
        write(dir, "com.android.mms", zipOf(
                Collections.singletonMap("theme_values.xml", "<theme/>".getBytes(StandardCharsets.UTF_8))));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("theme_values.xml", "<theme/>".getBytes(StandardCharsets.UTF_8));
        entries.put(CompatibilityContract.PAYLOAD_MARKER_ENTRY,
                CompatibilityContract.PAYLOAD_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8));
        write(dir, "com.android.contacts", zipOf(entries));
        expect(ThemeCompatibilityGate.evaluate(dir),
                ThemeCompatibilityGate.Status.COMPATIBLE, "payload with marker");
    }

    /** A marker record with unexpected content must not count as proof. */
    private static void caseWrongMarkerContent(File root) throws IOException {
        File dir = new File(root, "forged");
        dir.mkdirs();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(CompatibilityContract.PAYLOAD_MARKER_ENTRY,
                "someone-else:v9\n".getBytes(StandardCharsets.UTF_8));
        write(dir, "com.android.mms", zipOf(entries));
        expect(ThemeCompatibilityGate.evaluate(dir),
                ThemeCompatibilityGate.Status.INCOMPATIBLE, "foreign marker content");
    }

    /** Integration: a marker added through RawZipArchiveEditor is found. */
    private static void caseEditorWrittenMarker(File root) throws IOException {
        File dir = new File(root, "editor");
        dir.mkdirs();
        byte[] source = zipOf(
                Collections.singletonMap("theme_values.xml", "<theme/>".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> additions = new LinkedHashMap<>();
        additions.put(CompatibilityContract.PAYLOAD_MARKER_ENTRY,
                CompatibilityContract.PAYLOAD_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8));
        RawZipArchiveEditor.Result edited = RawZipArchiveEditor.edit(
                source, additions, Collections.emptyMap(), Collections.emptySet());
        if (edited.additions != 1) {
            throw new AssertionError("editor additions: expected 1 got " + edited.additions);
        }
        write(dir, "com.android.settings", edited.bytes);
        write(dir, "com.android.mms", zipOf(
                Collections.singletonMap("theme_values.xml", "<theme/>".getBytes(StandardCharsets.UTF_8))));
        expect(ThemeCompatibilityGate.evaluate(dir),
                ThemeCompatibilityGate.Status.COMPATIBLE, "editor-written marker");
    }

    private static void write(File dir, String name, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(dir, name))) {
            output.write(bytes);
        }
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
