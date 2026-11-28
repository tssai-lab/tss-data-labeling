package com.tss.labeling.imports;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Keeps source bytes intact and produces a same-size PNG for each supported image/page. */
final class ImageArchiveReader {
    record Result(List<ArchiveParser.ParsedSample> samples, long extraBytes) { }
    static final class InvalidImage extends IOException {
        final String code;
        InvalidImage(String code, String message) { super(message); this.code = code; }
    }

    static Result read(Path original, String path, UUID batch, ImageFormats format,
                       ImportSettings settings, ImportStorage storage, int remainingEntries,
                       long remainingBytes) throws IOException {
        var samples = new ArrayList<ArchiveParser.ParsedSample>();
        var created = new ArrayList<Path>();
        long length = Files.size(original), storedBytes = 0;
        boolean committed = false;
        try (var input = ImageIO.createImageInputStream(original.toFile())) {
            if (input == null) throw invalid("无法读取图片内容");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("文件不是可解码图片");
            var reader = readers.next();
            try {
                if (!format.readerName().equalsIgnoreCase(reader.getFormatName()))
                    throw invalid("扩展名与图片实际格式不一致");
                reader.setInput(input, false, true);
                if (format != ImageFormats.TIFF && reader.getNumImages(true) != 1)
                    throw new InvalidImage("ANIMATED_IMAGE_NOT_SUPPORTED", "当前图片标注仅支持静态 WebP/BMP，请逐帧导入动画");
                for (int page = 0; ; page++) {
                    int width, height;
                    try { width = reader.getWidth(page); height = reader.getHeight(page); }
                    catch (IndexOutOfBoundsException end) {
                        if (page == 0) throw invalid("图片没有可读取页面");
                        break;
                    }
                    if (samples.size() >= remainingEntries)
                        throw ArchiveParser.rejected("EXPANSION_LIMIT", "图片页面数量超过导入配置上限");
                    if (width <= 0 || height <= 0 || (long) width * height > settings.maxPixels())
                        throw new InvalidImage("IMAGE_TOO_LARGE", "图片页面像素超过配置上限");
                    long budget = remainingBytes + length - storedBytes;
                    if (length > budget) throw ArchiveParser.rejected("EXPANSION_LIMIT", "原图和预览总大小超过导入配置上限");
                    String source = page == 0 ? path : path + "#page-" + (page + 1);
                    UUID id = UUID.nameUUIDFromBytes((batch + "\n" + source).getBytes(StandardCharsets.UTF_8));
                    Path target = storage.sampleDirectory(batch).resolve(id + ".bin");
                    Path preview = storage.sampleDirectory(batch).resolve(id + ".preview.png");
                    created.add(target); created.add(preview);
                    Files.copy(original, target, StandardCopyOption.REPLACE_EXISTING);
                    BufferedImage decoded = reader.read(page);
                    if (decoded == null) throw invalid("图片页面无法解码");
                    var rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    try {
                        var graphics = rendered.createGraphics();
                        try { graphics.drawImage(decoded, 0, 0, null); }
                        finally { graphics.dispose(); }
                        try (var output = new BoundedOutput(Files.newOutputStream(preview),
                                Math.min(settings.maxFileBytes(), budget - length))) {
                            if (!ImageIO.write(rendered, "png", output)) throw invalid("PNG 预览生成失败");
                        }
                    } finally { decoded.flush(); rendered.flush(); }
                    try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
                    try (var channel = java.nio.channels.FileChannel.open(preview, StandardOpenOption.WRITE)) { channel.force(true); }
                    long previewBytes = Files.size(preview);
                    storedBytes += length + previewBytes;
                    var metadata = new LinkedHashMap<String,Object>();
                    metadata.put("width", width); metadata.put("height", height); metadata.put("sizeBytes", length);
                    metadata.put("originalSourcePath", path); metadata.put("originalMediaType", format.mediaType());
                    metadata.put("pageIndex", page); metadata.put("pageNumber", page + 1);
                    metadata.put("previewMediaType", "image/png"); metadata.put("previewSizeBytes", previewBytes);
                    metadata.put("previewSha256", ImportStorage.sha256(preview));
                    samples.add(new ArchiveParser.ParsedSample(id, source, batch + "/samples/" + id + ".bin",
                            ImportStorage.sha256(target), format.mediaType(), metadata));
                    if (format != ImageFormats.TIFF) break;
                }
            } finally { reader.dispose(); }
            for (var sample : samples) sample.metadata().put("pageCount", samples.size());
            committed = true;
            return new Result(List.copyOf(samples), storedBytes - length);
        } catch (InvalidImage exception) { throw exception; }
        catch (IOException | IllegalArgumentException exception) { throw invalid("图片内容损坏，无法完整解码"); }
        finally { if (!committed) for (Path file : created) Files.deleteIfExists(file); }
    }

    private static InvalidImage invalid(String message) { return new InvalidImage("INVALID_IMAGE", message); }

    private static final class BoundedOutput extends FilterOutputStream {
        private final long limit;
        private long count;
        BoundedOutput(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length); out.write(bytes, offset, length);
        }
        private void reserve(int length) {
            if (length > limit - count)
                throw ArchiveParser.rejected("EXPANSION_LIMIT", "图片预览大小超过导入配置上限");
            count += length;
        }
    }
}
