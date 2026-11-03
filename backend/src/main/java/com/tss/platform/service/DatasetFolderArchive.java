package com.tss.platform.service;

import com.tss.platform.model.CvAnnotationFormat;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static com.tss.platform.service.DatasetUploadPolicy.normalizeText;
import static com.tss.platform.service.DatasetZipValidator.extensionOf;

/** CV 目录打包。保留路径规范化、重复路径拒绝和标注格式白名单；临时文件生命周期仍由上传编排管理。 */
final class DatasetFolderArchive {
    private DatasetFolderArchive() {}
    private static final Set<String> CV_IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff"
    );

    static int writeCvFolderZip(
            Path tempZip,
            List<MultipartFile> files,
            List<String> paths,
            String annotationFormat
    ) throws Exception {
        int imageCount = 0;
        Set<String> entryNames = new LinkedHashSet<>();
        try (OutputStream os = Files.newOutputStream(tempZip);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            for (int i = 0; i < files.size(); i += 1) {
                MultipartFile file = files.get(i);
                if (file == null || file.isEmpty()) {
                    throw new IllegalArgumentException("图片文件不能为空");
                }
                String entryName = sanitizeZipEntryPath(paths.get(i), file.getOriginalFilename());
                String ext = extensionOf(entryName);
                if (!CvAnnotationFormat.isAllowedFile(annotationFormat, ext)
                        && !DatasetZipValidator.isCvDatasetManifest(annotationFormat, entryName)) {
                    throw new IllegalArgumentException(
                            "CV folder upload does not allow file for annotationFormat "
                                    + annotationFormat + ": " + entryName
                    );
                }
                if (!entryNames.add(entryName)) {
                    throw new IllegalArgumentException("图片文件夹中存在重复路径: " + entryName);
                }

                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(System.currentTimeMillis());
                zip.putNextEntry(entry);
                try (InputStream input = file.getInputStream()) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
                if (CV_IMAGE_EXTENSIONS.contains(ext)) {
                    imageCount += 1;
                }
            }
        }
        return imageCount;
    }

    static String sanitizeZipEntryPath(String rawPath, String fallbackName) {
        String path = normalizeText(rawPath);
        if (path == null) {
            path = normalizeText(fallbackName);
        }
        if (path == null) {
            throw new IllegalArgumentException("图片文件路径不能为空");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("图片文件路径非法: " + path);
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part == null || part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part) || part.contains("\u0000")) {
                throw new IllegalArgumentException("图片文件路径非法: " + path);
            }
            parts.add(sanitizeZipSegment(part));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("图片文件路径不能为空");
        }
        return String.join("/", parts);
    }

    static String sanitizeZipSegment(String value) {
        String segment = value.trim().replaceAll("[\\\\:*?\"<>|]", "_");
        return segment.isEmpty() ? "unnamed" : segment;
    }
}
