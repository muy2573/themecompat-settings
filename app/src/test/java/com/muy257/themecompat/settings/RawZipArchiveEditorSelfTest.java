package com.muy257.themecompat.settings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Standalone regression check for raw-record business edits. */
public final class RawZipArchiveEditorSelfTest {
    private RawZipArchiveEditorSelfTest() { }

    public static void main(String[] args) throws Exception {
        byte[] original = fixture();
        Record keepBefore = record(original, "keep.txt");
        Map<String, byte[]> additions = new LinkedHashMap<>();
        additions.put("新增.txt", bytes("added"));
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        replacements.put("replace.txt", bytes("replacement payload with a different length"));
        RawZipArchiveEditor.Result result = RawZipArchiveEditor.edit(original, additions,
                replacements, Set.of("remove.txt"));
        check(result.additions == 1 && result.replacements == 1 && result.removals == 1,
                "wrong edit counts");

        Map<String, byte[]> values = values(result.bytes);
        check(Arrays.equals(bytes("untouched payload"), values.get("keep.txt")),
                "untouched value changed");
        check(Arrays.equals(bytes("replacement payload with a different length"),
                values.get("replace.txt")), "replacement missing");
        check(Arrays.equals(bytes("added"), values.get("新增.txt")), "addition missing");
        check(!values.containsKey("remove.txt"), "removal failed");

        Record keepAfter = record(result.bytes, "keep.txt");
        check(Arrays.equals(keepBefore.localRecord, keepAfter.localRecord),
                "untouched local record changed");
        check(Arrays.equals(withoutOffset(keepBefore.centralRecord),
                withoutOffset(keepAfter.centralRecord)),
                "untouched central metadata changed");

        byte[] duplicate = rename(fixture(), "same-b.txt", "same-a.txt");
        boolean rejected = false;
        try {
            RawZipArchiveEditor.edit(duplicate, additions, Map.of(), Set.of());
        } catch (IOException expected) {
            rejected = expected.getMessage().contains("同名重复路径");
        }
        check(rejected, "true duplicate pathname was not rejected explicitly");
        System.out.println("PASS raw ZIP edits: untouched bytes/metadata, counts, duplicates");
    }

    private static byte[] fixture() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            add(output, "keep.txt", "untouched payload", "keep-comment", 1234567890000L);
            add(output, "replace.txt", "old", "replace-comment", 1234567900000L);
            add(output, "remove.txt", "remove", "remove-comment", 1234567910000L);
            add(output, "same-a.txt", "a", "a-comment", 1234567920000L);
            add(output, "same-b.txt", "b", "b-comment", 1234567930000L);
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream output, String name, String value,
                            String comment, long time) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setComment(comment);
        entry.setTime(time);
        entry.setExtra(new byte[]{(byte) 0xfe, (byte) 0xca, 2, 0, 1, 2});
        output.putNextEntry(entry);
        output.write(bytes(value));
        output.closeEntry();
    }

    private static Map<String, byte[]> values(byte[] archive) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[256];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream value = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) value.write(buffer, 0, read);
                result.put(entry.getName(), value.toByteArray());
                input.closeEntry();
            }
        }
        return result;
    }

    private static Record record(byte[] archive, String wanted) throws Exception {
        int eocd = findEocd(archive);
        int count = u16(archive, eocd + 10);
        int central = (int) u32(archive, eocd + 16);
        for (int index = 0; index < count; index++) {
            int nameLength = u16(archive, central + 28);
            int extraLength = u16(archive, central + 30);
            int commentLength = u16(archive, central + 32);
            int centralLength = 46 + nameLength + extraLength + commentLength;
            String name = new String(archive, central + 46, nameLength, StandardCharsets.UTF_8);
            if (wanted.equals(name)) {
                int local = (int) u32(archive, central + 42);
                int flags = u16(archive, central + 8);
                int compressed = (int) u32(archive, central + 20);
                int localName = u16(archive, local + 26);
                int localExtra = u16(archive, local + 28);
                int end = local + 30 + localName + localExtra + compressed;
                if ((flags & 8) != 0) end += u32(archive, end) == 0x08074b50L ? 16 : 12;
                return new Record(Arrays.copyOfRange(archive, local, end),
                        Arrays.copyOfRange(archive, central, central + centralLength));
            }
            central += centralLength;
        }
        throw new AssertionError("missing record: " + wanted);
    }

    private static byte[] withoutOffset(byte[] central) {
        byte[] result = new byte[central.length - 4];
        System.arraycopy(central, 0, result, 0, 42);
        System.arraycopy(central, 46, result, 42, central.length - 46);
        return result;
    }

    private static byte[] rename(byte[] archive, String from, String to) throws Exception {
        check(from.length() == to.length(), "rename fixture requires equal byte lengths");
        byte[] result = Arrays.copyOf(archive, archive.length);
        int eocd = findEocd(result);
        int count = u16(result, eocd + 10);
        int central = (int) u32(result, eocd + 16);
        for (int index = 0; index < count; index++) {
            int nameLength = u16(result, central + 28);
            int extraLength = u16(result, central + 30);
            int commentLength = u16(result, central + 32);
            String name = new String(result, central + 46, nameLength, StandardCharsets.UTF_8);
            if (from.equals(name)) {
                int local = (int) u32(result, central + 42);
                byte[] target = bytes(to);
                System.arraycopy(target, 0, result, central + 46, target.length);
                System.arraycopy(target, 0, result, local + 30, target.length);
                return result;
            }
            central += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("rename source missing");
    }

    private static int findEocd(byte[] archive) {
        for (int cursor = archive.length - 22; cursor >= 0; cursor--) {
            if (u32(archive, cursor) == 0x06054b50L
                    && cursor + 22 + u16(archive, cursor + 20) == archive.length) return cursor;
        }
        throw new AssertionError("EOCD missing");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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

    private static final class Record {
        final byte[] localRecord;
        final byte[] centralRecord;

        Record(byte[] localRecord, byte[] centralRecord) {
            this.localRecord = localRecord;
            this.centralRecord = centralRecord;
        }
    }
}
