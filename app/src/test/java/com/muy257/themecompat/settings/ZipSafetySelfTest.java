package com.muy257.themecompat.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone rejection tests for hostile or unsupported module ZIPs. */
public final class ZipSafetySelfTest {
    private ZipSafetySelfTest() { }

    public static void main(String[] args) throws Exception {
        expectFailure(zip("../escape.png"), "ZIP pathname");

        byte[] malformedUtf8 = zip("a.txt");
        int eocd = findEocd(malformedUtf8);
        int central = (int) u32(malformedUtf8, eocd + 16);
        int local = (int) u32(malformedUtf8, central + 42);
        malformedUtf8[central + 46] = (byte) 0xff;
        malformedUtf8[local + 30] = (byte) 0xff;
        put16(malformedUtf8, central + 8, u16(malformedUtf8, central + 8) | 0x0800);
        put16(malformedUtf8, local + 6, u16(malformedUtf8, local + 6) | 0x0800);
        expectFailure(malformedUtf8, "UTF-8");

        byte[] tooMany = zip("a.txt");
        int tooManyEocd = findEocd(tooMany);
        put16(tooMany, tooManyEocd + 8, 10001);
        put16(tooMany, tooManyEocd + 10, 10001);
        expectFailure(tooMany, "10000");
        System.out.println("PASS ZIP safety: traversal, malformed UTF-8, entry-count limit");
    }

    private static byte[] zip(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(name));
            output.write("x".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void expectFailure(byte[] archive, String messagePart) throws Exception {
        try {
            ZipPathnameNormalizer.normalize(archive);
            throw new AssertionError("expected rejection containing: " + messagePart);
        } catch (IOException expected) {
            if (!expected.getMessage().contains(messagePart)) {
                throw new AssertionError("wrong rejection: " + expected.getMessage(), expected);
            }
        }
    }

    private static int findEocd(byte[] archive) {
        for (int cursor = archive.length - 22; cursor >= 0; cursor--) {
            if (u32(archive, cursor) == 0x06054b50L
                    && cursor + 22 + u16(archive, cursor + 20) == archive.length) return cursor;
        }
        throw new AssertionError("EOCD missing");
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

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }
}
