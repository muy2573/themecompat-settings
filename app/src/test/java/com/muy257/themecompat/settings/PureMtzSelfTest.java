package com.muy257.themecompat.settings;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Verifies that a test MTZ differs only in the com.android.thememanager payload. */
public final class PureMtzSelfTest {
    private static final String MODULE = "com.android.thememanager";

    private PureMtzSelfTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("original.mtz pure.mtz normalized-module.zip");
        }
        Map<String, Fingerprint> original = fingerprints(Path.of(args[0]));
        Map<String, Fingerprint> pure = fingerprints(Path.of(args[1]));
        check(original.keySet().equals(pure.keySet()), "outer entry set changed");
        int changed = 0;
        for (String name : original.keySet()) {
            if (original.get(name).equals(pure.get(name))) continue;
            changed++;
            check(MODULE.equals(name), "unexpected changed outer entry: " + name);
        }
        check(changed == 1, "expected one changed outer entry, got " + changed);
        Fingerprint expected = fingerprint(Files.newInputStream(Path.of(args[2])));
        check(expected.equals(pure.get(MODULE)), "ThemeManager payload is not normalized module");
        System.out.println("PASS outerEntries=" + pure.size() + " changedOnly=" + MODULE);
    }

    private static Map<String, Fingerprint> fingerprints(Path archive) throws Exception {
        Map<String, Fingerprint> result = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive.toFile()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                check(!result.containsKey(entry.getName()), "duplicate outer entry: " + entry.getName());
                result.put(entry.getName(), fingerprint(input));
                input.closeEntry();
            }
        }
        return result;
    }

    private static Fingerprint fingerprint(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            size += read;
        }
        return new Fingerprint(size, digest.digest());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fingerprint {
        final long size;
        final byte[] sha256;

        Fingerprint(long size, byte[] sha256) {
            this.size = size;
            this.sha256 = sha256;
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Fingerprint)) return false;
            Fingerprint other = (Fingerprint) value;
            return size == other.size && Arrays.equals(sha256, other.sha256);
        }

        @Override public int hashCode() {
            return (int) (size ^ (size >>> 32)) * 31 + Arrays.hashCode(sha256);
        }
    }
}
