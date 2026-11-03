package com.tss.platform.training.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.TrainingExperimentVersion;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class TrainingRunSpecCodec {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ObjectMapper objectMapper;

    public TrainingRunSpecCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public TrainingRunSpec decode(TrainingExperimentVersion task) {
        if (task == null || task.getRunSpecJson() == null || task.getRunSpecJson().isBlank()) {
            throw new IllegalArgumentException("training task has no RunSpec snapshot");
        }
        String expectedSha256 = task.getRunSpecSha256();
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException("training task RunSpec SHA-256 is invalid");
        }
        String actualSha256 = sha256(task.getRunSpecJson());
        if (!MessageDigest.isEqual(
                expectedSha256.getBytes(StandardCharsets.US_ASCII),
                actualSha256.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new IllegalArgumentException("training task RunSpec SHA-256 mismatch");
        }
        try {
            TrainingRunSpec runSpec = objectMapper.readValue(task.getRunSpecJson(), TrainingRunSpec.class);
            validateIdentity(task, runSpec);
            return runSpec;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("training task RunSpec cannot be decoded", e);
        }
    }

    private void validateIdentity(TrainingExperimentVersion task, TrainingRunSpec runSpec) {
        if (runSpec == null
                || !TrainingRunSpec.SCHEMA_VERSION.equals(runSpec.schemaVersion())
                || !Objects.equals(task.getId(), runSpec.trainingId())
                || runSpec.plan() == null
                || !Objects.equals(task.getTrainingPlanId(), runSpec.plan().id())
                || !Objects.equals(task.getTrainingPlanVersion(), runSpec.plan().version())
                || runSpec.resources() == null
                || !Objects.equals(task.getResourceProfileId(), runSpec.resources().profileId())
                || runSpec.runtime() == null
                || !Objects.equals(task.getRuntimeImage(), runSpec.runtime().image())
                || !Objects.equals(task.getRuntimeImageDigest(), runSpec.runtime().imageDigest())) {
            throw new IllegalArgumentException("training task fields do not match RunSpec snapshot");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
