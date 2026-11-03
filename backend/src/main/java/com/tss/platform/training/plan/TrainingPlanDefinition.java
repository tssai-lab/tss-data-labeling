package com.tss.platform.training.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public record TrainingPlanDefinition(
        String schemaVersion,
        String id,
        String version,
        String displayName,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) PlanCategory category,
        Boolean enabled,
        String unavailableReason,
        List<TrainingMode> trainingModes,
        Execution execution,
        Inputs inputs,
        List<Parameter> parameters,
        List<RuntimeVariant> runtimes,
        Outputs outputs,
        Security security
) {

    public TrainingPlanDefinition(
            String schemaVersion,
            String id,
            String version,
            String displayName,
            String description,
            Boolean enabled,
            String unavailableReason,
            List<TrainingMode> trainingModes,
            Execution execution,
            Inputs inputs,
            List<Parameter> parameters,
            List<RuntimeVariant> runtimes,
            Outputs outputs,
            Security security
    ) {
        this(schemaVersion, id, version, displayName, description, null, enabled, unavailableReason,
                trainingModes, execution, inputs, parameters, runtimes, outputs, security);
    }

    public TrainingPlanDefinition(
            String schemaVersion,
            String id,
            String version,
            String displayName,
            String description,
            Boolean enabled,
            String unavailableReason,
            Execution execution,
            Inputs inputs,
            List<Parameter> parameters,
            List<RuntimeVariant> runtimes,
            Outputs outputs,
            Security security
    ) {
        this(schemaVersion, id, version, displayName, description, null, enabled, unavailableReason,
                List.of(TrainingMode.FROM_SCRATCH), execution, inputs, parameters, runtimes, outputs, security);
    }

    public enum PlanCategory {
        CV,
        NLP,
        OTHER
    }

    public enum TrainingMode {
        FROM_SCRATCH,
        FULL_FINETUNE,
        PEFT,
        PREFERENCE_OPTIMIZATION
    }

    public record Execution(
            String interpreter,
            String entrypoint,
            List<String> arguments
    ) {
    }

    public record Inputs(
            ModelInput model,
            DatasetInput dataset,
            CodeInput code
    ) {
    }

    public record ModelInput(
            Boolean required,
            Boolean consumed,
            List<String> formats,
            List<String> taskTypes,
            List<String> requiredEntries,
            String formatGuide,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> acceptedSpecIds
    ) {
        public ModelInput(
                Boolean required,
                Boolean consumed,
                List<String> formats,
                List<String> taskTypes,
                List<String> requiredEntries,
                String formatGuide
        ) {
            this(required, consumed, formats, taskTypes, requiredEntries, formatGuide, null);
        }
    }

    public record DatasetInput(
            Boolean required,
            List<String> taskTypes,
            List<String> cvTaskTypes,
            List<String> annotationFormats,
            List<String> requiredEntries,
            String formatGuide,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> acceptedSpecIds
    ) {
        public DatasetInput(
                Boolean required,
                List<String> taskTypes,
                List<String> cvTaskTypes,
                List<String> annotationFormats,
                List<String> requiredEntries,
                String formatGuide
        ) {
            this(required, taskTypes, cvTaskTypes, annotationFormats, requiredEntries, formatGuide, null);
        }
    }

    public record CodeInput(
            Boolean required,
            Boolean approvalRequired,
            CodeRuntime runtime
    ) {
    }

    public enum CodeRuntime {
        PYTHON3
    }

    public record Parameter(
            String name,
            String displayName,
            String description,
            ParameterType type,
            Boolean required,
            Object defaultValue,
            Double minimum,
            Double maximum,
            List<Object> allowedValues
    ) {
    }

    public enum ParameterType {
        INTEGER,
        NUMBER,
        STRING,
        BOOLEAN
    }

    public record RuntimeVariant(
            String id,
            DeviceType deviceType,
            String image,
            ImagePullPolicy imagePullPolicy,
            Boolean productionDigestRequired,
            List<ResourceProfile> resourceProfiles
    ) {
    }

    public enum DeviceType {
        CPU,
        NVIDIA_GPU
    }

    public enum ImagePullPolicy {
        Always,
        IfNotPresent,
        Never
    }

    public record ResourceProfile(
            String id,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            String ephemeralStorageLimit,
            Integer gpuCount,
            Map<String, String> nodeSelector
    ) {
    }

    public record Outputs(
            String progressProtocol,
            String metricsPath,
            String logPath,
            List<Artifact> artifacts
    ) {
    }

    public record Artifact(
            String path,
            ArtifactRole role,
            Boolean required,
            String format,
            Boolean publishAsModel,
            Packaging packaging,
            @JsonInclude(JsonInclude.Include.NON_NULL) String publishedModelSpecId
    ) {
        public Artifact(
                String path,
                ArtifactRole role,
                Boolean required,
                String format,
                Boolean publishAsModel,
                Packaging packaging
        ) {
            this(path, role, required, format, publishAsModel, packaging, null);
        }

        public Artifact(
                String path,
                ArtifactRole role,
                Boolean required,
                String format,
                Boolean publishAsModel
        ) {
            this(path, role, required, format, publishAsModel, null, null);
        }
    }

    public record Packaging(
            PackagingType type,
            String sourcePath,
            String entryName
    ) {
    }

    public enum PackagingType {
        ZIP_SINGLE_FILE
    }

    public enum ArtifactRole {
        PRIMARY_MODEL,
        CHECKPOINT,
        METRICS,
        LOG,
        PREDICTIONS,
        VISUALIZATION,
        OTHER
    }

    public record Security(
            NetworkPolicy networkPolicy,
            Boolean runAsNonRoot,
            Boolean allowPrivilegeEscalation,
            Boolean automountServiceAccountToken,
            Integer maxRuntimeSeconds
    ) {
    }

    public enum NetworkPolicy {
        DENY_EGRESS,
        PLATFORM_SERVICES_ONLY
    }
}
