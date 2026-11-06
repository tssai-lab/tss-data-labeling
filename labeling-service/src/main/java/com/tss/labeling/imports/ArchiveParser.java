package com.tss.labeling.imports;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.CRC32;

/** Extracts into generated filenames only; user-controlled paths are metadata. */
@Component
public class ArchiveParser {
    private final ImportSettings settings;
    private final ImportStorage storage;

    public ArchiveParser(ImportSettings settings, ImportStorage storage) {
        this.settings = settings;
        this.storage = storage;
    }

    ParsedArchive parse(UUID batch) throws IOException {
        ZipSafety.check(storage.archive(batch), settings);
        List<ParsedSample> samples = new ArrayList<>();
        List<FileError> errors = new ArrayList<>();
        int errorCount = 0;
        try (ZipFile zip = ZipFile.builder().setPath(storage.archive(batch)).setCharset(settings.zipEncoding()).get()) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            var enumeration = zip.getEntries();
            long declaredBytes = 0;
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                if (entries.size() >= settings.maxEntries()) throw rejected("EXPANSION_LIMIT", "压缩包条目数超过配置上限");
                String path = sourcePath(entry.getName());
                int type = entry.getUnixMode() & 0170000;
                if (entry.isUnixSymlink() || (type != 0 && type != 0100000 && type != 0040000)) {
                    throw rejected("UNSAFE_ARCHIVE", "压缩包含符号链接或特殊文件");
                }
                if (!paths.add(path)) throw rejected("UNSAFE_ARCHIVE", "压缩包含重复或规范化冲突路径");
                if (!zip.canReadEntryData(entry)) throw rejected("UNSUPPORTED_FORMAT", "不支持加密或该压缩算法的 ZIP");
                if (entry.getSize() < 0) throw rejected("INVALID_ARCHIVE", "压缩包条目长度无效");
                if (entry.isDirectory() && entry.getSize() != 0) throw rejected("INVALID_ARCHIVE", "目录条目含数据");
                if (entry.getSize() > settings.maxFileBytes()) throw rejected("EXPANSION_LIMIT", "单个展开文件超过配置上限");
                if (entry.getSize() > settings.maxExpandedBytes() - declaredBytes) throw rejected("EXPANSION_LIMIT", "总展开大小超过配置上限");
                declaredBytes += entry.getSize();
                entries.add(entry);
            }
            long expanded = 0;
            for (ZipArchiveEntry entry : entries) {
                if (entry.isDirectory()) continue;
                String path = sourcePath(entry.getName());
                Path temp = Files.createTempFile(storage.sampleDirectory(batch), "extract-", ".part");
                try {
                    long length = 0;
                    CRC32 crc = new CRC32();
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(temp)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (count > settings.maxFileBytes() - length || count > settings.maxExpandedBytes() - expanded) {
                                throw rejected("EXPANSION_LIMIT", "实际展开大小超过配置上限");
                            }
                            length += count; expanded += count;
                            crc.update(buffer, 0, count); output.write(buffer, 0, count);
                        }
                    }
                    if (length != entry.getSize() || crc.getValue() != entry.getCrc()) throw rejected("INVALID_ARCHIVE", "压缩包条目长度或 CRC 校验失败");
                    String lower = path.toLowerCase(Locale.ROOT);
                    boolean png = lower.endsWith(".png");
                    boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                    if (!png && !jpeg) throw new InvalidImage("UNSUPPORTED_FORMAT", "仅支持 PNG/JPEG 图片");
                    Map<String, Object> metadata = imageMetadata(temp, png ? "png" : "JPEG", length);
                    UUID sample = UUID.nameUUIDFromBytes((batch + "\n" + path).getBytes(StandardCharsets.UTF_8));
                    String checksum = ImportStorage.sha256(temp);
                    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
                    Path target = storage.sampleDirectory(batch).resolve(sample + ".bin");
                    try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException exception) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                    samples.add(new ParsedSample(sample, path, batch + "/samples/" + sample + ".bin", checksum,
                            png ? "image/png" : "image/jpeg", metadata));
                } catch (InvalidImage exception) {
                    errorCount++;
                    if (errors.size() < settings.maxErrors()) errors.add(new FileError(path, exception.code, exception.getMessage()));
                } finally { Files.deleteIfExists(temp); }
            }
        } catch (java.util.zip.ZipException exception) {
            throw rejected("INVALID_ARCHIVE", "无法读取 ZIP 压缩包");
        }
        return new ParsedArchive(samples, errors, errorCount);
    }

    private Map<String, Object> imageMetadata(Path file, String format, long length) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new InvalidImage("INVALID_IMAGE", "无法读取图片内容");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidImage("INVALID_IMAGE", "文件不是可解码图片");
            var reader = readers.next();
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) throw new InvalidImage("INVALID_IMAGE", "扩展名与图片实际格式不一致");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > settings.maxPixels()) {
                    throw new InvalidImage("IMAGE_TOO_LARGE", "图片像素超过配置上限");
                }
                var image = reader.read(0);
                if (image == null) throw new InvalidImage("INVALID_IMAGE", "图片无法解码");
                image.flush();
                return Map.of("width", width, "height", height, "sizeBytes", length);
            } finally { reader.dispose(); }
        } catch (javax.imageio.IIOException | IllegalArgumentException exception) {
            throw new InvalidImage("INVALID_IMAGE", "图片内容损坏，无法解码");
        }
    }

    static String sourcePath(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.startsWith("/")
                || value.contains("\\") || value.contains(":") || value.chars().anyMatch(c -> c < 32)) {
            throw rejected("UNSAFE_ARCHIVE", "压缩包路径不安全");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String part : normalized.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw rejected("UNSAFE_ARCHIVE", "压缩包路径不安全");
        }
        return normalized;
    }

    static Rejected rejected(String code, String message) { return new Rejected(code, message); }
    static final class Rejected extends RuntimeException {
        final String code;
        Rejected(String code, String message) { super(message); this.code = code; }
    }
    private static final class InvalidImage extends RuntimeException {
        final String code;
        InvalidImage(String code, String message) { super(message); this.code = code; }
    }
    record FileError(String sourcePath, String code, String message) { }
    record ParsedSample(UUID id, String sourcePath, String storageKey, String sha256, String mediaType, Map<String, Object> metadata) { }
    record ParsedArchive(List<ParsedSample> samples, List<FileError> errors, int errorCount) { }
}
