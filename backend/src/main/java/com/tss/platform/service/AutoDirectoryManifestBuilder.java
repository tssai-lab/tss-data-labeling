package com.tss.platform.service;

import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestAnnotation;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AutoDirectoryManifestBuilder {

    private static final int MAX_SAMPLES = 10_000;
    private static final int MAX_REFERENCES = 100_000;
    private static final int MAX_DATA_PER_SAMPLE = 100;
    private static final int MAX_ANNOTATIONS_PER_SAMPLE = 100;

    public ManifestImportPlan build(
            List<ZipEntryInfo> zipEntries,
            int generatedSampleIndexStart
    ) {
        if (zipEntries == null) {
            throw new IllegalArgumentException("zipEntries cannot be null");
        }
        if (generatedSampleIndexStart < 0) {
            throw new IllegalArgumentException(
                    "generatedSampleIndexStart must be non-negative"
            );
        }

        Map<String, List<ZipEntryInfo>> groups = new TreeMap<>();
        for (ZipEntryInfo entry : zipEntries) {
            if (entry == null) {
                throw new IllegalArgumentException("zipEntries cannot contain null");
            }
            if (entry.directory()) {
                continue;
            }
            String path = entry.normalizedPath();
            if (path == null || path.isBlank()) {
                throw ManifestValidationException.classified(ManifestFailureKind.INVALID_SAMPLE_DIRECTORY, "AUTO_DIRECTORY ZIP entry path cannot be blank", Map.of());
            }
            String[] parts = path.split("/");
            if (parts.length < 2 || parts[0].isBlank()) {
                throw validation(
                        "file must be below a top-level sample directory",
                        null,
                        path
                );
            }
            String externalId = parts[0];
            if (externalId.length() > 255) {
                throw validation(
                        "external_id length exceeds 255",
                        externalId,
                        path
                );
            }
            groups.computeIfAbsent(externalId, ignored -> new ArrayList<>())
                    .add(entry);
        }

        if (groups.isEmpty()) {
            throw ManifestValidationException.classified(ManifestFailureKind.INVALID_SAMPLE_DIRECTORY, "AUTO_DIRECTORY must contain at least one sample", Map.of());
        }
        if (groups.size() > MAX_SAMPLES) {
            throw ManifestValidationException.classified(ManifestFailureKind.INVALID_SAMPLE_DIRECTORY, "AUTO_DIRECTORY samples count exceeds 10000", Map.of());
        }
        long lastIndex = (long) generatedSampleIndexStart + groups.size() - 1;
        if (lastIndex > Integer.MAX_VALUE) {
            throw ManifestValidationException.classified(ManifestFailureKind.INVALID_SAMPLE_DIRECTORY, "AUTO_DIRECTORY generated sample_index exceeds integer range", Map.of());
        }

        List<ManifestSample> samples = new ArrayList<>(groups.size());
        int totalData = 0;
        int totalAnnotations = 0;
        int position = 0;
        for (Map.Entry<String, List<ZipEntryInfo>> group : groups.entrySet()) {
            String externalId = group.getKey();
            List<ZipEntryInfo> dataEntries = new ArrayList<>();
            List<ZipEntryInfo> annotationEntries = new ArrayList<>();
            for (ZipEntryInfo entry : group.getValue()) {
                if (isAnnotation(entry.normalizedPath(), externalId)) {
                    annotationEntries.add(entry);
                } else {
                    dataEntries.add(entry);
                }
            }
            dataEntries.sort(Comparator.comparing(ZipEntryInfo::normalizedPath));
            annotationEntries.sort(Comparator.comparing(ZipEntryInfo::normalizedPath));

            if (dataEntries.isEmpty()) {
                throw validation(
                        "sample must contain at least one data file",
                        externalId,
                        null
                );
            }
            if (dataEntries.size() > MAX_DATA_PER_SAMPLE) {
                throw validation(
                        "data count exceeds 100",
                        externalId,
                        null
                );
            }
            if (annotationEntries.size() > MAX_ANNOTATIONS_PER_SAMPLE) {
                throw validation(
                        "annotations count exceeds 100",
                        externalId,
                        null
                );
            }
            if ((long) totalData + totalAnnotations
                    + dataEntries.size() + annotationEntries.size() > MAX_REFERENCES) {
                throw validation(
                        "data and annotations references exceed 100000",
                        externalId,
                        null
                );
            }

            List<ManifestData> data = buildData(externalId, dataEntries);
            List<ManifestAnnotation> annotations = buildAnnotations(
                    externalId,
                    annotationEntries,
                    data
            );
            samples.add(new ManifestSample(
                    externalId,
                    generatedSampleIndexStart + position,
                    Map.of(),
                    Map.of(),
                    data,
                    annotations
            ));
            totalData += data.size();
            totalAnnotations += annotations.size();
            position++;
        }

        return new ManifestImportPlan(
                "1.0",
                samples,
                samples.size(),
                totalData,
                totalAnnotations,
                List.of()
        );
    }

    private static List<ManifestData> buildData(
            String externalId,
            List<ZipEntryInfo> entries
    ) {
        List<ManifestData> data = new ArrayList<>(entries.size());
        Map<String, Integer> nextSequenceByType = new LinkedHashMap<>();
        for (ZipEntryInfo entry : entries) {
            String path = entry.normalizedPath();
            String fileName = fileName(path);
            String extension = extension(fileName);
            DataTypeDescriptor descriptor = dataType(extension, externalId, path);
            int sequence = nextSequenceByType.getOrDefault(descriptor.dataType(), 0);
            nextSequenceByType.put(descriptor.dataType(), sequence + 1);
            data.add(new ManifestData(
                    path,
                    descriptor.dataType(),
                    null,
                    null,
                    sequence,
                    descriptor.format(),
                    fileName,
                    descriptor.contentType(),
                    Map.of(),
                    entry
            ));
        }
        return data;
    }

    private static List<ManifestAnnotation> buildAnnotations(
            String externalId,
            List<ZipEntryInfo> entries,
            List<ManifestData> data
    ) {
        List<ManifestAnnotation> annotations = new ArrayList<>(entries.size());
        for (ZipEntryInfo entry : entries) {
            String path = entry.normalizedPath();
            String fileName = fileName(path);
            String annotationExtension = extension(fileName);
            AnnotationDescriptor descriptor = annotationType(
                    annotationExtension,
                    externalId,
                    path
            );
            String associationName = removeLastExtension(fileName);
            List<ManifestData> matches = data.stream()
                    .filter(item -> item.fileName().equalsIgnoreCase(associationName))
                    .toList();
            if (matches.isEmpty()) {
                matches = data.stream()
                        .filter(item -> stem(item.fileName())
                                .equalsIgnoreCase(associationName))
                        .toList();
            }
            if (matches.isEmpty()) {
                throw validation(ManifestFailureKind.ANNOTATION_TARGET_NOT_FOUND,
                        "annotation data match not found",
                        externalId,
                        path
                );
            }
            if (matches.size() > 1) {
                throw validation(ManifestFailureKind.ANNOTATION_TARGET_AMBIGUOUS,
                        "annotation data match is ambiguous",
                        externalId,
                        path
                );
            }
            annotations.add(new ManifestAnnotation(
                    path,
                    "OTHER",
                    descriptor.format(),
                    matches.get(0).path(),
                    fileName,
                    descriptor.contentType(),
                    Map.of(),
                    entry
            ));
        }
        return annotations;
    }

    private static boolean isAnnotation(String path, String externalId) {
        String prefix = externalId + "/annotations/";
        return path.startsWith(prefix) && path.length() > prefix.length();
    }

    private static DataTypeDescriptor dataType(
            String extension,
            String externalId,
            String path
    ) {
        return switch (extension) {
            case "jpg", "jpeg" ->
                    new DataTypeDescriptor("IMAGE", extension, "image/jpeg");
            case "png" -> new DataTypeDescriptor("IMAGE", extension, "image/png");
            case "bmp" -> new DataTypeDescriptor("IMAGE", extension, "image/bmp");
            case "gif" -> new DataTypeDescriptor("IMAGE", extension, "image/gif");
            case "webp" -> new DataTypeDescriptor("IMAGE", extension, "image/webp");
            case "tif", "tiff" ->
                    new DataTypeDescriptor("IMAGE", extension, "image/tiff");
            case "pcd" ->
                    new DataTypeDescriptor(
                            "POINT_CLOUD",
                            extension,
                            "application/vnd.pointcloud"
                    );
            case "ply" ->
                    new DataTypeDescriptor(
                            "POINT_CLOUD",
                            extension,
                            "application/vnd.ply"
                    );
            case "mp4" -> new DataTypeDescriptor("VIDEO", extension, "video/mp4");
            case "webm" -> new DataTypeDescriptor("VIDEO", extension, "video/webm");
            case "mov" ->
                    new DataTypeDescriptor("VIDEO", extension, "video/quicktime");
            case "avi" ->
                    new DataTypeDescriptor("VIDEO", extension, "video/x-msvideo");
            case "mkv" ->
                    new DataTypeDescriptor("VIDEO", extension, "video/x-matroska");
            case "wav" -> new DataTypeDescriptor("AUDIO", extension, "audio/wav");
            case "mp3" -> new DataTypeDescriptor("AUDIO", extension, "audio/mpeg");
            case "flac" -> new DataTypeDescriptor("AUDIO", extension, "audio/flac");
            case "txt" -> new DataTypeDescriptor("TEXT", extension, "text/plain");
            case "md" -> new DataTypeDescriptor("TEXT", extension, "text/markdown");
            case "csv" -> new DataTypeDescriptor("TEXT", extension, "text/csv");
            case "jsonl" ->
                    new DataTypeDescriptor(
                            "TEXT",
                            extension,
                            "application/x-ndjson"
                    );
            case "log" -> new DataTypeDescriptor("OTHER", extension, "text/plain");
            case "bin", "npy", "npz", "pkl" ->
                    new DataTypeDescriptor(
                            "OTHER",
                            extension,
                            "application/octet-stream"
                    );
            default -> throw validation(ManifestFailureKind.UNSUPPORTED_SAMPLE_FILE,
                    "unsupported data extension: " + extension,
                    externalId,
                    path
            );
        };
    }

    private static AnnotationDescriptor annotationType(
            String extension,
            String externalId,
            String path
    ) {
        return switch (extension) {
            case "json" ->
                    new AnnotationDescriptor("JSON", "application/json");
            case "txt" -> new AnnotationDescriptor("TXT", "text/plain");
            case "xml" -> new AnnotationDescriptor("XML", "application/xml");
            case "csv" -> new AnnotationDescriptor("CSV", "text/csv");
            case "yaml", "yml" ->
                    new AnnotationDescriptor("YAML", "application/yaml");
            default -> throw validation(ManifestFailureKind.UNSUPPORTED_SAMPLE_FILE,
                    "unsupported annotation extension: " + extension,
                    externalId,
                    path
            );
        };
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String removeLastExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String stem(String fileName) {
        int dot = fileName.indexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static ManifestValidationException validation(
            String reason,
            String externalId,
            String path
    ) {
        return validation(ManifestFailureKind.INVALID_SAMPLE_DIRECTORY, reason, externalId, path);
    }

    private static ManifestValidationException validation(ManifestFailureKind kind,
            String reason, String externalId, String path) {
        StringBuilder message = new StringBuilder("AUTO_DIRECTORY ").append(reason);
        if (externalId != null) {
            message.append(", external_id: ").append(externalId);
        }
        if (path != null) {
            message.append(", path: ").append(path);
        }
        return ManifestValidationException.classified(kind, message.toString(), Map.of());
    }

    private record DataTypeDescriptor(
            String dataType,
            String format,
            String contentType
    ) {
    }

    private record AnnotationDescriptor(String format, String contentType) {
    }
}
