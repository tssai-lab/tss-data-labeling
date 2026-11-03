package com.tss.platform.service;

import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SingleModalImportPlanBuilder {

    private static final int EXTERNAL_ID_MAX_LENGTH = 255;

    public ManifestImportPlan build(
            String taskType,
            List<ZipEntryInfo> zipEntries,
            int generatedSampleIndexStart
    ) {
        String normalizedTaskType = DatasetTaskType.normalize(taskType);
        if ("MULTIMODAL".equals(normalizedTaskType)) {
            throw new IllegalArgumentException(
                    "single-modal import plan does not support MULTIMODAL"
            );
        }
        if (zipEntries == null) {
            throw new IllegalArgumentException("zipEntries cannot be null");
        }
        if (generatedSampleIndexStart < 0) {
            throw new IllegalArgumentException(
                    "generatedSampleIndexStart must be non-negative"
            );
        }

        List<ZipEntryInfo> fileEntries = zipEntries.stream()
                .filter(entry -> {
                    if (entry == null) {
                        throw new IllegalArgumentException(
                                "zipEntries cannot contain null"
                        );
                    }
                    return !entry.directory();
                })
                .sorted(Comparator.comparing(ZipEntryInfo::normalizedPath))
                .toList();
        if (fileEntries.isEmpty()) {
            throw ManifestValidationException.classified(
                    ManifestFailureKind.INVALID_MANIFEST,
                    "single-modal ZIP must contain at least one file", Map.of()
            );
        }

        List<ManifestSample> samples = new ArrayList<>(fileEntries.size());
        for (int index = 0; index < fileEntries.size(); index += 1) {
            ZipEntryInfo entry = fileEntries.get(index);
            String path = requireEntryPath(entry);
            int sampleIndex = generatedSampleIndexStart + index;
            FileDescriptor descriptor = describeFile(normalizedTaskType, path);
            ManifestData data = new ManifestData(
                    path,
                    descriptor.dataType(),
                    null,
                    null,
                    0,
                    descriptor.format(),
                    fileName(path),
                    descriptor.contentType(),
                    Map.of(),
                    entry
            );
            samples.add(new ManifestSample(
                    externalId(path, sampleIndex),
                    sampleIndex,
                    Map.of(),
                    Map.of(),
                    List.of(data),
                    List.of()
            ));
        }

        return new ManifestImportPlan(
                "1.0",
                samples,
                samples.size(),
                samples.size(),
                0,
                List.of()
        );
    }

    private static String requireEntryPath(ZipEntryInfo entry) {
        String path = entry.normalizedPath();
        if (path == null || path.isBlank()) {
            throw ManifestValidationException.classified(
                    ManifestFailureKind.INVALID_MANIFEST,
                    "single-modal ZIP entry path cannot be blank", Map.of()
            );
        }
        return path;
    }

    private static String externalId(String path, int sampleIndex) {
        return path.length() <= EXTERNAL_ID_MAX_LENGTH
                ? path
                : "entry-" + sampleIndex;
    }

    private static FileDescriptor describeFile(String taskType, String path) {
        String extension = extension(path);
        return switch (taskType) {
            case "CV" -> describeCvFile(extension, path);
            case "NLP" -> describeTextLikeFile(extension, path);
            case "POINT_CLOUD" -> describePointCloudFile(extension, path);
            case "ROBOT" -> describeRobotFile(extension, path);
            case "LEROBOT" -> describeLeRobotFile(extension, path);
            case "OTHER" -> describeOtherFile(extension, path);
            default -> throw new IllegalArgumentException(
                    "single-modal import plan does not support task type: " + taskType
            );
        };
    }

    private static FileDescriptor describeCvFile(String extension, String path) {
        return switch (extension) {
            case "jpg", "jpeg" ->
                    new FileDescriptor("IMAGE", extension, "image/jpeg");
            case "png" -> new FileDescriptor("IMAGE", extension, "image/png");
            case "bmp" -> new FileDescriptor("IMAGE", extension, "image/bmp");
            case "gif" -> new FileDescriptor("IMAGE", extension, "image/gif");
            case "webp" -> new FileDescriptor("IMAGE", extension, "image/webp");
            case "tif", "tiff" ->
                    new FileDescriptor("IMAGE", extension, "image/tiff");
            case "txt", "csv", "json", "xml", "yaml", "yml" ->
                    describeTextLikeFile(extension, path);
            default -> unsupported(extension, path);
        };
    }

    private static FileDescriptor describePointCloudFile(
            String extension,
            String path
    ) {
        return switch (extension) {
            case "pcd" -> new FileDescriptor(
                    "POINT_CLOUD",
                    extension,
                    "application/vnd.pointcloud"
            );
            case "ply" -> new FileDescriptor(
                    "POINT_CLOUD",
                    extension,
                    "application/vnd.ply"
            );
            case "txt", "json", "yaml", "yml" -> describeTextLikeFile(extension, path);
            default -> unsupported(extension, path);
        };
    }

    private static FileDescriptor describeRobotFile(String extension, String path) {
        return switch (extension) {
            case "xml", "yaml", "yml", "json", "txt" ->
                    describeTextLikeFile(extension, path);
            default -> unsupported(extension, path);
        };
    }

    private static FileDescriptor describeLeRobotFile(String extension, String path) {
        return switch (extension) {
            case "mp4" -> new FileDescriptor("VIDEO", extension, "video/mp4");
            case "mkv" -> new FileDescriptor("VIDEO", extension, "video/x-matroska");
            case "parquet" -> new FileDescriptor(
                    "OTHER",
                    extension,
                    "application/vnd.apache.parquet"
            );
            case "json", "jsonl", "txt", "md" -> describeTextLikeFile(extension, path);
            case "", "gitattributes" -> {
                if (".gitattributes".equals(fileName(path).toLowerCase(Locale.ROOT))) {
                    yield new FileDescriptor("OTHER", "gitattributes", "text/plain");
                }
                yield unsupported(extension, path);
            }
            default -> unsupported(extension, path);
        };
    }

    private static FileDescriptor describeOtherFile(String extension, String path) {
        return switch (extension) {
            case "jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff" ->
                    describeCvFile(extension, path);
            case "pcd", "ply" -> describePointCloudFile(extension, path);
            case "mp4", "mkv", "parquet" -> describeLeRobotFile(extension, path);
            default -> describeTextLikeFile(extension, path);
        };
    }

    private static FileDescriptor describeTextLikeFile(
            String extension,
            String path
    ) {
        return switch (extension) {
            case "txt" -> new FileDescriptor("TEXT", extension, "text/plain");
            case "json" -> new FileDescriptor("TEXT", extension, "application/json");
            case "jsonl" ->
                    new FileDescriptor("TEXT", extension, "application/x-ndjson");
            case "csv" -> new FileDescriptor("TEXT", extension, "text/csv");
            case "xlsx" ->
                    new FileDescriptor(
                            "OTHER",
                            extension,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    );
            case "xls" -> new FileDescriptor("OTHER", extension, "application/vnd.ms-excel");
            case "pdf" -> new FileDescriptor("OTHER", extension, "application/pdf");
            case "docx" ->
                    new FileDescriptor(
                            "OTHER",
                            extension,
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    );
            case "xml" -> new FileDescriptor("TEXT", extension, "application/xml");
            case "yaml", "yml" ->
                    new FileDescriptor("TEXT", extension, "application/yaml");
            case "md" -> new FileDescriptor("TEXT", extension, "text/markdown");
            default -> unsupported(extension, path);
        };
    }

    private static FileDescriptor unsupported(String extension, String path) {
        throw ManifestValidationException.classified(
                ManifestFailureKind.UNSUPPORTED_SAMPLE_FILE,
                "single-modal ZIP contains unsupported file extension: "
                        + extension + ", path: " + path, Map.of()
        );
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String path) {
        String fileName = fileName(path);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record FileDescriptor(
            String dataType,
            String format,
            String contentType
    ) {
    }
}
