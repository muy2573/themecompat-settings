package com.muy257.themecompat.settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compares two canonical ZIPs without inflating entries. */
public final class RawZipDiffAudit {
    private RawZipDiffAudit() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("normalized-original.zip patched.zip");
        Map<String, Record> before = records(Files.readAllBytes(Path.of(args[0])));
        Map<String, Record> after = records(Files.readAllBytes(Path.of(args[1])));
        int added = 0;
        int removed = 0;
        int changed = 0;
        int exact = 0;
        for (Map.Entry<String, Record> item : before.entrySet()) {
            Record newer = after.get(item.getKey());
            if (newer == null) {
                removed++;
            } else if (item.getValue().sameExceptOffset(newer)) {
                exact++;
            } else {
                changed++;
                System.out.println("changed=" + item.getKey());
            }
        }
        for (String name : after.keySet()) if (!before.containsKey(name)) added++;
        System.out.println("PASS before=" + before.size() + " after=" + after.size()
                + " exactUntouched=" + exact + " changed=" + changed
                + " added=" + added + " removed=" + removed);
    }

    private static Map<String, Record> records(byte[] archive) throws Exception {
        int eocd = findEocd(archive);
        int count = u16(archive, eocd + 10);
        int central = (int) u32(archive, eocd + 16);
        Map<String, Record> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            if (u32(archive, central) != 0x02014b50L) throw new AssertionError("bad central #" + index);
            int flags = u16(archive, central + 8);
            int compressed = (int) u32(archive, central + 20);
            int nameLength = u16(archive, central + 28);
            int extraLength = u16(archive, central + 30);
            int commentLength = u16(archive, central + 32);
            int centralLength = 46 + nameLength + extraLength + commentLength;
            String name = new String(archive, central + 46, nameLength, StandardCharsets.UTF_8);
            if (name.indexOf('\ufffd') >= 0) throw new AssertionError("U+FFFD: " + name);
            int local = (int) u32(archive, central + 42);
            if (u32(archive, local) != 0x04034b50L) throw new AssertionError("bad local: " + name);
            int localName = u16(archive, local + 26);
            int localExtra = u16(archive, local + 28);
            int end = local + 30 + localName + localExtra + compressed;
            if ((flags & 8) != 0) end += u32(archive, end) == 0x08074b50L ? 16 : 12;
            byte[] centralRecord = Arrays.copyOfRange(archive, central, central + centralLength);
            Arrays.fill(centralRecord, 42, 46, (byte) 0);
            Record previous = result.put(name, new Record(
                    Arrays.copyOfRange(archive, local, end), centralRecord));
            if (previous != null) throw new AssertionError("duplicate: " + name);
            central += centralLength;
        }
        return result;
    }

    private static int findEocd(byte[] archive) {
        for (int cursor = archive.length - 22; cursor >= Math.max(0, archive.length - 65557); cursor--) {
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

    private static final class Record {
        final byte[] local;
        final byte[] central;

        Record(byte[] local, byte[] central) {
            this.local = local;
            this.central = central;
        }

        boolean sameExceptOffset(Record other) {
            return Arrays.equals(local, other.local) && Arrays.equals(central, other.central);
        }
    }
}
