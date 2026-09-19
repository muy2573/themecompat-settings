package com.muy257.themecompat.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Canonicalizes legacy ZIP pathnames before java.util.zip is allowed to decode them.
 *
 * <p>Old Chinese MTZ modules commonly store a GBK name in the main ZIP filename while
 * carrying the real Unicode pathname in an Info-ZIP 0x7075 extra field. Android's ZIP
 * reader can turn malformed UTF-8 byte sequences into U+FFFD; distinct raw names may
 * consequently collapse to one Java String. This class reads the raw local/central
 * headers, validates 0x7075, and writes canonical UTF-8 names with GPBF bit 11 set.
 * Compressed payload bytes, CRCs, methods and entry order are left untouched.</p>
 */
final class ZipPathnameNormalizer {
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final int UTF8_FLAG = 0x0800;
    private static final int UNICODE_PATH_EXTRA = 0x7075;
    private static final int MAX_ENTRIES = 10000;
    private static final Charset GB18030 = Charset.forName("GB18030");

    private ZipPathnameNormalizer() { }

    static final class Result {
        final byte[] bytes;
        final int normalizedPathnames;
        final int legacyFallbacks;

        Result(byte[] bytes, int normalizedPathnames, int legacyFallbacks) {
            this.bytes = bytes;
            this.normalizedPathnames = normalizedPathnames;
            this.legacyFallbacks = legacyFallbacks;
        }

        boolean changed() {
            return normalizedPathnames > 0;
        }
    }

    static Result normalize(byte[] archive) throws IOException {
        if (archive == null || archive.length < 22) throw new IOException("主题模块 ZIP 过短");
        int eocd = findEocd(archive);
        int disk = u16(archive, eocd + 4);
        int centralDisk = u16(archive, eocd + 6);
        int diskEntries = u16(archive, eocd + 8);
        int totalEntries = u16(archive, eocd + 10);
        long centralSizeLong = u32(archive, eocd + 12);
        long centralOffsetLong = u32(archive, eocd + 16);
        if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries) {
            throw new IOException("暂不支持分卷主题模块 ZIP");
        }
        if (totalEntries == 0xffff || centralSizeLong == 0xffffffffL
                || centralOffsetLong == 0xffffffffL) {
            throw new IOException("暂不支持 ZIP64 主题模块");
        }
        if (totalEntries > MAX_ENTRIES) {
            throw new IOException("主题模块 ZIP 条目过多（上限 " + MAX_ENTRIES + "）");
        }
        int centralSize = checkedInt(centralSizeLong, "Central Directory 过大");
        int centralOffset = checkedInt(centralOffsetLong, "Central Directory 偏移过大");
        if (centralOffset < 0 || centralSize < 0
                || centralOffset + (long) centralSize > eocd) {
            throw new IOException("主题模块 Central Directory 损坏");
        }

        List<Entry> entries = new ArrayList<>(totalEntries);
        Map<String, byte[]> firstRawName = new HashMap<>();
        int cursor = centralOffset;
        int normalized = 0;
        int fallbacks = 0;
        for (int index = 0; index < totalEntries; index++) {
            requireRange(archive, cursor, 46, "Central Directory header");
            if (u32(archive, cursor) != CENTRAL_SIGNATURE) {
                throw new IOException("Central Directory entry 签名错误 #" + index);
            }
            int flags = u16(archive, cursor + 8);
            int nameLength = u16(archive, cursor + 28);
            int extraLength = u16(archive, cursor + 30);
            int commentLength = u16(archive, cursor + 32);
            int recordLength = 46 + nameLength + extraLength + commentLength;
            requireRange(archive, cursor, recordLength, "Central Directory entry");
            byte[] rawName = slice(archive, cursor + 46, nameLength);
            byte[] extra = slice(archive, cursor + 46 + nameLength, extraLength);
            byte[] comment = slice(archive, cursor + 46 + nameLength + extraLength, commentLength);
            DecodedName decoded = decodeName(rawName, extra, flags);
            validatePath(decoded.value);
            byte[] utf8Name = decoded.value.getBytes(StandardCharsets.UTF_8);
            boolean nonAscii = !isAscii(rawName);
            boolean changed = nonAscii
                    && ((flags & UTF8_FLAG) == 0 || !Arrays.equals(rawName, utf8Name));
            if (changed) normalized++;
            if (changed && decoded.legacyFallback) fallbacks++;

            byte[] previous = firstRawName.putIfAbsent(decoded.value, rawName);
            if (previous != null && !Arrays.equals(previous, rawName)) {
                throw new IOException("不同原始路径恢复为同一路径，已停止以避免丢文件：" + decoded.value);
            }

            long localOffsetLong = u32(archive, cursor + 42);
            int localOffset = checkedInt(localOffsetLong, "Local Header 偏移过大");
            entries.add(new Entry(slice(archive, cursor, 46), rawName,
                    changed ? utf8Name : rawName,
                    extra, comment, localOffset, changed, decoded.value));
            cursor += recordLength;
        }
        if (cursor != centralOffset + centralSize) {
            throw new IOException("Central Directory 大小与条目不一致");
        }
        if (normalized == 0) return new Result(archive, 0, 0);

        List<Entry> physical = new ArrayList<>(entries);
        physical.sort(Comparator.comparingInt(entry -> entry.localOffset));
        ByteArrayOutputStream output = new ByteArrayOutputStream(archive.length + normalized * 16);
        int sourceCursor = 0;
        for (Entry entry : physical) {
            if (entry.localOffset < sourceCursor || entry.localOffset >= centralOffset) {
                throw new IOException("Local Header 顺序或偏移损坏：" + entry.canonicalName);
            }
            output.write(archive, sourceCursor, entry.localOffset - sourceCursor);
            requireRange(archive, entry.localOffset, 30, "Local Header");
            if (u32(archive, entry.localOffset) != LOCAL_SIGNATURE) {
                throw new IOException("Local Header 签名错误：" + entry.canonicalName);
            }
            int localNameLength = u16(archive, entry.localOffset + 26);
            int localExtraLength = u16(archive, entry.localOffset + 28);
            requireRange(archive, entry.localOffset, 30 + localNameLength + localExtraLength,
                    "Local Header pathname");
            byte[] localRawName = slice(archive, entry.localOffset + 30, localNameLength);
            if (!Arrays.equals(localRawName, entry.rawName)) {
                throw new IOException("Local/Central pathname 不一致：" + entry.canonicalName);
            }
            byte[] localExtra = slice(archive, entry.localOffset + 30 + localNameLength,
                    localExtraLength);
            byte[] localFixed = slice(archive, entry.localOffset, 30);
            entry.newLocalOffset = output.size();
            if (entry.changed) {
                put16(localFixed, 6, u16(localFixed, 6) | UTF8_FLAG);
                put16(localFixed, 26, entry.outputName.length);
                localExtra = refreshUnicodePathExtra(localExtra, entry.outputName,
                        entry.canonicalName);
            }
            output.write(localFixed, 0, localFixed.length);
            output.write(entry.outputName, 0, entry.outputName.length);
            output.write(localExtra, 0, localExtra.length);
            sourceCursor = entry.localOffset + 30 + localNameLength + localExtraLength;
        }
        output.write(archive, sourceCursor, centralOffset - sourceCursor);

        int newCentralOffset = output.size();
        for (Entry entry : entries) {
            byte[] fixed = Arrays.copyOf(entry.centralFixed, entry.centralFixed.length);
            byte[] extra = entry.centralExtra;
            if (entry.changed) {
                put16(fixed, 8, u16(fixed, 8) | UTF8_FLAG);
                put16(fixed, 28, entry.outputName.length);
                extra = refreshUnicodePathExtra(extra, entry.outputName, entry.canonicalName);
            }
            put32(fixed, 42, entry.newLocalOffset);
            output.write(fixed, 0, fixed.length);
            output.write(entry.outputName, 0, entry.outputName.length);
            output.write(extra, 0, extra.length);
            output.write(entry.comment, 0, entry.comment.length);
        }
        int newCentralSize = output.size() - newCentralOffset;
        int oldCentralEnd = centralOffset + centralSize;
        output.write(archive, oldCentralEnd, eocd - oldCentralEnd);
        byte[] eocdAndComment = slice(archive, eocd, archive.length - eocd);
        put32(eocdAndComment, 12, newCentralSize);
        put32(eocdAndComment, 16, newCentralOffset);
        output.write(eocdAndComment, 0, eocdAndComment.length);
        return new Result(output.toByteArray(), normalized, fallbacks);
    }

    private static DecodedName decodeName(byte[] rawName, byte[] extra, int flags)
            throws IOException {
        if ((flags & UTF8_FLAG) != 0) {
            return new DecodedName(decodeStrict(rawName, StandardCharsets.UTF_8,
                    "UTF-8 pathname"), false);
        }
        String unicode = unicodePathFromExtra(rawName, extra);
        if (unicode != null) return new DecodedName(unicode, false);
        if (isAscii(rawName)) {
            return new DecodedName(new String(rawName, StandardCharsets.US_ASCII), false);
        }
        String legacy = decodeStrict(rawName, GB18030, "legacy GB18030 pathname");
        if (!Arrays.equals(rawName, legacy.getBytes(GB18030))) {
            throw new IOException("legacy pathname 无法无损 round-trip");
        }
        return new DecodedName(legacy, true);
    }

    private static String unicodePathFromExtra(byte[] rawName, byte[] extra) throws IOException {
        int cursor = 0;
        while (cursor + 4 <= extra.length) {
            int id = u16(extra, cursor);
            int size = u16(extra, cursor + 2);
            if (cursor + 4 + size > extra.length) throw new IOException("ZIP extra field 损坏");
            if (id == UNICODE_PATH_EXTRA && size >= 5 && extra[cursor + 4] == 1) {
                long expected = u32(extra, cursor + 5);
                CRC32 crc = new CRC32();
                crc.update(rawName);
                if (crc.getValue() == expected) {
                    return decodeStrict(slice(extra, cursor + 9, size - 5),
                            StandardCharsets.UTF_8, "0x7075 Unicode Path");
                }
            }
            cursor += 4 + size;
        }
        if (cursor != extra.length) throw new IOException("ZIP extra field 尾部损坏");
        return null;
    }

    private static byte[] refreshUnicodePathExtra(byte[] source, byte[] utf8Name,
                                                   String canonicalName) throws IOException {
        byte[] extra = Arrays.copyOf(source, source.length);
        int cursor = 0;
        while (cursor + 4 <= extra.length) {
            int id = u16(extra, cursor);
            int size = u16(extra, cursor + 2);
            if (cursor + 4 + size > extra.length) throw new IOException("ZIP extra field 损坏");
            if (id == UNICODE_PATH_EXTRA && size >= 5 && extra[cursor + 4] == 1) {
                byte[] payload = canonicalName.getBytes(StandardCharsets.UTF_8);
                if (payload.length == size - 5) {
                    CRC32 crc = new CRC32();
                    crc.update(utf8Name);
                    put32(extra, cursor + 5, crc.getValue());
                    System.arraycopy(payload, 0, extra, cursor + 9, payload.length);
                }
            }
            cursor += 4 + size;
        }
        if (cursor != extra.length) throw new IOException("ZIP extra field 尾部损坏");
        return extra;
    }

    private static String decodeStrict(byte[] bytes, Charset charset, String label)
            throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IOException(label + " 解码失败", error);
        }
    }

    private static void validatePath(String path) throws IOException {
        if (path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("不安全的 ZIP pathname：" + path);
        }
        for (String component : path.split("/", -1)) {
            if ("..".equals(component)) throw new IOException("ZIP pathname 包含 ..：" + path);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte value : bytes) if ((value & 0x80) != 0) return false;
        return true;
    }

    private static int findEocd(byte[] bytes) throws IOException {
        int earliest = Math.max(0, bytes.length - 22 - 0xffff);
        for (int cursor = bytes.length - 22; cursor >= earliest; cursor--) {
            if (u32(bytes, cursor) != EOCD_SIGNATURE) continue;
            int commentLength = u16(bytes, cursor + 20);
            if (cursor + 22 + commentLength == bytes.length) return cursor;
        }
        throw new IOException("找不到 ZIP EOCD");
    }

    private static int checkedInt(long value, String message) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException(message);
        return (int) value;
    }

    private static void requireRange(byte[] bytes, int offset, int length, String label)
            throws IOException {
        if (offset < 0 || length < 0 || offset + (long) length > bytes.length) {
            throw new IOException(label + " 越界");
        }
    }

    private static byte[] slice(byte[] bytes, int offset, int length) {
        return Arrays.copyOfRange(bytes, offset, offset + length);
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

    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static final class DecodedName {
        final String value;
        final boolean legacyFallback;

        DecodedName(String value, boolean legacyFallback) {
            this.value = value;
            this.legacyFallback = legacyFallback;
        }
    }

    private static final class Entry {
        final byte[] centralFixed;
        final byte[] rawName;
        final byte[] outputName;
        final byte[] centralExtra;
        final byte[] comment;
        final int localOffset;
        final boolean changed;
        final String canonicalName;
        int newLocalOffset;

        Entry(byte[] centralFixed, byte[] rawName, byte[] outputName, byte[] centralExtra,
              byte[] comment, int localOffset, boolean changed, String canonicalName) {
            this.centralFixed = centralFixed;
            this.rawName = rawName;
            this.outputName = outputName;
            this.centralExtra = centralExtra;
            this.comment = comment;
            this.localOffset = localOffset;
            this.changed = changed;
            this.canonicalName = canonicalName;
        }
    }
}
