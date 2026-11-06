package com.tss.labeling.imports;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static com.tss.labeling.imports.ArchiveParser.rejected;

/** Bounds the central directory before ZipFile allocates its entry objects. */
final class ZipSafety {
    private ZipSafety() { }

    static void check(Path file, ImportSettings settings) throws IOException {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 22) throw rejected("INVALID_ARCHIVE", "ZIP 文件不完整");
            int length = (int) Math.min(size, 65557);
            ByteBuffer tail = read(channel, size - length, length);
            int end = -1;
            for (int i = length - 22; i >= 0; i--) {
                if (tail.getInt(i) == 0x06054b50 && i + 22 + unsigned(tail, i + 20) == length) { end = i; break; }
            }
            if (end < 0) throw rejected("INVALID_ARCHIVE", "未找到完整 ZIP 结束目录");
            if (unsigned(tail, end + 4) != 0 || unsigned(tail, end + 6) != 0) throw rejected("UNSUPPORTED_FORMAT", "不支持分卷 ZIP");
            long count = unsigned(tail, end + 10);
            long directorySize = Integer.toUnsignedLong(tail.getInt(end + 12));
            long offset = Integer.toUnsignedLong(tail.getInt(end + 16));
            long directoryEnd = size - length + end;
            ByteBuffer locator = directoryEnd >= 20 ? read(channel, directoryEnd - 20, 20) : null;
            boolean hasZip64 = locator != null && locator.getInt(0) == 0x07064b50;
            boolean needsZip64 = count == 65535 || directorySize == 0xffffffffL || offset == 0xffffffffL;
            // Commons follows a ZIP64 locator even without ZIP32 sentinel values.
            if (hasZip64 || needsZip64) {
                if (!hasZip64 || locator.getInt(4) != 0 || locator.getInt(16) != 1) {
                    throw rejected("UNSUPPORTED_FORMAT", "ZIP64 目录定位信息无效或为分卷");
                }
                long recordOffset = locator.getLong(8);
                ByteBuffer record = read(channel, recordOffset, 56);
                if (record.getInt(0) != 0x06064b50 || record.getLong(4) < 44
                        || record.getLong(4) != directoryEnd - 20 - recordOffset - 12
                        || record.getInt(16) != 0 || record.getInt(20) != 0 || record.getLong(24) != record.getLong(32)) {
                    throw rejected("INVALID_ARCHIVE", "ZIP64 目录无效");
                }
                if ((count != 65535 && count != record.getLong(32))
                        || (unsigned(tail, end + 8) != 65535 && unsigned(tail, end + 8) != record.getLong(24))
                        || (directorySize != 0xffffffffL && directorySize != record.getLong(40))
                        || (offset != 0xffffffffL && offset != record.getLong(48))) {
                    throw rejected("INVALID_ARCHIVE", "ZIP32 与 ZIP64 目录信息矛盾");
                }
                count = record.getLong(32); directorySize = record.getLong(40); offset = record.getLong(48);
                directoryEnd = recordOffset;
            } else if (unsigned(tail, end + 8) != count) {
                throw rejected("INVALID_ARCHIVE", "ZIP 条目计数不一致");
            }
            if (count > settings.maxEntries()) throw rejected("EXPANSION_LIMIT", "压缩包条目数超过配置上限");
            if (count < 0 || offset < 0 || directorySize < 0 || directorySize > directoryEnd || offset != directoryEnd - directorySize) {
                throw rejected("INVALID_ARCHIVE", "ZIP 中央目录范围无效");
            }
            long position = offset, limit = offset + directorySize, actual = 0;
            while (position < limit) {
                if (++actual > settings.maxEntries()) throw rejected("EXPANSION_LIMIT", "压缩包条目数超过配置上限");
                if (limit - position < 46) throw rejected("INVALID_ARCHIVE", "ZIP 中央目录被截断");
                ByteBuffer entry = read(channel, position, 46);
                if (entry.getInt(0) != 0x02014b50) throw rejected("INVALID_ARCHIVE", "ZIP 中央目录条目无效");
                int nameLength = unsigned(entry, 28);
                long entrySize = 46L + nameLength + unsigned(entry, 30) + unsigned(entry, 32);
                if (entrySize > limit - position) throw rejected("INVALID_ARCHIVE", "ZIP 中央目录条目越界");
                if (nameLength > 16384) throw rejected("UNSAFE_ARCHIVE", "ZIP 路径过长");
                Charset charset = (unsigned(entry, 8) & 0x800) != 0 ? StandardCharsets.UTF_8 : Charset.forName(settings.zipEncoding());
                try {
                    String rawName = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(read(channel, position + 46, nameLength)).toString();
                    ArchiveParser.sourcePath(rawName);
                } catch (java.nio.charset.CharacterCodingException exception) {
                    throw rejected("INVALID_ARCHIVE", "ZIP 路径编码无效，请使用 UTF-8 或配置正确编码");
                }
                position += entrySize;
            }
            if (actual != count) throw rejected("INVALID_ARCHIVE", "ZIP 实际目录与声明数量不一致");
        }
    }

    private static int unsigned(ByteBuffer bytes, int position) { return Short.toUnsignedInt(bytes.getShort(position)); }

    private static ByteBuffer read(SeekableByteChannel channel, long offset, int size) throws IOException {
        if (offset < 0 || offset > channel.size() - size) throw rejected("INVALID_ARCHIVE", "ZIP 目录位置越界");
        ByteBuffer bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw rejected("INVALID_ARCHIVE", "ZIP 目录不完整");
        bytes.flip();
        return bytes;
    }
}
