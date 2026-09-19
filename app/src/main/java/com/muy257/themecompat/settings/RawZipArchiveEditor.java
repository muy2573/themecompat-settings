package com.muy257.themecompat.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Applies small edits to an already pathname-normalized, non-ZIP64 archive.
 *
 * <p>Untouched local records and central-directory metadata are copied as raw bytes. This is
 * important for theme modules: rewriting them with {@code ZipOutputStream} needlessly changes
 * compression, timestamps, extra fields, comments and platform attributes for every resource.</p>
 */
final class RawZipArchiveEditor {
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L;
    private static final int UTF8_FLAG = 0x0800;
    private static final int DATA_DESCRIPTOR_FLAG = 0x0008;
    private static final int ENCRYPTED_FLAG = 0x0001;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;
    private static final int MAX_ENTRIES = 10000;

    private RawZipArchiveEditor() { }

    static final class Result {
        final byte[] bytes;
        final int additions;
        final int replacements;
        final int removals;

        Result(byte[] bytes, int additions, int replacements, int removals) {
            this.bytes = bytes;
            this.additions = additions;
            this.replacements = replacements;
            this.removals = removals;
        }
    }

    static Result edit(byte[] archive, Map<String, byte[]> additions,
                       Map<String, byte[]> replacements, Set<String> removals)
            throws IOException {
        if (archive == null || archive.length < 22) throw new IOException("主题模块 ZIP 过短");
        if (additions.isEmpty() && replacements.isEmpty() && removals.isEmpty()) {
            return new Result(archive, 0, 0, 0);
        }
        rejectOverlappingRequests(additions, replacements, removals);

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
        Map<String, Entry> byName = new HashMap<>();
        int cursor = centralOffset;
        for (int index = 0; index < totalEntries; index++) {
            requireRange(archive, cursor, 46, "Central Directory header");
            if (u32(archive, cursor) != CENTRAL_SIGNATURE) {
                throw new IOException("Central Directory entry 签名错误 #" + index);
            }
            int nameLength = u16(archive, cursor + 28);
            int extraLength = u16(archive, cursor + 30);
            int commentLength = u16(archive, cursor + 32);
            int recordLength = 46 + nameLength + extraLength + commentLength;
            requireRange(archive, cursor, recordLength, "Central Directory entry");
            byte[] rawName = slice(archive, cursor + 46, nameLength);
            String name = decodeUtf8(rawName);
            validatePath(name);
            Entry entry = new Entry(name, rawName, slice(archive, cursor, 46),
                    slice(archive, cursor + 46 + nameLength, extraLength),
                    slice(archive, cursor + 46 + nameLength + extraLength, commentLength),
                    checkedInt(u32(archive, cursor + 42), "Local Header 偏移过大"),
                    u16(archive, cursor + 8), u16(archive, cursor + 10),
                    checkedInt(u32(archive, cursor + 20), "压缩数据过大"));
            if (byName.put(name, entry) != null) {
                throw new IOException("模块包含同名重复路径，无法安全执行业务修补：" + name);
            }
            entries.add(entry);
            cursor += recordLength;
        }
        if (cursor != centralOffset + centralSize) {
            throw new IOException("Central Directory 大小与条目不一致");
        }
        for (String name : additions.keySet()) {
            validatePath(name);
            if (byName.containsKey(name)) throw new IOException("新增路径已存在：" + name);
        }
        for (String name : replacements.keySet()) {
            if (!byName.containsKey(name)) throw new IOException("替换路径不存在：" + name);
        }
        for (String name : removals) {
            if (!byName.containsKey(name)) throw new IOException("移除路径不存在：" + name);
        }

        List<Entry> physical = new ArrayList<>(entries);
        physical.sort(Comparator.comparingInt(entry -> entry.localOffset));
        for (int index = 0; index < physical.size(); index++) {
            Entry entry = physical.get(index);
            int nextOffset = index + 1 < physical.size()
                    ? physical.get(index + 1).localOffset : centralOffset;
            inspectLocalRecord(archive, entry, nextOffset);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                archive.length + additions.size() * 1024);
        int sourceCursor = 0;
        for (Entry entry : physical) {
            if (entry.localOffset < sourceCursor) {
                throw new IOException("Local Header 顺序或偏移损坏：" + entry.name);
            }
            output.write(archive, sourceCursor, entry.localOffset - sourceCursor);
            if (!removals.contains(entry.name)) {
                entry.newLocalOffset = output.size();
                byte[] replacement = replacements.get(entry.name);
                if (replacement == null) {
                    output.write(archive, entry.localOffset, entry.nextOffset - entry.localOffset);
                } else {
                    writeReplacement(output, archive, entry, replacement);
                }
            }
            sourceCursor = entry.nextOffset;
        }
        output.write(archive, sourceCursor, centralOffset - sourceCursor);

        List<Entry> addedEntries = new ArrayList<>(additions.size());
        for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
            Entry added = createAddition(addition.getKey(), addition.getValue());
            added.newLocalOffset = output.size();
            output.write(added.generatedLocal, 0, added.generatedLocal.length);
            addedEntries.add(added);
        }

        int newCentralOffset = output.size();
        for (Entry entry : entries) {
            if (removals.contains(entry.name)) continue;
            byte[] fixed = Arrays.copyOf(entry.centralFixed, entry.centralFixed.length);
            byte[] replacement = replacements.get(entry.name);
            if (replacement != null) patchCentralForReplacement(fixed, entry);
            put32(fixed, 42, entry.newLocalOffset);
            output.write(fixed, 0, fixed.length);
            output.write(entry.rawName, 0, entry.rawName.length);
            output.write(entry.centralExtra, 0, entry.centralExtra.length);
            output.write(entry.comment, 0, entry.comment.length);
        }
        for (Entry entry : addedEntries) {
            byte[] fixed = Arrays.copyOf(entry.centralFixed, entry.centralFixed.length);
            put32(fixed, 42, entry.newLocalOffset);
            output.write(fixed, 0, fixed.length);
            output.write(entry.rawName, 0, entry.rawName.length);
        }
        int newCentralSize = output.size() - newCentralOffset;
        int oldCentralEnd = centralOffset + centralSize;
        output.write(archive, oldCentralEnd, eocd - oldCentralEnd);
        byte[] eocdAndComment = slice(archive, eocd, archive.length - eocd);
        int newCount = entries.size() - removals.size() + addedEntries.size();
        if (newCount >= 0xffff) throw new IOException("修补后条目数需要 ZIP64");
        put16(eocdAndComment, 8, newCount);
        put16(eocdAndComment, 10, newCount);
        put32(eocdAndComment, 12, newCentralSize);
        put32(eocdAndComment, 16, newCentralOffset);
        output.write(eocdAndComment, 0, eocdAndComment.length);
        return new Result(output.toByteArray(), addedEntries.size(),
                replacements.size(), removals.size());
    }

    private static void rejectOverlappingRequests(Map<String, byte[]> additions,
                                                  Map<String, byte[]> replacements,
                                                  Set<String> removals) throws IOException {
        Set<String> requested = new HashSet<>();
        for (String name : additions.keySet()) requested.add(name);
        for (String name : replacements.keySet()) {
            if (!requested.add(name)) throw new IOException("ZIP 路径同时被新增和替换：" + name);
        }
        for (String name : removals) {
            if (!requested.add(name)) throw new IOException("ZIP 路径收到冲突修补操作：" + name);
        }
    }

    private static void inspectLocalRecord(byte[] archive, Entry entry, int nextOffset)
            throws IOException {
        if (entry.localOffset < 0 || entry.localOffset >= nextOffset || nextOffset > archive.length) {
            throw new IOException("Local Header 顺序或偏移损坏：" + entry.name);
        }
        requireRange(archive, entry.localOffset, 30, "Local Header");
        if (u32(archive, entry.localOffset) != LOCAL_SIGNATURE) {
            throw new IOException("Local Header 签名错误：" + entry.name);
        }
        int localFlags = u16(archive, entry.localOffset + 6);
        int localMethod = u16(archive, entry.localOffset + 8);
        int nameLength = u16(archive, entry.localOffset + 26);
        int extraLength = u16(archive, entry.localOffset + 28);
        int dataOffset = entry.localOffset + 30 + nameLength + extraLength;
        requireRange(archive, entry.localOffset, 30 + nameLength + extraLength,
                "Local Header pathname");
        if (!Arrays.equals(entry.rawName, slice(archive, entry.localOffset + 30, nameLength))) {
            throw new IOException("Local/Central pathname 不一致：" + entry.name);
        }
        if (localMethod != entry.method || localFlags != entry.flags) {
            throw new IOException("Local/Central 压缩参数不一致：" + entry.name);
        }
        int descriptorLength = 0;
        int dataEnd = dataOffset + entry.compressedSize;
        if ((entry.flags & DATA_DESCRIPTOR_FLAG) != 0) {
            requireRange(archive, dataEnd, 12, "Data Descriptor");
            descriptorLength = u32(archive, dataEnd) == DATA_DESCRIPTOR_SIGNATURE ? 16 : 12;
        }
        requireRange(archive, dataOffset, entry.compressedSize + descriptorLength,
                "压缩数据");
        if (dataEnd + descriptorLength > nextOffset) {
            throw new IOException("压缩数据与下一条目重叠：" + entry.name);
        }
        entry.localFixed = slice(archive, entry.localOffset, 30);
        entry.localExtra = slice(archive, entry.localOffset + 30 + nameLength, extraLength);
        entry.trailingOffset = dataEnd + descriptorLength;
        entry.nextOffset = nextOffset;
    }

    private static void writeReplacement(ByteArrayOutputStream output, byte[] archive,
                                         Entry entry, byte[] value) throws IOException {
        if ((entry.flags & ENCRYPTED_FLAG) != 0) {
            throw new IOException("无法替换加密 ZIP 条目：" + entry.name);
        }
        if (entry.method != STORED && entry.method != DEFLATED) {
            throw new IOException("无法保留不支持的压缩方法 " + entry.method + "：" + entry.name);
        }
        byte[] compressed = entry.method == STORED ? value : deflate(value);
        CRC32 crc = new CRC32();
        crc.update(value);
        byte[] local = Arrays.copyOf(entry.localFixed, entry.localFixed.length);
        int flags = entry.flags & ~DATA_DESCRIPTOR_FLAG;
        put16(local, 6, flags);
        put32(local, 14, crc.getValue());
        put32(local, 18, compressed.length);
        put32(local, 22, value.length);
        output.write(local, 0, local.length);
        output.write(entry.rawName, 0, entry.rawName.length);
        output.write(entry.localExtra, 0, entry.localExtra.length);
        output.write(compressed, 0, compressed.length);
        output.write(archive, entry.trailingOffset, entry.nextOffset - entry.trailingOffset);
        entry.replacementFlags = flags;
        entry.replacementCrc = crc.getValue();
        entry.replacementCompressedSize = compressed.length;
        entry.replacementSize = value.length;
    }

    private static void patchCentralForReplacement(byte[] fixed, Entry entry) {
        put16(fixed, 8, entry.replacementFlags);
        put32(fixed, 16, entry.replacementCrc);
        put32(fixed, 20, entry.replacementCompressedSize);
        put32(fixed, 24, entry.replacementSize);
    }

    private static Entry createAddition(String name, byte[] value) throws IOException {
        byte[] rawName = name.getBytes(StandardCharsets.UTF_8);
        if (rawName.length > 0xffff) throw new IOException("新增 ZIP pathname 过长：" + name);
        byte[] compressed = deflate(value);
        CRC32 crc = new CRC32();
        crc.update(value);
        byte[] local = new byte[30 + rawName.length + compressed.length];
        put32(local, 0, LOCAL_SIGNATURE);
        put16(local, 4, 20);
        put16(local, 6, UTF8_FLAG);
        put16(local, 8, DEFLATED);
        put32(local, 14, crc.getValue());
        put32(local, 18, compressed.length);
        put32(local, 22, value.length);
        put16(local, 26, rawName.length);
        System.arraycopy(rawName, 0, local, 30, rawName.length);
        System.arraycopy(compressed, 0, local, 30 + rawName.length, compressed.length);

        byte[] central = new byte[46];
        put32(central, 0, CENTRAL_SIGNATURE);
        put16(central, 4, 20);
        put16(central, 6, 20);
        put16(central, 8, UTF8_FLAG);
        put16(central, 10, DEFLATED);
        put32(central, 16, crc.getValue());
        put32(central, 20, compressed.length);
        put32(central, 24, value.length);
        put16(central, 28, rawName.length);
        Entry entry = new Entry(name, rawName, central, new byte[0], new byte[0],
                -1, UTF8_FLAG, DEFLATED, compressed.length);
        entry.generatedLocal = local;
        return entry;
    }

    private static byte[] deflate(byte[] value) throws IOException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(value);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(32, value.length / 2));
        byte[] buffer = new byte[8192];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0 && deflater.needsInput()) {
                    throw new IOException("DEFLATE 提前结束");
                }
                compressed.write(buffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        return compressed.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            CharBuffer value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return value.toString();
        } catch (CharacterCodingException error) {
            throw new IOException("业务修补前 pathname 尚未规范化为 UTF-8", error);
        }
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("不安全的 ZIP pathname：" + path);
        }
        for (String component : path.split("/", -1)) {
            if ("..".equals(component)) throw new IOException("ZIP pathname 包含 ..：" + path);
        }
    }

    private static int findEocd(byte[] bytes) throws IOException {
        int earliest = Math.max(0, bytes.length - 22 - 0xffff);
        for (int cursor = bytes.length - 22; cursor >= earliest; cursor--) {
            if (u32(bytes, cursor) == EOCD_SIGNATURE
                    && cursor + 22 + u16(bytes, cursor + 20) == bytes.length) return cursor;
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

    private static final class Entry {
        final String name;
        final byte[] rawName;
        final byte[] centralFixed;
        final byte[] centralExtra;
        final byte[] comment;
        final int localOffset;
        final int flags;
        final int method;
        final int compressedSize;
        int nextOffset;
        int trailingOffset;
        int newLocalOffset;
        byte[] localFixed;
        byte[] localExtra;
        byte[] generatedLocal;
        int replacementFlags;
        long replacementCrc;
        int replacementCompressedSize;
        int replacementSize;

        Entry(String name, byte[] rawName, byte[] centralFixed, byte[] centralExtra,
              byte[] comment, int localOffset, int flags, int method, int compressedSize) {
            this.name = name;
            this.rawName = rawName;
            this.centralFixed = centralFixed;
            this.centralExtra = centralExtra;
            this.comment = comment;
            this.localOffset = localOffset;
            this.flags = flags;
            this.method = method;
            this.compressedSize = compressedSize;
        }
    }
}
