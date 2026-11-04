package com.tss.platform.asset.spec;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative content-to-contract recognition.
 *
 * <p>A broad directory category or a file extension is not proof of training
 * compatibility. These methods return {@code null} when the available evidence
 * is ambiguous, so storage can remain backward compatible without granting an
 * unverified asset training eligibility.</p>
 */
public final class ArtifactSpecEvidence {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff"
    );

    private ArtifactSpecEvidence() {
    }

    public static String recognizeModelArchive(String taskType, Collection<String> filePaths) {
        String type = normalize(taskType);
        if (filePaths == null || filePaths.isEmpty()) {
            return null;
        }
        Set<String> paths = normalizedPaths(filePaths);
        if ("NLP".equals(type)) {
            boolean descriptor = paths.contains("model.yaml")
                    && paths.contains("config.json")
                    && paths.contains("vocab.txt");
            boolean weight = paths.stream().anyMatch(path ->
                    rootFile(path) && (path.endsWith(".safetensors")
                            || "pytorch_model.bin".equals(path))
            );
            return descriptor && weight
                    ? ArtifactSpecIds.MODEL_NLP_BERT_SEQUENCE_CLASSIFICATION
                    : null;
        }
        if (!"CV".equals(type)) {
            return null;
        }
        boolean hfDescriptor = paths.contains("model.yaml") && paths.contains("config.json");
        boolean hfWeight = paths.stream().anyMatch(path ->
                rootFile(path) && (path.endsWith(".safetensors") || path.endsWith(".bin"))
        );
        boolean yoloWeight = paths.contains("yolo11n.pt");

        if (hfDescriptor && hfWeight && !yoloWeight) {
            return ArtifactSpecIds.MODEL_CV_HF_IMAGE;
        }
        if (yoloWeight && !hfDescriptor && !hfWeight) {
            return ArtifactSpecIds.MODEL_CV_YOLO_WEIGHT;
        }
        return null;
    }

    public static String recognizeSingleModel(String taskType, String fileName) {
        if (!"CV".equals(normalize(taskType))) {
            return null;
        }
        String path = normalizePath(fileName);
        int slash = path.lastIndexOf('/');
        String baseName = slash >= 0 ? path.substring(slash + 1) : path;
        return "yolo11n.pt".equals(baseName)
                ? ArtifactSpecIds.MODEL_CV_YOLO_WEIGHT
                : null;
    }

    public static String recognizeDatasetArchive(
            String taskType,
            String cvTaskType,
            String annotationFormat,
            Collection<String> filePaths
    ) {
        String type = normalize(taskType);
        Set<String> paths = normalizedPaths(filePaths);
        return switch (type) {
            case "CV" -> recognizeCvDataset(cvTaskType, annotationFormat, paths);
            case "NLP" -> hasTextClassificationJsonlStructure(paths)
                    ? ArtifactSpecIds.DATASET_NLP_TEXT_CLASSIFICATION_JSONL
                    : paths.isEmpty() ? null : ArtifactSpecIds.DATASET_NLP_DOCUMENTS;
            case "POINT_CLOUD" -> paths.stream().anyMatch(ArtifactSpecEvidence::isPointCloud)
                    ? ArtifactSpecIds.DATASET_POINT_CLOUD_PLY_PCD : null;
            case "ROBOT" -> paths.isEmpty() ? null : ArtifactSpecIds.DATASET_ROBOT_CONFIG;
            case "LEROBOT" -> hasLeRobotStructure(paths)
                    ? ArtifactSpecIds.DATASET_ROBOT_LEROBOT : null;
            default -> null;
        };
    }

    public static String recognizeSingleDataset(String taskType, String fileName) {
        String type = normalize(taskType);
        String normalizedName = normalizePath(fileName);
        if (normalizedName.isEmpty()) {
            return null;
        }
        return switch (type) {
            case "NLP" -> ArtifactSpecIds.DATASET_NLP_DOCUMENTS;
            case "POINT_CLOUD" -> isPointCloud(normalizedName)
                    ? ArtifactSpecIds.DATASET_POINT_CLOUD_PLY_PCD : null;
            case "ROBOT" -> ArtifactSpecIds.DATASET_ROBOT_CONFIG;
            default -> null;
        };
    }

    private static String recognizeCvDataset(
            String cvTaskType,
            String annotationFormat,
            Set<String> paths
    ) {
        String task = normalize(cvTaskType);
        String format = normalize(annotationFormat);
        if ("IMAGE_CLASSIFICATION".equals(task)
                && "FOLDER_CLASSIFICATION".equals(format)
                && hasImageFolderSplits(paths)) {
            return ArtifactSpecIds.DATASET_CV_IMAGE_FOLDER;
        }
        if ("OBJECT_DETECTION".equals(task)
                && "YOLO".equals(format)
                && paths.contains("data.yaml")
                && hasYoloPair(paths)) {
            return ArtifactSpecIds.DATASET_CV_YOLO;
        }
        if ("UNLABELED".equals(task)
                && "NONE".equals(format)
                && paths.stream().anyMatch(ArtifactSpecEvidence::isImage)) {
            return ArtifactSpecIds.DATASET_CV_UNLABELED_IMAGES;
        }
        return null;
    }

    private static boolean hasImageFolderSplits(Set<String> paths) {
        return hasClassImageUnder(paths, "data/train/")
                && hasClassImageUnder(paths, "data/validation/")
                && hasClassImageUnder(paths, "data/test/");
    }

    private static boolean hasTextClassificationJsonlStructure(Set<String> paths) {
        return paths.contains("dataset.json")
                && paths.contains("data/train.jsonl")
                && paths.contains("data/validation.jsonl")
                && paths.contains("data/test.jsonl");
    }

    private static boolean hasClassImageUnder(Set<String> paths, String prefix) {
        return paths.stream().anyMatch(path -> {
            if (!path.startsWith(prefix) || !isImage(path)) {
                return false;
            }
            String remainder = path.substring(prefix.length());
            int slash = remainder.indexOf('/');
            return slash > 0 && slash < remainder.length() - 1;
        });
    }

    private static boolean hasYoloPair(Set<String> paths) {
        Set<String> images = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (String path : paths) {
            String imageKey = yoloKey(path, "images", IMAGE_EXTENSIONS);
            if (imageKey != null) {
                images.add(imageKey);
            }
            String labelKey = yoloKey(path, "labels", Set.of(".txt"));
            if (labelKey != null) {
                labels.add(labelKey);
            }
        }
        images.retainAll(labels);
        return !images.isEmpty();
    }

    private static String yoloKey(String path, String marker, Set<String> extensions) {
        String markerToken = "/" + marker + "/";
        int markerIndex = ("/" + path).indexOf(markerToken);
        if (markerIndex < 0 || extensions.stream().noneMatch(path::endsWith)) {
            return null;
        }
        String withoutMarker = ("/" + path).substring(0, markerIndex)
                + ("/" + path).substring(markerIndex + markerToken.length() - 1);
        int dot = withoutMarker.lastIndexOf('.');
        return dot > 0 ? withoutMarker.substring(0, dot) : withoutMarker;
    }

    private static boolean hasLeRobotStructure(Set<String> paths) {
        Set<String> effectivePaths = withoutSingleWrapperDirectory(paths);
        return effectivePaths.contains("meta/info.json")
                && effectivePaths.stream().anyMatch(path -> path.startsWith("meta/episodes")
                && (path.endsWith(".jsonl") || path.endsWith(".parquet")))
                && effectivePaths.stream().anyMatch(path -> path.startsWith("data/") && path.endsWith(".parquet"))
                && effectivePaths.stream().anyMatch(path -> path.startsWith("videos/")
                && (path.endsWith(".mp4") || path.endsWith(".mkv")));
    }

    private static Set<String> withoutSingleWrapperDirectory(Set<String> paths) {
        if (paths.isEmpty() || paths.stream().anyMatch(path -> !path.contains("/"))) {
            return paths;
        }
        String first = paths.iterator().next();
        String wrapper = first.substring(0, first.indexOf('/') + 1);
        if (paths.stream().anyMatch(path -> !path.startsWith(wrapper))) {
            return paths;
        }
        Set<String> unwrapped = new HashSet<>();
        for (String path : paths) {
            unwrapped.add(path.substring(wrapper.length()));
        }
        return unwrapped;
    }

    private static Set<String> normalizedPaths(Collection<String> source) {
        Set<String> paths = new HashSet<>();
        if (source == null) {
            return paths;
        }
        for (String value : source) {
            String normalized = normalizePath(value);
            if (!normalized.isEmpty()) {
                paths.add(normalized);
            }
        }
        return paths;
    }

    private static String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean rootFile(String path) {
        return !path.contains("/");
    }

    private static boolean isImage(String path) {
        return IMAGE_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    private static boolean isPointCloud(String path) {
        return path.endsWith(".ply") || path.endsWith(".pcd");
    }
}
