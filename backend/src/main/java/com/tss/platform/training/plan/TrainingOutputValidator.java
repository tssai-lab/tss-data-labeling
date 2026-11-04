package com.tss.platform.training.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.TrainingExperimentVersion;
import com.tss.platform.service.ArtifactDigestService;
import com.tss.platform.service.MinioService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the immutable TrainingOutput evidence before a RunSpec task can become successful. */
@Component
public class TrainingOutputValidator {

    private static final String SCHEMA_VERSION = "tss.training.output/v1";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "schemaVersion", "trainingId", "planId", "planVersion", "status", "progress",
            "startedAt", "finishedAt", "exitCode", "errorCode", "errorMessage", "metrics",
            "artifacts", "primaryModel", "log", "inputDigests"
    );
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "path", "objectName", "role", "format", "sha256", "sizeBytes"
    );

    private final ObjectMapper objectMapper;
    private final TrainingRunSpecCodec runSpecCodec;
    private final MinioService minioService;
    private final ArtifactDigestService artifactDigestService;

    public TrainingOutputValidator(
            ObjectMapper objectMapper,
            TrainingRunSpecCodec runSpecCodec,
            MinioService minioService,
            ArtifactDigestService artifactDigestService
    ) {
        this.objectMapper = objectMapper;
        this.runSpecCodec = runSpecCodec;
        this.minioService = minioService;
        this.artifactDigestService = artifactDigestService;
    }

    public ValidatedOutput validate(
            TrainingExperimentVersion task,
            Object callbackOutput,
            String objectName,
            String expectedSha256,
            Long expectedSizeBytes
    ) {
        TrainingRunSpec runSpec = runSpecCodec.decode(task);
        String requiredObjectName = "training-results/" + task.getId() + "/training-output.json";
        if (!requiredObjectName.equals(objectName)) {
            throw new IllegalArgumentException("TrainingOutput objectName does not match the training task");
        }
        requireSha256(expectedSha256, "TrainingOutput SHA-256");
        if (expectedSizeBytes == null || expectedSizeBytes <= 0 || expectedSizeBytes > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("TrainingOutput sizeBytes is invalid");
        }

        byte[] storedBytes = downloadManifest(objectName, expectedSizeBytes);
        String actualSha256 = sha256(storedBytes);
        if (!constantTimeEquals(expectedSha256, actualSha256)) {
            throw new IllegalArgumentException("TrainingOutput SHA-256 mismatch");
        }

        JsonNode stored = parseObject(storedBytes, "stored TrainingOutput");
        JsonNode callback = toObjectNode(callbackOutput, "callback TrainingOutput");
        if (!semanticallyEqual(stored, callback)) {
            throw new IllegalArgumentException("callback TrainingOutput does not match the stored manifest");
        }

        validateIdentity(task, runSpec, stored);
        validateInputDigests(runSpec, stored.path("inputDigests"));
        Map<String, JsonNode> artifacts = validateArtifacts(task, runSpec, stored.path("artifacts"));
        validateArtifactReference(stored.path("primaryModel"), publishedArtifact(runSpec, artifacts), "primaryModel");
        validateArtifactReference(stored.path("log"), artifacts.get(runSpec.outputs().logPath()), "log");

        Instant startedAt = parseInstant(stored, "startedAt");
        Instant finishedAt = parseInstant(stored, "finishedAt");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("TrainingOutput finishedAt cannot be before startedAt");
        }
        try {
            return new ValidatedOutput(
                    objectMapper.writeValueAsString(stored),
                    stored.path("metrics"),
                    startedAt,
                    finishedAt,
                    objectName,
                    expectedSha256,
                    expectedSizeBytes
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("TrainingOutput cannot be serialized", e);
        }
    }

    private void validateIdentity(TrainingExperimentVersion task, TrainingRunSpec runSpec, JsonNode output) {
        rejectUnknownFields(output, OUTPUT_FIELDS, "TrainingOutput");
        if (!SCHEMA_VERSION.equals(text(output, "schemaVersion"))
                || !task.getId().equals(text(output, "trainingId"))
                || !runSpec.plan().id().equals(text(output, "planId"))
                || !runSpec.plan().version().equals(text(output, "planVersion"))
                || !"success".equals(text(output, "status"))
                || output.path("progress").asInt(-1) != 100
                || output.path("exitCode").asInt(Integer.MIN_VALUE) != 0
                || !output.path("metrics").isObject()) {
            throw new IllegalArgumentException("TrainingOutput identity or successful terminal state is invalid");
        }
        output.path("metrics").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isValueNode()) {
                throw new IllegalArgumentException("TrainingOutput metrics values must be scalar");
            }
        });
    }

    private void validateInputDigests(TrainingRunSpec runSpec, JsonNode digests) {
        if (!digests.isObject()
                || !runSpec.inputs().model().sha256().equals(text(digests, "model"))
                || !runSpec.inputs().dataset().sha256().equals(text(digests, "dataset"))
                || !runSpec.inputs().code().sha256().equals(text(digests, "code"))) {
            throw new IllegalArgumentException("TrainingOutput input digests do not match RunSpec");
        }
    }

    private Map<String, JsonNode> validateArtifacts(
            TrainingExperimentVersion task,
            TrainingRunSpec runSpec,
            JsonNode outputArtifacts
    ) {
        if (!outputArtifacts.isArray() || outputArtifacts.size() > 256) {
            throw new IllegalArgumentException("TrainingOutput artifacts must be an array");
        }
        Map<String, TrainingPlanDefinition.Artifact> declared = new HashMap<>();
        for (TrainingPlanDefinition.Artifact artifact : runSpec.outputs().artifacts()) {
            declared.put(artifact.path(), artifact);
        }
        Map<String, JsonNode> actual = new HashMap<>();
        Set<String> objectNames = new HashSet<>();
        for (JsonNode artifact : outputArtifacts) {
            if (!artifact.isObject()) {
                throw new IllegalArgumentException("TrainingOutput artifact must be an object");
            }
            rejectUnknownFields(artifact, ARTIFACT_FIELDS, "TrainingOutput artifact");
            String path = text(artifact, "path");
            TrainingPlanDefinition.Artifact contract = declared.get(path);
            if (contract == null || actual.putIfAbsent(path, artifact) != null) {
                throw new IllegalArgumentException("TrainingOutput contains undeclared or duplicate artifact: " + path);
            }
            String requiredObjectName = "training-results/" + task.getId() + "/artifacts/" + path;
            String objectName = text(artifact, "objectName");
            if (!requiredObjectName.equals(objectName) || !objectNames.add(objectName)) {
                throw new IllegalArgumentException("TrainingOutput artifact objectName is invalid: " + path);
            }
            if (!contract.role().name().equals(text(artifact, "role"))
                    || !contract.format().equals(text(artifact, "format"))) {
                throw new IllegalArgumentException("TrainingOutput artifact contract mismatch: " + path);
            }
            String sha256 = text(artifact, "sha256");
            requireSha256(sha256, "artifact SHA-256");
            long sizeBytes = artifact.path("sizeBytes").asLong(-1);
            ArtifactDigestService.DigestResult digest = artifactDigestService.digest(objectName, sizeBytes);
            if (!constantTimeEquals(sha256, digest.sha256())) {
                throw new IllegalArgumentException("TrainingOutput artifact SHA-256 mismatch: " + path);
            }
        }
        for (TrainingPlanDefinition.Artifact contract : runSpec.outputs().artifacts()) {
            if (Boolean.TRUE.equals(contract.required()) && !actual.containsKey(contract.path())) {
                throw new IllegalArgumentException("TrainingOutput is missing required artifact: " + contract.path());
            }
        }
        return actual;
    }

    private JsonNode publishedArtifact(TrainingRunSpec runSpec, Map<String, JsonNode> actual) {
        JsonNode published = null;
        for (TrainingPlanDefinition.Artifact contract : runSpec.outputs().artifacts()) {
            if (Boolean.TRUE.equals(contract.publishAsModel())) {
                if (published != null) {
                    throw new IllegalArgumentException("RunSpec declares multiple publishable model artifacts");
                }
                published = actual.get(contract.path());
            }
        }
        if (published == null) {
            throw new IllegalArgumentException("TrainingOutput has no publishable model artifact");
        }
        return published;
    }

    private void validateArtifactReference(JsonNode reference, JsonNode artifact, String field) {
        if (artifact == null || !reference.isObject() || !Objects.equals(reference, artifact)) {
            throw new IllegalArgumentException("TrainingOutput " + field + " does not reference a declared artifact");
        }
    }

    private byte[] downloadManifest(String objectName, long expectedSize) {
        try (InputStream input = minioService.downloadStream(objectName);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize)) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_MANIFEST_BYTES || total > expectedSize) {
                    throw new IllegalArgumentException("stored TrainingOutput size exceeds the declared limit");
                }
                output.write(buffer, 0, read);
            }
            if (total != expectedSize) {
                throw new IllegalArgumentException("stored TrainingOutput size mismatch");
            }
            return output.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("stored TrainingOutput cannot be downloaded", e);
        }
    }

    private JsonNode parseObject(byte[] data, String label) {
        try {
            JsonNode node = objectMapper.readTree(data);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " is not valid JSON", e);
        }
    }

    private JsonNode toObjectNode(Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            JsonNode node = value instanceof String
                    ? objectMapper.readTree((String) value)
                    : objectMapper.valueToTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " is not valid JSON", e);
        }
    }

    private Instant parseInstant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (Exception e) {
            throw new IllegalArgumentException("TrainingOutput " + field + " is not an ISO-8601 instant", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("TrainingOutput " + field + " is required");
        }
        return value.asText();
    }

    private void requireSha256(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String label) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(label + " contains unknown field: " + field);
            }
        });
    }

    private boolean semanticallyEqual(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> leftFields = new HashSet<>();
            left.fieldNames().forEachRemaining(leftFields::add);
            Set<String> rightFields = new HashSet<>();
            right.fieldNames().forEachRemaining(rightFields::add);
            if (!leftFields.equals(rightFields)) {
                return false;
            }
            for (String field : leftFields) {
                if (!semanticallyEqual(left.get(field), right.get(field))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!semanticallyEqual(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    public record ValidatedOutput(
            String json,
            JsonNode metrics,
            Instant startedAt,
            Instant finishedAt,
            String objectName,
            String sha256,
            Long sizeBytes
    ) {
    }
}
