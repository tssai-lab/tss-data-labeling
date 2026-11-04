package com.tss.platform.service;

import com.tss.platform.dto.PointCloudPreviewDto;
import com.tss.platform.dto.PointCloudPreviewFileDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class PointCloudPreviewService {

    private static final long DEFAULT_MAX_PREVIEW_SIZE = 200L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final Set<String> POINT_CLOUD_EXTENSIONS = Set.of(".ply", ".pcd");

    private final DatasetVersionRepository datasetVersionRepo;
    private final DatasetAssetRepository datasetAssetRepo;
    private final MinioService minioService;
    private final AuthContext authContext;
    private final long maxPreviewSize;

    public PointCloudPreviewService(
            DatasetVersionRepository datasetVersionRepo,
            DatasetAssetRepository datasetAssetRepo,
            MinioService minioService,
            AuthContext authContext,
            @Value("${point-cloud.preview.max-size:209715200}") long maxPreviewSize
    ) {
        this.datasetVersionRepo = datasetVersionRepo;
        this.datasetAssetRepo = datasetAssetRepo;
        this.minioService = minioService;
        this.authContext = authContext;
        this.maxPreviewSize = maxPreviewSize > 0 ? maxPreviewSize : DEFAULT_MAX_PREVIEW_SIZE;
    }

    public PointCloudPreviewDto preview(String datasetVersionId) {
        PointCloudDataset dataset = getPointCloudDataset(datasetVersionId);
        DatasetVersion version = dataset.version();
        String sourceName = sourceName(version);
        String ext = extensionOf(sourceName);

        PointCloudPreviewDto dto = basePreview(version, sourceName);
        if (".ply".equals(ext) || ".pcd".equals(ext)) {
            dto.setFormat(formatOf(ext));
            Long sizeBytes = resolveObjectSize(version);
            dto.setSizeBytes(sizeBytes);
            if (isPreviewAllowed(sizeBytes)) {
                dto.setPreviewSupported(true);
                dto.setPreviewUrl(singleFilePreviewUrl(version.getId()));
            } else {
                dto.setPreviewSupported(false);
                dto.setMessage(tooLargeMessage());
            }
            return dto;
        }

        if (".zip".equals(ext)) {
            dto.setFormat("ZIP");
            fillZipPreview(dto, version);
            return dto;
        }

        dto.setPreviewSupported(false);
        dto.setMessage("点云在线预览仅支持 .ply、.pcd 或包含点云文件的 .zip 数据集");
        return dto;
    }

    public PointCloudFileStream openPointCloudFile(String datasetVersionId) {
        PointCloudDataset dataset = getPointCloudDataset(datasetVersionId);
        DatasetVersion version = dataset.version();
        String sourceName = sourceName(version);
        String ext = extensionOf(sourceName);
        if (!POINT_CLOUD_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持直接预览 .ply 或 .pcd 点云文件");
        }

        Long sizeBytes = resolveObjectSize(version);
        if (!isPreviewAllowed(sizeBytes)) {
            throw new IllegalArgumentException(tooLargeMessage());
        }

        try {
            return new PointCloudFileStream(
                    minioService.downloadStream(version.getStoragePath()),
                    fileNameOf(sourceName),
                    formatOf(ext),
                    sizeBytes
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("读取点云文件失败: " + rootMessage(e));
        }
    }

    public PointCloudFileStream openZipPointCloudFile(String datasetVersionId, String zipEntryPath) {
        PointCloudDataset dataset = getPointCloudDataset(datasetVersionId);
        DatasetVersion version = dataset.version();
        String sourceName = sourceName(version);
        if (!".zip".equals(extensionOf(sourceName))) {
            throw new IllegalArgumentException("仅支持从 POINT_CLOUD zip 数据集读取内部点云文件");
        }

        String targetPath = normalizeZipEntryPath(zipEntryPath);
        String targetExt = extensionOf(targetPath);
        if (!POINT_CLOUD_EXTENSIONS.contains(targetExt)) {
            throw new IllegalArgumentException("zip 内点云预览仅支持 .ply 或 .pcd 文件");
        }

        try (InputStream is = minioService.downloadStream(version.getStoragePath());
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(is), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                entries += 1;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("点云 zip 文件条目过多");
                }
                String entryPath = normalizeZipEntryPath(entry.getName());
                if (!entry.isDirectory() && targetPath.equals(entryPath)) {
                    return extractEntryToTempStream(zip, entry, entryPath);
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取点云 zip 文件失败: " + rootMessage(e));
        }

        throw new IllegalArgumentException("zip 内点云文件不存在: " + targetPath);
    }

    private void fillZipPreview(PointCloudPreviewDto dto, DatasetVersion version) {
        List<PointCloudPreviewFileDto> files = new ArrayList<>();
        try (InputStream is = minioService.downloadStream(version.getStoragePath());
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(is), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                entries += 1;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("点云 zip 文件条目过多");
                }
                String entryPath = normalizeZipEntryPath(entry.getName());
                if (!entry.isDirectory() && POINT_CLOUD_EXTENSIONS.contains(extensionOf(entryPath))) {
                    files.add(toPreviewFile(version.getId(), entryPath, readEntrySize(zip)));
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取点云 zip 目录失败: " + rootMessage(e));
        }

        files.sort(Comparator.comparing(PointCloudPreviewFileDto::getPath));
        dto.setPointCloudFiles(files);
        if (files.isEmpty()) {
            dto.setPreviewSupported(false);
            dto.setMessage("zip 内未找到 .ply 或 .pcd 点云文件");
            return;
        }

        boolean hasAllowedFile = files.stream().anyMatch(PointCloudPreviewFileDto::isPreviewAllowed);
        dto.setPreviewSupported(hasAllowedFile);
        dto.setMessage(hasAllowedFile ? "请选择 zip 内的点云文件进行预览" : tooLargeMessage());
    }

    private PointCloudPreviewFileDto toPreviewFile(String datasetVersionId, String entryPath, long sizeBytes) {
        String ext = extensionOf(entryPath);
        boolean previewAllowed = isPreviewAllowed(sizeBytes);

        PointCloudPreviewFileDto dto = new PointCloudPreviewFileDto();
        dto.setPath(entryPath);
        dto.setFileName(fileNameOf(entryPath));
        dto.setFormat(formatOf(ext));
        dto.setSizeBytes(sizeBytes);
        dto.setPreviewAllowed(previewAllowed);
        dto.setPreviewUrl(previewAllowed ? zipFilePreviewUrl(datasetVersionId, entryPath) : null);
        dto.setMessage(previewAllowed ? null : tooLargeMessage());
        return dto;
    }

    private long readEntrySize(ZipInputStream zip) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        int len;
        while ((len = zip.read(buffer)) != -1) {
            total += len;
            if (total > maxPreviewSize) {
                throw new IllegalArgumentException(tooLargeMessage());
            }
        }
        return total;
    }

    private PointCloudFileStream extractEntryToTempStream(ZipInputStream zip, ZipEntry entry, String entryPath)
            throws IOException {
        Long declaredSize = entry.getSize() >= 0 ? entry.getSize() : null;
        if (!isPreviewAllowed(declaredSize)) {
            throw new IllegalArgumentException(tooLargeMessage());
        }

        Path tempFile = Files.createTempFile("point-cloud-preview-", extensionOf(entryPath));
        tempFile.toFile().deleteOnExit();
        boolean complete = false;
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
            int len;
            while ((len = zip.read(buffer)) != -1) {
                total += len;
                if (total > maxPreviewSize) {
                    throw new IllegalArgumentException(tooLargeMessage());
                }
                out.write(buffer, 0, len);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(tempFile);
            }
        }

        InputStream stream = Files.newInputStream(
                tempFile,
                StandardOpenOption.READ,
                StandardOpenOption.DELETE_ON_CLOSE
        );
        return new PointCloudFileStream(stream, fileNameOf(entryPath), formatOf(extensionOf(entryPath)), total);
    }

    private PointCloudDataset getPointCloudDataset(String datasetVersionId) {
        if (datasetVersionId == null || datasetVersionId.isBlank()) {
            throw new IllegalArgumentException("datasetVersionId 不能为空");
        }
        DatasetVersion version = datasetVersionRepo.findByIdAndDeletedFalse(datasetVersionId.trim())
                .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在或无权限: " + datasetVersionId));
        DatasetAsset asset = datasetAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("数据集资产不存在或已删除: " + version.getAssetId()));

        Integer ownerUserId = version.getOwnerUserId() != null ? version.getOwnerUserId() : asset.getOwnerUserId();
        authContext.requireOwnerAccess(ownerUserId, "dataset version not found or no permission");
        requirePreviewableStatus(version);

        if (!"POINT_CLOUD".equalsIgnoreCase(asset.getType())) {
            throw new IllegalArgumentException("点云预览仅支持 POINT_CLOUD 数据集");
        }
        requireStoragePath(version);
        authContext.requireObjectAccess(version.getStoragePath(), ownerUserId, "dataset object not found or no permission");
        return new PointCloudDataset(version, asset);
    }

    private void requirePreviewableStatus(DatasetVersion version) {
        String status = version.getStatus();
        if ("DRAFT".equals(status) || "ARCHIVED".equals(status)) {
            throw new IllegalArgumentException("dataset version status must be READY or DEPRECATED for preview");
        }
    }

    private PointCloudPreviewDto basePreview(DatasetVersion version, String sourceName) {
        PointCloudPreviewDto dto = new PointCloudPreviewDto();
        dto.setDatasetVersionId(version.getId());
        dto.setFileName(fileNameOf(sourceName));
        dto.setType("POINT_CLOUD");
        dto.setSizeBytes(version.getSizeBytes());
        dto.setPreviewSupported(false);
        dto.setPointCloudFiles(null);
        return dto;
    }

    private Long resolveObjectSize(DatasetVersion version) {
        if (version.getSizeBytes() != null) {
            return version.getSizeBytes();
        }
        try {
            return minioService.stat(version.getStoragePath()).size();
        } catch (Exception e) {
            return null;
        }
    }

    private void requireStoragePath(DatasetVersion version) {
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("数据集版本缺少存储路径");
        }
    }

    private boolean isPreviewAllowed(Long sizeBytes) {
        return sizeBytes == null || sizeBytes <= maxPreviewSize;
    }

    private String tooLargeMessage() {
        return "文件过大，请下载后本地查看";
    }

    private String singleFilePreviewUrl(String datasetVersionId) {
        return "/api/dataset/point-cloud/file?id=" + queryEncode(datasetVersionId);
    }

    private String zipFilePreviewUrl(String datasetVersionId, String entryPath) {
        return "/api/dataset/point-cloud/zip-file?id="
                + queryEncode(datasetVersionId)
                + "&path="
                + queryEncode(entryPath);
    }

    private String normalizeZipEntryPath(String path) {
        try {
            return ZipPathValidator.normalizeEntryPath(path);
        } catch (IllegalArgumentException exception) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("zip entry path 不能为空", exception);
            }
            throw new IllegalArgumentException("zip entry path 非法: " + path, exception);
        }
    }

    private String sourceName(DatasetVersion version) {
        if (version.getFileName() != null && !version.getFileName().isBlank()) {
            return version.getFileName();
        }
        return version.getStoragePath();
    }

    private String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String extensionOf(String path) {
        String fileName = fileNameOf(path);
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    private String formatOf(String extension) {
        return ".pcd".equals(extension) ? "PCD" : "PLY";
    }

    private String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    public record PointCloudFileStream(
            InputStream inputStream,
            String fileName,
            String format,
            Long sizeBytes
    ) {
    }

    private record PointCloudDataset(DatasetVersion version, DatasetAsset asset) {
    }
}
