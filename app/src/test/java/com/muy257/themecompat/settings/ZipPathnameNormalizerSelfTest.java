package com.muy257.themecompat.settings;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Standalone regression check; run with two arguments: legacy.zip normalized.zip. */
public final class ZipPathnameNormalizerSelfTest {
    private static final Set<String> EXPECTED_CHINESE = new HashSet<>(Arrays.asList(
            "res/drawable-xxhdpi/主题.png",
            "res/drawable-xxhdpi/动态壁纸.png",
            "res/drawable-xxhdpi/图标.png",
            "res/drawable-xxhdpi/壁纸.png",
            "res/drawable-xxhdpi/大图标.png",
            "res/drawable-xxhdpi/字体.png",
            "res/drawable-xxhdpi/小部件.png",
            "res/drawable-xxhdpi/息屏.png",
            "res/drawable-xxhdpi/铃声.png"
    ));

    private ZipPathnameNormalizerSelfTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("legacy.zip normalized.zip");
        byte[] input = Files.readAllBytes(Path.of(args[0]));
        ZipPathnameNormalizer.Result result = ZipPathnameNormalizer.normalize(input);
        check(result.normalizedPathnames == 9, "expected 9 normalized paths, got "
                + result.normalizedPathnames);
        check(result.legacyFallbacks == 0, "valid 0x7075 paths must not use GB18030 fallback");
        check(payloads(input).equals(payloads(result.bytes)),
                "compressed payload, CRC, method or entry order changed");
        Files.write(Path.of(args[1]), result.bytes);

        int entries = 0;
        Set<String> chinese = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                String name = entry.getName();
                check(name.indexOf('\ufffd') < 0, "replacement character in " + name);
                if (EXPECTED_CHINESE.contains(name)) chinese.add(name);
                zip.closeEntry();
            }
        }
        check(entries == 206, "expected 206 entries, got " + entries);
        check(chinese.equals(EXPECTED_CHINESE), "missing canonical Chinese paths: "
                + difference(EXPECTED_CHINESE, chinese));

        ZipPathnameNormalizer.Result second = ZipPathnameNormalizer.normalize(result.bytes);
        check(!second.changed(), "normalization must be idempotent");
        check(Arrays.equals(result.bytes, second.bytes), "idempotent bytes changed");
        System.out.println("PASS entries=206 normalized=9 legacyFallbacks=0");
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static List<Payload> payloads(byte[] archive) throws Exception {
        int eocd = -1;
        for (int cursor = archive.length - 22; cursor >= Math.max(0, archive.length - 65557);
             cursor--) {
            if (u32(archive, cursor) == 0x06054b50L
                    && cursor + 22 + u16(archive, cursor + 20) == archive.length) {
                eocd = cursor;
                break;
            }
        }
        check(eocd >= 0, "EOCD missing");
        int count = u16(archive, eocd + 10);
        int central = (int) u32(archive, eocd + 16);
        List<Payload> payloads = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            check(u32(archive, central) == 0x02014b50L, "central signature #" + index);
            int method = u16(archive, central + 10);
            long crc = u32(archive, central + 16);
            int compressed = (int) u32(archive, central + 20);
            int uncompressed = (int) u32(archive, central + 24);
            int nameLength = u16(archive, central + 28);
            int extraLength = u16(archive, central + 30);
            int commentLength = u16(archive, central + 32);
            int local = (int) u32(archive, central + 42);
            check(u32(archive, local) == 0x04034b50L, "local signature #" + index);
            int localName = u16(archive, local + 26);
            int localExtra = u16(archive, local + 28);
            int data = local + 30 + localName + localExtra;
            byte[] compressedBytes = Arrays.copyOfRange(archive, data, data + compressed);
            payloads.add(new Payload(method, crc, compressed, uncompressed,
                    MessageDigest.getInstance("SHA-256").digest(compressedBytes)));
            central += 46 + nameLength + extraLength + commentLength;
        }
        return payloads;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Payload {
        final int method;
        final long crc;
        final int compressed;
        final int uncompressed;
        final byte[] digest;

        Payload(int method, long crc, int compressed, int uncompressed, byte[] digest) {
            this.method = method;
            this.crc = crc;
            this.compressed = compressed;
            this.uncompressed = uncompressed;
            this.digest = digest;
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Payload)) return false;
            Payload other = (Payload) value;
            return method == other.method && crc == other.crc
                    && compressed == other.compressed && uncompressed == other.uncompressed
                    && Arrays.equals(digest, other.digest);
        }

        @Override public int hashCode() {
            int result = method;
            result = result * 31 + (int) crc;
            result = result * 31 + compressed;
            result = result * 31 + uncompressed;
            return result * 31 + Arrays.hashCode(digest);
        }
    }
}
