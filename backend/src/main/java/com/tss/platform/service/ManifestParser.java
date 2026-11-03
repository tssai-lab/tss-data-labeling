package com.tss.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestAnnotation;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ManifestParser {

    private static final int MAX_SAMPLES = 10_000;
    private static final int MAX_REFERENCES = 100_000;
    private static final int MAX_DATA_PER_SAMPLE = 100;
    private static final int MAX_ANNOTATIONS_PER_SAMPLE = 100;
    private static final Set<String> DATA_TYPES = Set.of(
            "IMAGE", "TEXT", "POINT_CLOUD", "AUDIO", "VIDEO", "OTHER"
    );
    private static final Set<String> VIDEO_FORMATS = Set.of("mp4", "webm", "mov", "avi", "mkv");
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public ManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ManifestImportPlan parse(
            String manifestJson,
            List<ZipEntryInfo> zipEntries,
            String manifestPath
    ) {
        return parse(manifestJson, zipEntries, manifestPath, 0);
    }

    public ManifestImportPlan parse(
            String manifestJson,
            List<ZipEntryInfo> zipEntries,
            String manifestPath,
            int generatedSampleIndexStart
    ) {
        return parse(manifestJson, zipEntries, manifestPath, generatedSampleIndexStart, false);
    }

    public ManifestImportPlan parse(
            String manifestJson,
            List<ZipEntryInfo> zipEntries,
            String manifestPath,
            int generatedSampleIndexStart,
            boolean strictManifest
    ) {
        if (generatedSampleIndexStart < 0) {
            throw new IllegalArgumentException("generatedSampleIndexStart must be non-negative");
        }
        JsonNode root = parseRoot(manifestJson);
        String version = requiredText(root, "version", null, null);
        if (!"1.0".equals(version)) {
            throw error("version", null, null, "must equal 1.0");
        }

        JsonNode samplesNode = root.get("samples");
        if (samplesNode == null || !samplesNode.isArray() || samplesNode.isEmpty()) {
            throw error("samples", null, null, "must be a non-empty array");
        }
        if (samplesNode.size() > MAX_SAMPLES) {
            throw error("samples", null, null, "samples count exceeds 10000");
        }
        ReferenceCounts referenceCounts = validateReferenceCounts(samplesNode);

        Map<String, ZipEntryInfo> zipEntryMap = buildZipEntryMap(zipEntries);
        Set<String> externalIds = new LinkedHashSet<>();
        Set<Integer> sampleIndexes = new LinkedHashSet<>();
        Set<String> declaredPaths = new LinkedHashSet<>();
        List<ManifestSample> samples = new ArrayList<>(samplesNode.size());
        int totalDataCount = referenceCounts.dataCount();
        int totalAnnotationCount = referenceCounts.annotationCount();

        for (int position = 0; position < samplesNode.size(); position++) {
            JsonNode sampleNode = samplesNode.get(position);
            String sampleField = "samples[" + position + "]";
            if (!sampleNode.isObject()) {
                throw error(sampleField, null, null, "must be a JSON object");
            }
            String externalId = requiredText(
                    sampleNode,
                    "external_id",
                    sampleField + ".external_id",
                    null,
                    null
            );
            validateLength(
                    sampleField + ".external_id",
                    externalId,
                    255,
                    externalId,
                    null
            );
            if (!externalIds.add(externalId)) {
                throw error(ManifestFailureKind.DUPLICATE_SAMPLE,
                        sampleField + ".external_id",
                        externalId,
                        null,
                        "duplicate external_id: " + externalId
                );
            }

            int sampleIndex = optionalNonNegativeInt(
                    sampleNode.get("sample_index"),
                    generatedSampleIndexStart + position,
                    sampleField + ".sample_index",
                    externalId,
                    null
            );
            if (!sampleIndexes.add(sampleIndex)) {
                throw error(ManifestFailureKind.DUPLICATE_SAMPLE,
                        sampleField + ".sample_index",
                        externalId,
                        null,
                        "duplicate sample_index: " + sampleIndex,
                        Map.of("sampleIndex", sampleIndex)
                );
            }

            Map<String, Object> tags = objectMap(
                    sampleNode.get("tags"),
                    sampleField + ".tags",
                    externalId,
                    null
            );
            Map<String, Object> metadata = objectMap(
                    sampleNode.get("metadata"),
                    sampleField + ".metadata",
                    externalId,
                    null
            );

            JsonNode dataNode = optionalArray(
                    sampleNode.get("data"),
                    sampleField + ".data",
                    externalId
            );
            JsonNode annotationsNode = optionalArray(
                    sampleNode.get("annotations"),
                    sampleField + ".annotations",
                    externalId
            );
            List<ManifestData> data = parseData(
                    dataNode,
                    sampleField,
                    externalId,
                    zipEntryMap,
                    declaredPaths
            );
            Map<String, ManifestData> currentSampleData = new LinkedHashMap<>();
            for (ManifestData item : data) {
                currentSampleData.put(item.path(), item);
            }
            List<ManifestAnnotation> annotations = parseAnnotations(
                    annotationsNode,
                    sampleField,
                    externalId,
                    zipEntryMap,
                    declaredPaths,
                    currentSampleData
            );
            samples.add(new ManifestSample(
                    externalId,
                    sampleIndex,
                    tags,
                    metadata,
                    data,
                    annotations
            ));
        }

        String normalizedManifestPath = normalizeManifestPath(manifestPath);
        List<String> undeclaredEntries = undeclaredZipEntries(
                zipEntries,
                declaredPaths,
                normalizedManifestPath
        );
        if (strictManifest && !undeclaredEntries.isEmpty()) {
            throw new ManifestValidationException(
                    "INVALID_MANIFEST_UNDECLARED_ENTRY",
                    "manifest 未声明 ZIP 文件: " + undeclaredEntries.get(0),
                    Map.of(
                            "field", "zipEntries",
                            "path", undeclaredEntries.get(0),
                            "reason", "entry is not declared by manifest",
                            "undeclaredEntries", undeclaredEntries
                    )
            );
        }
        List<String> warnings = undeclaredEntries.stream()
                .map(path -> "undeclared zip entry: " + path)
                .toList();
        return new ManifestImportPlan(
                version,
                samples,
                samples.size(),
                totalDataCount,
                totalAnnotationCount,
                warnings
        );
    }

    private List<ManifestData> parseData(
            JsonNode dataNode,
            String sampleField,
            String externalId,
            Map<String, ZipEntryInfo> zipEntryMap,
            Set<String> declaredPaths
    ) {
        List<ManifestData> data = new ArrayList<>(dataNode.size());
        Set<String> dataKeys = new LinkedHashSet<>();
        for (int index = 0; index < dataNode.size(); index++) {
            JsonNode item = dataNode.get(index);
            String dataField = sampleField + ".data[" + index + "]";
            if (!item.isObject()) {
                throw error(dataField, externalId, null, "must be a JSON object");
            }
            String path = validatedDeclaredPath(
                    item,
                    false,
                    "path",
                    dataField + ".path",
                    externalId,
                    zipEntryMap,
                    declaredPaths
            );
            String dataType = requiredText(
                    item,
                    "data_type",
                    dataField + ".data_type",
                    externalId,
                    path
            );
            if (!DATA_TYPES.contains(dataType)) {
                throw error(ManifestFailureKind.UNSUPPORTED_SAMPLE_FILE,
                        dataField + ".data_type",
                        externalId,
                        path,
                        "unsupported value: " + dataType
                );
            }
            String sensor = optionalText(
                    item, "sensor", dataField + ".sensor", externalId, path
            );
            String channel = optionalText(
                    item, "channel", dataField + ".channel", externalId, path
            );
            String format = optionalText(
                    item, "format", dataField + ".format", externalId, path
            );
            validateLength(dataField + ".sensor", sensor, 64, externalId, path);
            validateLength(dataField + ".channel", channel, 32, externalId, path);
            validateLength(dataField + ".format", format, 32, externalId, path);

            int seq = optionalNonNegativeInt(
                    item.get("seq"),
                    0,
                    dataField + ".seq",
                    externalId,
                    path
            );
            String key = dataType + "\u0000" + nullToEmpty(sensor) + "\u0000"
                    + nullToEmpty(channel) + "\u0000" + seq;
            if (!dataKeys.add(key)) {
                throw error(
                        dataField,
                        externalId,
                        path,
                        "duplicate data_type + sensor + channel + seq"
                );
            }

            String fileName = fileName(path);
            validateLength(dataField + ".path", fileName, 255, externalId, path);
            String extension = extension(fileName);
            if ("VIDEO".equals(dataType)) {
                validateVideo(
                        extension,
                        format,
                        dataField + ".path",
                        dataField + ".format",
                        externalId,
                        path
                );
            }
            Map<String, Object> metadata = objectMap(
                    item.get("metadata"),
                    dataField + ".metadata",
                    externalId,
                    path
            );
            data.add(new ManifestData(
                    path,
                    dataType,
                    sensor,
                    channel,
                    seq,
                    format,
                    fileName,
                    inferContentType(extension),
                    metadata,
                    zipEntryMap.get(path)
            ));
        }
        return data;
    }

    private List<ManifestAnnotation> parseAnnotations(
            JsonNode annotationsNode,
            String sampleField,
            String externalId,
            Map<String, ZipEntryInfo> zipEntryMap,
            Set<String> declaredPaths,
            Map<String, ManifestData> currentSampleData
    ) {
        List<ManifestAnnotation> annotations = new ArrayList<>(annotationsNode.size());
        for (int index = 0; index < annotationsNode.size(); index++) {
            JsonNode item = annotationsNode.get(index);
            String annotationField =
                    sampleField + ".annotations[" + index + "]";
            if (!item.isObject()) {
                throw error(
                        annotationField,
                        externalId,
                        null,
                        "must be a JSON object"
                );
            }
            String path = validatedDeclaredPath(
                    item,
                    true,
                    "path",
                    annotationField + ".path",
                    externalId,
                    zipEntryMap,
                    declaredPaths
            );
            String annotationType = requiredText(
                    item,
                    "annotation_type",
                    annotationField + ".annotation_type",
                    externalId,
                    path
            );
            String format = requiredText(
                    item,
                    "format",
                    annotationField + ".format",
                    externalId,
                    path
            );
            validateLength(
                    annotationField + ".annotation_type",
                    annotationType,
                    64,
                    externalId,
                    path
            );
            validateLength(
                    annotationField + ".format",
                    format,
                    32,
                    externalId,
                    path
            );

            String refDataPath = optionalText(
                    item,
                    "ref_data_path",
                    annotationField + ".ref_data_path",
                    externalId,
                    path
            );
            if (refDataPath != null) {
                refDataPath = normalizePath(
                        refDataPath,
                        annotationField + ".ref_data_path",
                        externalId
                );
                if (!currentSampleData.containsKey(refDataPath)) {
                    throw error(ManifestFailureKind.ANNOTATION_TARGET_NOT_FOUND,
                            annotationField + ".ref_data_path",
                            externalId,
                            refDataPath,
                            "ref_data_path not found in current sample"
                    );
                }
            }

            String fileName = fileName(path);
            validateLength(annotationField + ".path", fileName, 255, externalId, path);
            Map<String, Object> metadata = objectMap(
                    item.get("metadata"),
                    annotationField + ".metadata",
                    externalId,
                    path
            );
            annotations.add(new ManifestAnnotation(
                    path,
                    annotationType,
                    format,
                    refDataPath,
                    fileName,
                    inferContentType(extension(fileName)),
                    metadata,
                    zipEntryMap.get(path)
            ));
        }
        return annotations;
    }

    private String validatedDeclaredPath(
            JsonNode item,
            boolean annotationReference,
            String jsonField,
            String errorField,
            String externalId,
            Map<String, ZipEntryInfo> zipEntryMap,
            Set<String> declaredPaths
    ) {
        String rawPath = requiredText(
                item,
                jsonField,
                errorField,
                externalId,
                null
        );
        String path = normalizePath(rawPath, errorField, externalId);
        if (!declaredPaths.add(path)) {
            throw error(
                    errorField,
                    externalId,
                    path,
                    "duplicate manifest path: " + path
            );
        }
        if (!zipEntryMap.containsKey(path)) {
            throw error(annotationReference ? ManifestFailureKind.ANNOTATION_TARGET_NOT_FOUND : ManifestFailureKind.INVALID_MANIFEST,
                    errorField,
                    externalId,
                    path,
                    "path not found in zip: " + path
            );
        }
        if (zipEntryMap.get(path).directory()) {
            throw error(errorField, externalId, path, "path points to a directory");
        }
        return path;
    }

    private static String normalizePath(String rawPath, String field, String externalId) {
        if (rawPath == null || rawPath.isBlank()) {
            throw error(field, externalId, rawPath, "must be non-empty");
        }
        if (rawPath.length() > 1024) {
            throw error(field, externalId, rawPath, "length exceeds 1024");
        }
        try {
            return ZipCentralDirectoryReader.normalizePath(rawPath);
        } catch (IllegalArgumentException exception) {
            throw error(field, externalId, rawPath, exception.getMessage());
        }
    }

    private static Map<String, ZipEntryInfo> buildZipEntryMap(List<ZipEntryInfo> zipEntries) {
        if (zipEntries == null) {
            throw error("zipEntries", null, null, "cannot be null");
        }
        Map<String, ZipEntryInfo> entries = new LinkedHashMap<>();
        for (ZipEntryInfo entry : zipEntries) {
            if (entry == null) {
                throw error("zipEntries", null, null, "cannot contain null");
            }
            ZipEntryInfo previous = entries.putIfAbsent(entry.normalizedPath(), entry);
            if (previous != null) {
                throw error(
                        "zipEntries",
                        null,
                        entry.normalizedPath(),
                        "duplicate normalized ZIP path"
                );
            }
        }
        return entries;
    }

    private static ReferenceCounts validateReferenceCounts(JsonNode samplesNode) {
        int dataCount = 0;
        int annotationCount = 0;
        for (int index = 0; index < samplesNode.size(); index++) {
            JsonNode sample = samplesNode.get(index);
            String sampleField = "samples[" + index + "]";
            if (!sample.isObject()) {
                throw error(sampleField, null, null, "must be a JSON object");
            }
            JsonNode externalIdNode = sample.get("external_id");
            String externalId = externalIdNode != null && externalIdNode.isTextual()
                    ? externalIdNode.textValue()
                    : null;
            JsonNode data = optionalArray(
                    sample.get("data"),
                    sampleField + ".data",
                    externalId
            );
            JsonNode annotations = optionalArray(
                    sample.get("annotations"),
                    sampleField + ".annotations",
                    externalId
            );
            if (data.size() > MAX_DATA_PER_SAMPLE) {
                throw error(
                        sampleField + ".data",
                        externalId,
                        null,
                        "data count exceeds 100"
                );
            }
            if (annotations.size() > MAX_ANNOTATIONS_PER_SAMPLE) {
                throw error(
                        sampleField + ".annotations",
                        externalId,
                        null,
                        "annotations count exceeds 100"
                );
            }
            dataCount = addReferenceCount(dataCount, data.size());
            annotationCount = addReferenceCount(annotationCount, annotations.size());
            if ((long) dataCount + annotationCount > MAX_REFERENCES) {
                throw error(
                        "samples",
                        externalId,
                        null,
                        "data and annotations references exceed 100000"
                );
            }
        }
        return new ReferenceCounts(dataCount, annotationCount);
    }

    private static List<String> undeclaredZipEntries(
            List<ZipEntryInfo> zipEntries,
            Set<String> declaredPaths,
            String manifestPath
    ) {
        List<String> undeclared = new ArrayList<>();
        for (ZipEntryInfo entry : zipEntries) {
            if (!entry.directory()
                    && !entry.normalizedPath().equals(manifestPath)
                    && !declaredPaths.contains(entry.normalizedPath())) {
                undeclared.add(entry.normalizedPath());
            }
        }
        return undeclared;
    }

    private static String normalizeManifestPath(String manifestPath) {
        try {
            String safePath = DatasetUploadService.normalizeManifestPath("MANIFEST", manifestPath);
            return ZipCentralDirectoryReader.normalizePath(safePath);
        } catch (IllegalArgumentException exception) {
            throw error("manifestPath", null, manifestPath, exception.getMessage());
        }
    }

    private JsonNode parseRoot(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            throw error("manifestJson", null, null, "cannot be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(manifestJson);
            if (root == null || !root.isObject()) {
                throw error("manifestJson", null, null, "root must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException exception) {
            LinkedHashMap<String, Object> details = validationDetails(
                    "manifestJson",
                    null,
                    null,
                    "invalid JSON"
            );
            if (exception.getLocation() != null) {
                details.put("line", exception.getLocation().getLineNr());
                details.put("column", exception.getLocation().getColumnNr());
            }
            throw ManifestValidationException.classified(
                    ManifestFailureKind.INVALID_MANIFEST,
                    "invalid manifest JSON: " + exception.getOriginalMessage(),
                    details,
                    exception
            );
        }
    }

    private static JsonNode optionalArray(JsonNode node, String field, String externalId) {
        if (node == null || node.isNull()) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        }
        if (!node.isArray()) {
            throw error(field, externalId, null, "must be a JSON array");
        }
        return node;
    }

    private Map<String, Object> objectMap(
            JsonNode node,
            String field,
            String externalId,
            String path
    ) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw error(field, externalId, path, "must be a JSON object");
        }
        return objectMapper.convertValue(node, OBJECT_MAP_TYPE);
    }

    private static String requiredText(
            JsonNode object,
            String field,
            String externalId,
            String path
    ) {
        return requiredText(object, field, field, externalId, path);
    }

    private static String requiredText(
            JsonNode object,
            String jsonField,
            String errorField,
            String externalId,
            String path
    ) {
        JsonNode node = object.get(jsonField);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw error(
                    errorField,
                    externalId,
                    path,
                    "must be a non-empty string"
            );
        }
        return node.textValue();
    }

    private static String optionalText(
            JsonNode object,
            String field,
            String externalId,
            String path
    ) {
        return optionalText(object, field, field, externalId, path);
    }

    private static String optionalText(
            JsonNode object,
            String jsonField,
            String errorField,
            String externalId,
            String path
    ) {
        JsonNode node = object.get(jsonField);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw error(errorField, externalId, path, "must be a string");
        }
        return node.textValue();
    }

    private static int optionalNonNegativeInt(
            JsonNode node,
            int defaultValue,
            String field,
            String externalId,
            String path
    ) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw error(field, externalId, path, "must be an integer");
        }
        int value = node.intValue();
        if (value < 0) {
            throw error(field, externalId, path, "must be greater than or equal to zero");
        }
        return value;
    }

    private static void validateLength(
            String field,
            String value,
            int maxLength,
            String externalId,
            String path
    ) {
        if (value != null && value.length() > maxLength) {
            throw error(field, externalId, path, "length exceeds " + maxLength);
        }
    }

    private static void validateVideo(
            String extension,
            String format,
            String pathField,
            String formatField,
            String externalId,
            String path
    ) {
        if (!VIDEO_FORMATS.contains(extension)) {
            throw error(
                    pathField,
                    externalId,
                    path,
                    "invalid video format: " + extension
            );
        }
        String normalizedFormat = format == null ? null : format.toLowerCase(Locale.ROOT);
        if (!VIDEO_FORMATS.contains(normalizedFormat)) {
            throw error(
                    formatField,
                    externalId,
                    path,
                    "invalid video format: " + format
            );
        }
    }

    private static String inferContentType(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "txt" -> "text/plain";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "pcd" -> "application/vnd.pointcloud";
            case "ply" -> "application/vnd.ply";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
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

    private static int addReferenceCount(int current, int increment) {
        long value = (long) current + increment;
        if (value > Integer.MAX_VALUE) {
            throw error("samples", null, null, "reference count overflow");
        }
        return (int) value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ManifestValidationException error(
            String field,
            String externalId,
            String path,
            String reason
    ) {
        return error(ManifestFailureKind.INVALID_MANIFEST, field, externalId, path, reason, Map.of());
    }

    private static ManifestValidationException error(
            String field,
            String externalId,
            String path,
            String reason,
            Map<String, Object> additionalDetails
    ) {
        return error(ManifestFailureKind.INVALID_MANIFEST, field, externalId, path, reason, additionalDetails);
    }

    private static ManifestValidationException error(ManifestFailureKind kind,
            String field, String externalId, String path, String reason) {
        return error(kind, field, externalId, path, reason, Map.of());
    }

    private static ManifestValidationException error(ManifestFailureKind kind,
            String field, String externalId, String path, String reason,
            Map<String, Object> additionalDetails) {
        String displayField = displayField(field);
        StringBuilder message = new StringBuilder("field ").append(leafField(displayField));
        if (!displayField.equals(leafField(displayField))) {
            message.append(" (").append(displayField).append(')');
        }
        if (externalId != null) {
            message.append(", external_id: ").append(externalId);
        }
        if (path != null) {
            message.append(", path: ").append(path);
        }
        message.append(", reason: ").append(reason);
        LinkedHashMap<String, Object> details = validationDetails(
                field,
                externalId,
                path,
                reason
        );
        if (additionalDetails != null) {
            details.putAll(additionalDetails);
        }
        return ManifestValidationException.classified(
                kind,
                message.toString(),
                details
        );
    }

    private static String displayField(String field) {
        String display = field.replaceAll("\\[\\d+\\]", "");
        return display.startsWith("samples.") ? display.substring("samples.".length()) : display;
    }

    private static String leafField(String field) {
        int separator = field.lastIndexOf('.');
        return separator < 0 ? field : field.substring(separator + 1);
    }

    private static LinkedHashMap<String, Object> validationDetails(
            String field,
            String externalId,
            String path,
            String reason
    ) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        if (externalId != null) {
            details.put("externalId", externalId);
        }
        if (path != null) {
            details.put("path", path);
        }
        details.put("reason", reason);
        return details;
    }

    private record ReferenceCounts(int dataCount, int annotationCount) {
    }
}
