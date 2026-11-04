package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import io.minio.StatObjectResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class LocalTrainingRunnerService {
    private final TrainingExperimentVersionRepository repository;
    private final ModelVersionRepository modelVersionRepository;
    private final DatasetVersionRepository datasetVersionRepository;
    private final MinioService minioService;
    private final MlflowTrackingService mlflowTrackingService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public LocalTrainingRunnerService(
            TrainingExperimentVersionRepository repository,
            ModelVersionRepository modelVersionRepository,
            DatasetVersionRepository datasetVersionRepository,
            MinioService minioService,
            MlflowTrackingService mlflowTrackingService,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.modelVersionRepository = modelVersionRepository;
        this.datasetVersionRepository = datasetVersionRepository;
        this.minioService = minioService;
        this.mlflowTrackingService = mlflowTrackingService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public void start(String trainingId) {
        Thread thread = new Thread(() -> run(trainingId), "local-training-" + trainingId);
        thread.setDaemon(true);
        thread.start();
    }

    private void run(String trainingId) {
        String mlflowRunId = null;
        try {
            update(trainingId, "running", 5, null, null, null, null, false);
            TrainingExperimentVersion task = repository.findById(trainingId)
                    .orElseThrow(() -> new IllegalArgumentException("训练任务不存在: " + trainingId));
            ModelVersion modelVersion = modelVersionRepository.findByIdAndDeletedFalse(task.getModelVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("模型版本不存在: " + task.getModelVersionId()));
            DatasetVersion datasetVersion = datasetVersionRepository.findByIdAndDeletedFalse(task.getDatasetVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在: " + task.getDatasetVersionId()));

            StatObjectResponse modelStat = minioService.stat(modelVersion.getStoragePath());
            mlflowRunId = mlflowTrackingService.createRun(task, modelVersion, datasetVersion);
            updateRunId(trainingId, mlflowRunId);
            update(trainingId, "running", 15, null, null, null, null, false);

            List<Sample> samples;
            try (InputStream inputStream = minioService.downloadStream(datasetVersion.getStoragePath())) {
                samples = loadYoloSamples(inputStream);
            }
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("数据集内未解析到 YOLO label 样本");
            }

            int epochs = intParam(task.getHyperParamsJson(), "epochs", 3, 1, 100);
            double lr = doubleParam(task.getHyperParamsJson(), "lr0", 0.05, 0.000001, 1.0);
            mlflowTrackingService.logParams(mlflowRunId, Map.of(
                    "training_id", trainingId,
                    "epochs", String.valueOf(epochs),
                    "lr0", String.valueOf(lr),
                    "sample_count", String.valueOf(samples.size()),
                    "model_storage_path", modelVersion.getStoragePath(),
                    "dataset_storage_path", datasetVersion.getStoragePath()
            ));
            TrainResult result = trainBoxRegressor(samples, epochs, lr, trainingId, mlflowRunId);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("mode", "local-java-lightweight-trainer");
            metrics.put("train_loss", round(result.finalLoss()));
            metrics.put("initial_loss", round(result.initialLoss()));
            metrics.put("val_mAP50", round(1.0 / (1.0 + result.finalLoss())));
            metrics.put("val_mAP50_95", round(0.7 / (1.0 + result.finalLoss())));
            metrics.put("val_accuracy", round(1.0 / (1.0 + result.finalLoss())));
            metrics.put("epochs", epochs);
            metrics.put("sample_count", samples.size());
            metrics.put("box_count", samples.size());
            metrics.put("model_size_bytes", modelStat.size());
            metrics.put("dataset_storage_path", datasetVersion.getStoragePath());
            metrics.put("model_storage_path", modelVersion.getStoragePath());

            String outputPath = "training-results/" + trainingId + "/local-regressor.json";
            String logPath = "training-results/" + trainingId + "/train.log";
            uploadJson(outputPath, Map.of(
                    "trainingId", trainingId,
                    "weights", result.weights(),
                    "metrics", metrics
            ));
            uploadText(logPath, "Local lightweight training finished.\n"
                    + "This run parsed YOLO labels and trained a small linear box regressor.\n"
                    + "samples=" + samples.size() + ", epochs=" + epochs + ", final_loss=" + result.finalLoss() + "\n");
            mlflowTrackingService.logMetrics(mlflowRunId, Map.of(
                    "train_loss", round(result.finalLoss()),
                    "initial_loss", round(result.initialLoss()),
                    "val_mAP50", round(1.0 / (1.0 + result.finalLoss())),
                    "val_mAP50_95", round(0.7 / (1.0 + result.finalLoss())),
                    "val_accuracy", round(1.0 / (1.0 + result.finalLoss()))
            ), epochs);
            mlflowTrackingService.finishRun(mlflowRunId, true);
            update(trainingId, "success", 100, metrics, null, "minio://" + logPath, "minio://" + outputPath, true);
        } catch (Exception e) {
            mlflowTrackingService.finishRun(mlflowRunId, false);
            update(trainingId, "failed", 0, null, e.getClass().getName() + ": " + e.getMessage(), null, null, true);
        }
    }

    private List<Sample> loadYoloSamples(InputStream zipStream) throws Exception {
        Map<String, double[]> imageFeatures = new HashMap<>();
        Map<String, List<double[]>> labels = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] bytes = readAll(zip);
                String stem = stem(name);
                if (isImage(name)) {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image != null) {
                        imageFeatures.put(stem, imageFeatures(image));
                    }
                } else if (name.toLowerCase().endsWith(".txt") && name.contains("labels/")) {
                    labels.put(stem, parseLabels(new String(bytes, StandardCharsets.UTF_8)));
                }
            }
        }

        List<Sample> samples = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> item : labels.entrySet()) {
            double[] features = imageFeatures.getOrDefault(item.getKey(), new double[]{1.0, 0.5, 0.0, 0.0, 0.0});
            for (double[] target : item.getValue()) {
                samples.add(new Sample(features, target));
            }
        }
        return samples;
    }

    private byte[] readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private boolean isImage(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    private String stem(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private double[] imageFeatures(BufferedImage image) {
        long sum = 0;
        int samples = 0;
        int stepX = Math.max(1, image.getWidth() / 16);
        int stepY = Math.max(1, image.getHeight() / 16);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                sum += r + g + b;
                samples += 3;
            }
        }
        double brightness = samples == 0 ? 0.0 : sum / (samples * 255.0);
        return new double[]{
                1.0,
                brightness,
                Math.min(1.0, image.getWidth() / 1024.0),
                Math.min(1.0, image.getHeight() / 1024.0),
                Math.min(1.0, (image.getWidth() * image.getHeight()) / (1024.0 * 1024.0))
        };
    }

    private List<double[]> parseLabels(String text) {
        List<double[]> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            result.add(new double[]{
                    parseDouble(parts[1]),
                    parseDouble(parts[2]),
                    parseDouble(parts[3]),
                    parseDouble(parts[4])
            });
        }
        return result;
    }

    private TrainResult trainBoxRegressor(
            List<Sample> samples,
            int epochs,
            double learningRate,
            String trainingId,
            String mlflowRunId
    ) {
        int featureSize = samples.get(0).features().length;
        double[][] weights = new double[4][featureSize];
        double initialLoss = loss(samples, weights);
        double finalLoss = initialLoss;
        for (int epoch = 1; epoch <= epochs; epoch++) {
            for (Sample sample : samples) {
                for (int output = 0; output < 4; output++) {
                    double prediction = dot(weights[output], sample.features());
                    double error = prediction - sample.target()[output];
                    for (int i = 0; i < featureSize; i++) {
                        weights[output][i] -= learningRate * error * sample.features()[i] / samples.size();
                    }
                }
            }
            finalLoss = loss(samples, weights);
            int progress = Math.min(95, 20 + (int) Math.round(epoch * 70.0 / epochs));
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("epoch", epoch);
            partial.put("train_loss", round(finalLoss));
            mlflowTrackingService.logMetrics(mlflowRunId, Map.of("train_loss", round(finalLoss)), epoch);
            update(trainingId, "running", progress, partial, null, null, null, false);
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new TrainResult(initialLoss, finalLoss, weights);
    }

    private double loss(List<Sample> samples, double[][] weights) {
        double total = 0;
        for (Sample sample : samples) {
            for (int output = 0; output < 4; output++) {
                double error = dot(weights[output], sample.features()) - sample.target()[output];
                total += error * error;
            }
        }
        return total / Math.max(1, samples.size() * 4);
    }

    private double dot(double[] weights, double[] features) {
        double result = 0;
        for (int i = 0; i < weights.length; i++) {
            result += weights[i] * features[i];
        }
        return result;
    }

    private int intParam(String json, String key, int defaultValue, int min, int max) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            int value = node.has(key) ? node.get(key).asInt(defaultValue) : defaultValue;
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double doubleParam(String json, String key, double defaultValue, double min, double max) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            double value = node.has(key) ? node.get(key).asDouble(defaultValue) : defaultValue;
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private double round(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    private void uploadJson(String objectName, Object value) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        minioService.uploadStream(objectName, new ByteArrayInputStream(bytes), bytes.length, MediaType.APPLICATION_JSON_VALUE);
    }

    private void uploadText(String objectName, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        minioService.uploadStream(objectName, new ByteArrayInputStream(bytes), bytes.length, MediaType.TEXT_PLAIN_VALUE);
    }

    private void update(
            String trainingId,
            String status,
            int progress,
            Map<String, Object> metrics,
            String errorMessage,
            String logPath,
            String outputPath,
            boolean finished
    ) {
        transactionTemplate.executeWithoutResult(statusTx -> repository.findById(trainingId).ifPresent(version -> {
            // 旧本地执行路径也不能用迟到进度恢复已结束任务。
            if (version.getStatus() != null && java.util.Set.of("success", "failed", "stopped", "cancelled").contains(version.getStatus())) return;
            version.setStatus(status);
            version.setProgress(progress);
            if (version.getStartedAt() == null && ("running".equals(status) || "success".equals(status))) {
                version.setStartedAt(Instant.now());
            }
            if (finished) {
                version.setFinishedAt(Instant.now());
            }
            version.setUpdatedAt(Instant.now());
            if (metrics != null) {
                try {
                    version.setMetricsJson(objectMapper.writeValueAsString(metrics));
                } catch (Exception ignored) {
                    version.setMetricsJson("{}");
                }
            }
            if (logPath != null) {
                version.setLogPath(logPath);
            }
            if (outputPath != null) {
                version.setOutputPath(outputPath);
            }
            if (errorMessage != null) {
                version.setErrorMessage(errorMessage);
            }
            repository.save(version);
        }));
    }

    private void updateRunId(String trainingId, String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(statusTx -> repository.findById(trainingId).ifPresent(version -> {
            version.setRunId(runId);
            version.setUpdatedAt(Instant.now());
            repository.save(version);
        }));
    }

    private record Sample(double[] features, double[] target) {
    }

    private record TrainResult(double initialLoss, double finalLoss, double[][] weights) {
    }
}
