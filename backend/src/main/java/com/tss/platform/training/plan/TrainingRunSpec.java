package com.tss.platform.training.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable, fully resolved execution contract persisted with one training task. */
public record TrainingRunSpec(
        String schemaVersion,
        String trainingId,
        Instant createdAt,
        PlanRef plan,
        TrainingPlanDefinition.TrainingMode trainingMode,
        Inputs inputs,
        Execution execution,
        Map<String, Object> parameters,
        Runtime runtime,
        Resources resources,
        Workspace workspace,
        TrainingPlanDefinition.Outputs outputs,
        TrainingPlanDefinition.Security security
) {

    public static final String SCHEMA_VERSION = "tss.training.run-spec/v1";

    public TrainingRunSpec(
            String schemaVersion,
            String trainingId,
            Instant createdAt,
            PlanRef plan,
            Inputs inputs,
            Execution execution,
            Map<String, Object> parameters,
            Runtime runtime,
            Resources resources,
            Workspace workspace,
            TrainingPlanDefinition.Outputs outputs,
            TrainingPlanDefinition.Security security
    ) {
        this(schemaVersion, trainingId, createdAt, plan,
                TrainingPlanDefinition.TrainingMode.FROM_SCRATCH,
                inputs, execution, parameters, runtime, resources, workspace, outputs, security);
    }

    public record PlanRef(String id, String version) {
    }

    public record Inputs(InputArtifact model, InputArtifact dataset, CodeArtifact code) {
    }

    public record InputArtifact(
            String versionId,
            String objectName,
            String sha256,
            Long sizeBytes,
            String format,
            String fileName,
            Boolean archive,
            List<String> requiredEntries,
            String artifactSpecId
    ) {
        public InputArtifact(
                String versionId,
                String objectName,
                String sha256,
                Long sizeBytes,
                String format,
                String fileName,
                Boolean archive,
                List<String> requiredEntries
        ) {
            this(versionId, objectName, sha256, sizeBytes, format, fileName,
                    archive, requiredEntries, null);
        }
    }

    public record CodeArtifact(
            String versionId,
            String objectName,
            String sha256,
            Long sizeBytes,
            String format,
            String fileName,
            Boolean archive,
            List<String> requiredEntries,
            String entrypoint,
            String approvalEvidenceId,
            List<String> requirements,
            String requirementsSha256
    ) {
        public CodeArtifact(
                String versionId,
                String objectName,
                String sha256,
                Long sizeBytes,
                String format,
                String fileName,
                Boolean archive,
                List<String> requiredEntries,
                String entrypoint,
                String approvalEvidenceId
        ) {
            this(versionId, objectName, sha256, sizeBytes, format, fileName, archive,
                    requiredEntries, entrypoint, approvalEvidenceId, List.of(), null);
        }
    }

    public record Execution(List<String> argv, String workingDirectory) {
    }

    public record Runtime(
            String variantId,
            TrainingPlanDefinition.DeviceType deviceType,
            String image,
            String imageDigest,
            TrainingPlanDefinition.ImagePullPolicy imagePullPolicy,
            String environmentFingerprint
    ) {
        public Runtime(
                String variantId,
                TrainingPlanDefinition.DeviceType deviceType,
                String image,
                String imageDigest,
                TrainingPlanDefinition.ImagePullPolicy imagePullPolicy
        ) {
            this(variantId, deviceType, image, imageDigest, imagePullPolicy, null);
        }
    }

    public record Resources(
            String profileId,
            String hardwareTargetId,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            String ephemeralStorageLimit,
            Integer gpuCount,
            Long gpuMemoryLimitMiB,
            Map<String, String> nodeSelector,
            String gpuNodeName,
            String gpuHostIndex,
            String gpuUuid,
            String gpuModel,
            Long gpuTotalMemoryMiB
    ) {
        public Resources(
                String profileId,
                String hardwareTargetId,
                String cpuRequest,
                String cpuLimit,
                String memoryRequest,
                String memoryLimit,
                String ephemeralStorageLimit,
                Integer gpuCount,
                Long gpuMemoryLimitMiB,
                Map<String, String> nodeSelector
        ) {
            this(profileId, hardwareTargetId, cpuRequest, cpuLimit, memoryRequest, memoryLimit,
                    ephemeralStorageLimit, gpuCount, gpuMemoryLimitMiB, nodeSelector,
                    null, null, null, null, null);
        }

        public Resources(
                String profileId,
                String cpuRequest,
                String cpuLimit,
                String memoryRequest,
                String memoryLimit,
                String ephemeralStorageLimit,
                Integer gpuCount,
                Long gpuMemoryLimitMiB,
                Map<String, String> nodeSelector
        ) {
            this(profileId, null, cpuRequest, cpuLimit, memoryRequest, memoryLimit,
                    ephemeralStorageLimit, gpuCount, gpuMemoryLimitMiB, nodeSelector,
                    null, null, null, null, null);
        }
    }

    public record Workspace(
            String modelDir,
            String dataDir,
            String codeDir,
            String configDir,
            String outputDir,
            String paramsFile
    ) {
        public static Workspace standard() {
            return new Workspace(
                    "/workspace/job/model",
                    "/workspace/job/data",
                    "/workspace/job/code",
                    "/workspace/job/config",
                    "/workspace/job/output",
                    "/workspace/job/config/params.json"
            );
        }
    }
}
