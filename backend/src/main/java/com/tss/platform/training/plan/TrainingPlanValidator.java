package com.tss.platform.training.plan;

import com.tss.platform.asset.spec.ArtifactSpecDefinition;
import com.tss.platform.asset.spec.ArtifactSpecRegistry;
import com.tss.platform.asset.spec.AssetKind;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TrainingPlanValidator {

    public static final String SCHEMA_VERSION_V1 = "tss.training.plan/v1";
    public static final String SCHEMA_VERSION_V2 = "tss.training.plan/v2";
    /** @deprecated use an explicit version constant. */
    @Deprecated
    public static final String SCHEMA_VERSION = SCHEMA_VERSION_V1;
    public static final String PROGRESS_PROTOCOL = "TSS_EVENT_JSONL_V1";

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]*$");
    private static final Pattern SIMPLE_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern PARAMETER_NAME_PATTERN = Pattern.compile("^[a-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern CPU_PATTERN = Pattern.compile("^[0-9]+m?$");
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^[0-9]+(Mi|Gi)$");
    private static final Pattern FORMAT_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Z_]+)}");
    private static final Pattern IMMUTABLE_IMAGE_DIGEST_PATTERN = Pattern.compile(".*@sha256:[0-9a-f]{64}$");
    private static final List<String> APPROVED_TRAINING_IMAGE_PREFIXES = List.of(
            "crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-cv-worker@sha256:",
            "crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-nlp-worker@sha256:"
    );
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "MODEL_DIR", "DATA_DIR", "CODE_DIR", "OUTPUT_DIR", "PARAMS_FILE", "DEVICE"
    );
    private final ArtifactSpecRegistry artifactSpecRegistry;

    public TrainingPlanValidator(ArtifactSpecRegistry artifactSpecRegistry) {
        this.artifactSpecRegistry = artifactSpecRegistry;
    }

    public void validate(TrainingPlanDefinition plan, String source) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            throw new TrainingPlanValidationException(source, List.of("配置不能为空"));
        }

        boolean v2 = SCHEMA_VERSION_V2.equals(plan.schemaVersion());
        if (!v2 && !SCHEMA_VERSION_V1.equals(plan.schemaVersion())) {
            addError(errors, TrainingPlanErrorCode.PLAN_SCHEMA_UNSUPPORTED,
                    "schemaVersion 仅支持 " + SCHEMA_VERSION_V1 + " 或 " + SCHEMA_VERSION_V2);
        }
        requirePattern(plan.id(), ID_PATTERN, "id", errors);
        requirePattern(plan.version(), VERSION_PATTERN, "version", errors);
        requireText(plan.displayName(), "displayName", errors);
        requireMaxLength(plan.displayName(), 128, "displayName", errors);
        requireMaxLength(plan.description(), 2048, "description", errors);
        requireMaxLength(plan.unavailableReason(), 512, "unavailableReason", errors);
        if (v2 && plan.category() == null) {
            errors.add("category 不能为空");
        }
        if (plan.enabled() == null) {
            errors.add("enabled 不能为空");
        } else if (!plan.enabled() && isBlank(plan.unavailableReason())) {
            errors.add("禁用方案必须提供 unavailableReason");
        }

        validateTrainingModes(plan.trainingModes(), errors);
        validateExecution(plan.execution(), errors);
        validateInputs(plan.inputs(), v2, errors);
        validateParameters(plan.parameters(), errors);
        validateRuntimes(plan.runtimes(), v2, errors);
        validateOutputs(plan.outputs(), v2, errors);
        validateSecurity(plan.security(), errors);

        if (!errors.isEmpty()) {
            throw new TrainingPlanValidationException(source, errors);
        }
    }

    private void validateTrainingModes(
            List<TrainingPlanDefinition.TrainingMode> trainingModes,
            List<String> errors
    ) {
        if (trainingModes == null || trainingModes.isEmpty()) {
            errors.add("trainingModes 至少包含一种训练模式");
            return;
        }
        if (trainingModes.stream().anyMatch(java.util.Objects::isNull)) {
            errors.add("trainingModes 不能包含空值");
        }
        if (new HashSet<>(trainingModes).size() != trainingModes.size()) {
            errors.add("trainingModes 不能重复");
        }
    }

    public Map<String, Object> resolveParameters(
            TrainingPlanDefinition plan,
            Map<String, ?> provided
    ) {
        List<TrainingPlanDefinition.Parameter> definitions = safeList(plan.parameters());
        Map<String, TrainingPlanDefinition.Parameter> byName = new LinkedHashMap<>();
        definitions.forEach(parameter -> byName.put(parameter.name(), parameter));

        Map<String, ?> actualProvided = provided == null ? Map.of() : provided;
        List<String> errors = new ArrayList<>();
        for (String name : actualProvided.keySet()) {
            if (!byName.containsKey(name)) {
                errors.add("未知参数: " + name);
            }
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (TrainingPlanDefinition.Parameter definition : definitions) {
            Object value = actualProvided.containsKey(definition.name())
                    ? actualProvided.get(definition.name())
                    : definition.defaultValue();
            if (value == null) {
                if (Boolean.TRUE.equals(definition.required())) {
                    errors.add("缺少必填参数: " + definition.name());
                }
                continue;
            }
            validateParameterValue(definition, value, "参数 " + definition.name(), errors);
            resolved.put(definition.name(), value);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("训练参数非法: " + String.join("; ", errors));
        }
        return Map.copyOf(resolved);
    }

    private void validateExecution(TrainingPlanDefinition.Execution execution, List<String> errors) {
        if (execution == null) {
            errors.add("execution 不能为空");
            return;
        }
        if (!Set.of("python", "python3").contains(execution.interpreter())) {
            errors.add("execution.interpreter 只允许 python/python3");
        }
        validateRelativePath(execution.entrypoint(), "execution.entrypoint", true, errors);
        if (execution.entrypoint() != null && !execution.entrypoint().endsWith(".py")) {
            errors.add("execution.entrypoint 必须是 .py 文件");
        }
        if (safeList(execution.arguments()).size() > 128) {
            errors.add("execution.arguments 不能超过128项");
        }
        for (String argument : safeList(execution.arguments())) {
            if (argument == null || argument.length() > 512 || argument.indexOf('\0') >= 0) {
                errors.add("execution.arguments 包含非法参数");
                continue;
            }
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(argument);
            while (matcher.find()) {
                if (!ALLOWED_PLACEHOLDERS.contains(matcher.group(1))) {
                    errors.add("execution.arguments 包含未知占位符: " + matcher.group(1));
                }
            }
            if (argument.contains("${") && !argument.matches(".*\\$\\{[A-Z_]+}.*")) {
                errors.add("execution.arguments 包含格式错误的占位符");
            }
        }
    }

    private void validateInputs(
            TrainingPlanDefinition.Inputs inputs,
            boolean v2,
            List<String> errors
    ) {
        if (inputs == null) {
            errors.add("inputs 不能为空");
            return;
        }
        TrainingPlanDefinition.ModelInput model = inputs.model();
        if (model == null) {
            errors.add("inputs.model 不能为空");
        } else {
            requireBoolean(model.required(), "inputs.model.required", errors);
            requireBoolean(model.consumed(), "inputs.model.consumed", errors);
            requireMaxLength(model.formatGuide(), 4096, "inputs.model.formatGuide", errors);
            if (v2) {
                rejectLegacyModelInput(model, errors);
                validateAcceptedSpecs(model.acceptedSpecIds(), AssetKind.MODEL,
                        "inputs.model.acceptedSpecIds", errors);
            } else {
                validateFormats(model.formats(), "inputs.model.formats", errors);
                requireNonEmpty(model.taskTypes(), "inputs.model.taskTypes", errors);
                validateRequiredEntries(model.requiredEntries(), "inputs.model.requiredEntries", errors);
            }
        }

        TrainingPlanDefinition.DatasetInput dataset = inputs.dataset();
        if (dataset == null) {
            errors.add("inputs.dataset 不能为空");
        } else {
            requireBoolean(dataset.required(), "inputs.dataset.required", errors);
            requireMaxLength(dataset.formatGuide(), 4096, "inputs.dataset.formatGuide", errors);
            if (v2) {
                rejectLegacyDatasetInput(dataset, errors);
                validateAcceptedSpecs(dataset.acceptedSpecIds(), AssetKind.DATASET,
                        "inputs.dataset.acceptedSpecIds", errors);
            } else {
                requireNonEmpty(dataset.taskTypes(), "inputs.dataset.taskTypes", errors);
                validateRequiredEntries(dataset.requiredEntries(), "inputs.dataset.requiredEntries", errors);
            }
        }

        TrainingPlanDefinition.CodeInput code = inputs.code();
        if (code == null) {
            errors.add("inputs.code 不能为空");
        } else {
            requireBoolean(code.required(), "inputs.code.required", errors);
            requireBoolean(code.approvalRequired(), "inputs.code.approvalRequired", errors);
            if (code.runtime() == null) {
                errors.add("inputs.code.runtime 不能为空");
            }
        }
    }

    private void rejectLegacyModelInput(
            TrainingPlanDefinition.ModelInput model,
            List<String> errors
    ) {
        if (model.formats() != null
                || model.taskTypes() != null
                || model.requiredEntries() != null) {
            errors.add("v2 inputs.model 禁止 formats/taskTypes/requiredEntries，必须使用 acceptedSpecIds");
        }
    }

    private void rejectLegacyDatasetInput(
            TrainingPlanDefinition.DatasetInput dataset,
            List<String> errors
    ) {
        if (dataset.taskTypes() != null
                || dataset.cvTaskTypes() != null
                || dataset.annotationFormats() != null
                || dataset.requiredEntries() != null) {
            errors.add("v2 inputs.dataset 禁止 taskTypes/cvTaskTypes/annotationFormats/requiredEntries，必须使用 acceptedSpecIds");
        }
    }

    private void validateAcceptedSpecs(
            List<String> specIds,
            AssetKind expectedKind,
            String field,
            List<String> errors
    ) {
        if (specIds == null || specIds.isEmpty()) {
            errors.add(field + " 不能为空");
            return;
        }
        if (specIds.size() > 32) {
            errors.add(field + " 不能超过32项");
        }
        Set<String> unique = new HashSet<>();
        for (String specId : specIds) {
            if (isBlank(specId)) {
                errors.add(field + " 不能包含空值");
                continue;
            }
            String normalized = specId.trim();
            if (!normalized.equals(specId)) {
                errors.add(field + " 不能包含首尾空白: " + normalized);
            }
            if (!unique.add(normalized)) {
                errors.add(field + " 不能重复: " + normalized);
                continue;
            }
            ArtifactSpecDefinition definition = artifactSpecRegistry.find(normalized).orElse(null);
            if (definition == null) {
                addError(errors, TrainingPlanErrorCode.PLAN_SPEC_UNKNOWN,
                        field + " 包含未知资产规范: " + normalized);
            } else if (definition.assetKind() != expectedKind) {
                addError(errors, TrainingPlanErrorCode.PLAN_SPEC_KIND_MISMATCH,
                        field + " 资产类型错误: " + normalized);
            } else if (!definition.canBeAcceptedByTrainingPlan()) {
                addError(errors, TrainingPlanErrorCode.PLAN_SPEC_NOT_TRAINING_READY,
                        field + " 尚未具备训练能力: " + normalized);
            }
        }
    }

    private void validateParameters(List<TrainingPlanDefinition.Parameter> parameters, List<String> errors) {
        if (parameters == null) {
            errors.add("parameters 不能为空");
            return;
        }
        if (parameters.size() > 128) {
            errors.add("parameters 不能超过128项");
        }
        Set<String> names = new HashSet<>();
        for (TrainingPlanDefinition.Parameter parameter : parameters) {
            if (parameter == null) {
                errors.add("parameters 不能包含空项");
                continue;
            }
            requirePattern(parameter.name(), PARAMETER_NAME_PATTERN, "parameter.name", errors);
            if (parameter.name() != null && !names.add(parameter.name())) {
                errors.add("参数名称重复: " + parameter.name());
            }
            requireText(parameter.displayName(), "parameter.displayName", errors);
            requireMaxLength(parameter.displayName(), 128, "parameter.displayName", errors);
            requireMaxLength(parameter.description(), 512, "parameter.description", errors);
            if (parameter.type() == null) {
                errors.add("参数 " + parameter.name() + " 缺少 type");
            }
            requireBoolean(parameter.required(), "parameter.required", errors);
            if (parameter.minimum() != null && parameter.maximum() != null
                    && parameter.minimum() > parameter.maximum()) {
                errors.add("参数 " + parameter.name() + " minimum 不能大于 maximum");
            }
            if ((parameter.minimum() != null && !Double.isFinite(parameter.minimum()))
                    || (parameter.maximum() != null && !Double.isFinite(parameter.maximum()))) {
                errors.add("参数 " + parameter.name() + " minimum/maximum 必须是有限数字");
            }
            if (parameter.type() == TrainingPlanDefinition.ParameterType.STRING
                    || parameter.type() == TrainingPlanDefinition.ParameterType.BOOLEAN) {
                if (parameter.minimum() != null || parameter.maximum() != null) {
                    errors.add("非数值参数 " + parameter.name() + " 不能声明 minimum/maximum");
                }
            }
            if (parameter.defaultValue() != null) {
                validateParameterValue(parameter, parameter.defaultValue(), "参数默认值 " + parameter.name(), errors);
            }
            if (safeList(parameter.allowedValues()).size() > 128) {
                errors.add("参数 " + parameter.name() + " allowedValues 不能超过128项");
            }
            Set<String> allowedFingerprints = new HashSet<>();
            for (Object allowed : safeList(parameter.allowedValues())) {
                validateParameterValue(parameter, allowed, "参数允许值 " + parameter.name(), errors);
                String fingerprint = allowed == null ? "<null>" : allowed.getClass().getName() + ":" + allowed;
                if (!allowedFingerprints.add(fingerprint)) {
                    errors.add("参数 " + parameter.name() + " allowedValues 不能重复");
                }
            }
        }
    }

    private void validateRuntimes(
            List<TrainingPlanDefinition.RuntimeVariant> runtimes,
            boolean v2,
            List<String> errors
    ) {
        if (runtimes == null || runtimes.isEmpty()) {
            errors.add("runtimes 至少包含一个运行环境");
            return;
        }
        if (runtimes.size() > 16) {
            errors.add("runtimes 不能超过16项");
        }
        Set<String> runtimeIds = new HashSet<>();
        Set<String> resourceIds = new HashSet<>();
        for (TrainingPlanDefinition.RuntimeVariant runtime : runtimes) {
            if (runtime == null) {
                errors.add("runtimes 不能包含空项");
                continue;
            }
            requirePattern(runtime.id(), SIMPLE_ID_PATTERN, "runtime.id", errors);
            if (runtime.id() != null && !runtimeIds.add(runtime.id())) {
                errors.add("runtime.id 重复: " + runtime.id());
            }
            if (runtime.deviceType() == null) {
                errors.add("runtime " + runtime.id() + " 缺少 deviceType");
            }
            requireText(runtime.image(), "runtime.image", errors);
            requireMaxLength(runtime.image(), 512, "runtime.image", errors);
            if (runtime.image() != null && runtime.image().chars().anyMatch(Character::isWhitespace)) {
                errors.add("runtime.image 不能包含空白字符");
            }
            if (runtime.imagePullPolicy() == null) {
                errors.add("runtime " + runtime.id() + " 缺少 imagePullPolicy");
            }
            requireBoolean(runtime.productionDigestRequired(), "runtime.productionDigestRequired", errors);
            if (v2) {
                validateImportedRuntimePolicy(runtime, errors);
            }
            if (runtime.resourceProfiles() == null || runtime.resourceProfiles().isEmpty()) {
                errors.add("runtime " + runtime.id() + " 至少包含一个 resourceProfile");
                continue;
            }
            if (runtime.resourceProfiles().size() > 16) {
                errors.add("runtime " + runtime.id() + " 的 resourceProfiles 不能超过16项");
            }
            for (TrainingPlanDefinition.ResourceProfile profile : runtime.resourceProfiles()) {
                validateResourceProfile(runtime, profile, resourceIds, v2, errors);
            }
        }
    }

    private void validateImportedRuntimePolicy(
            TrainingPlanDefinition.RuntimeVariant runtime,
            List<String> errors
    ) {
        String image = runtime.image();
        if (!isBlank(image)
                && APPROVED_TRAINING_IMAGE_PREFIXES.stream().noneMatch(image::startsWith)) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "runtime.image 不在平台批准的训练镜像范围内");
        }
        if (!isBlank(image) && !IMMUTABLE_IMAGE_DIGEST_PATTERN.matcher(image).matches()) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "runtime.image 必须使用 sha256 镜像摘要");
        }
        if (!Boolean.TRUE.equals(runtime.productionDigestRequired())) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "runtime.productionDigestRequired 必须为true");
        }
        if (runtime.imagePullPolicy() == TrainingPlanDefinition.ImagePullPolicy.Never) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "runtime.imagePullPolicy 不允许 Never");
        }
    }

    private void validateResourceProfile(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            Set<String> resourceIds,
            boolean v2,
            List<String> errors
    ) {
        if (profile == null) {
            errors.add("resourceProfiles 不能包含空项");
            return;
        }
        requirePattern(profile.id(), SIMPLE_ID_PATTERN, "resourceProfile.id", errors);
        if (profile.id() != null && !resourceIds.add(profile.id())) {
            errors.add("resourceProfile.id 在方案内必须唯一: " + profile.id());
        }
        requirePattern(profile.cpuRequest(), CPU_PATTERN, "resourceProfile.cpuRequest", errors);
        requirePattern(profile.cpuLimit(), CPU_PATTERN, "resourceProfile.cpuLimit", errors);
        requirePattern(profile.memoryRequest(), MEMORY_PATTERN, "resourceProfile.memoryRequest", errors);
        requirePattern(profile.memoryLimit(), MEMORY_PATTERN, "resourceProfile.memoryLimit", errors);
        requirePattern(profile.ephemeralStorageLimit(), MEMORY_PATTERN,
                "resourceProfile.ephemeralStorageLimit", errors);
        if (profile.gpuCount() == null || profile.gpuCount() < 0 || profile.gpuCount() > 8) {
            errors.add("resourceProfile.gpuCount 必须在0到8之间");
        } else if (runtime.deviceType() == TrainingPlanDefinition.DeviceType.CPU && profile.gpuCount() != 0) {
            errors.add("CPU runtime 的 gpuCount 必须为0");
        } else if (runtime.deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU
                && profile.gpuCount() < 1) {
            errors.add("NVIDIA_GPU runtime 的 gpuCount 必须大于0");
        }
        for (Map.Entry<String, String> entry : safeMap(profile.nodeSelector()).entrySet()) {
            if (isBlank(entry.getKey()) || isBlank(entry.getValue())) {
                errors.add("resourceProfile.nodeSelector 不能包含空键值");
            }
        }
        if (safeMap(profile.nodeSelector()).size() > 16) {
            errors.add("resourceProfile.nodeSelector 不能超过16项");
        }
        if (v2) {
            validateImportedResourcePolicy(runtime, profile, errors);
        }
    }

    private void validateImportedResourcePolicy(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile profile,
            List<String> errors
    ) {
        Integer requestCpu = parseCpuMillis(profile.cpuRequest());
        Integer limitCpu = parseCpuMillis(profile.cpuLimit());
        if (requestCpu != null && limitCpu != null && requestCpu > limitCpu) {
            errors.add("resourceProfile.cpuRequest 不能大于 cpuLimit");
        }
        if (requestCpu != null && requestCpu <= 0) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.cpuRequest 必须大于0");
        }
        if (limitCpu != null && (limitCpu <= 0 || limitCpu > 8000)) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.cpuLimit 必须大于0且不超过8核");
        }

        Long requestMemory = parseMebibytes(profile.memoryRequest());
        Long limitMemory = parseMebibytes(profile.memoryLimit());
        if (requestMemory != null && limitMemory != null && requestMemory > limitMemory) {
            errors.add("resourceProfile.memoryRequest 不能大于 memoryLimit");
        }
        if (requestMemory != null && requestMemory <= 0) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.memoryRequest 必须大于0");
        }
        if (limitMemory != null && (limitMemory <= 0 || limitMemory > 32768)) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.memoryLimit 必须大于0且不超过32Gi");
        }

        Long ephemeral = parseMebibytes(profile.ephemeralStorageLimit());
        if (ephemeral != null && (ephemeral <= 0 || ephemeral > 51200)) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.ephemeralStorageLimit 必须大于0且不超过50Gi");
        }

        Map<String, String> selector = safeMap(profile.nodeSelector());
        if (runtime.deviceType() == TrainingPlanDefinition.DeviceType.NVIDIA_GPU) {
            if (!Integer.valueOf(1).equals(profile.gpuCount())) {
                addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                        "NVIDIA_GPU runtime 当前只允许 gpuCount=1");
            }
            if (!selector.equals(Map.of("tss.ai/accelerator", "nvidia"))) {
                addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                        "NVIDIA_GPU runtime 的 nodeSelector 必须为 tss.ai/accelerator=nvidia");
            }
        } else if (!selector.isEmpty() && !selector.equals(Map.of("tss.ai/node-pool", "cpu"))) {
            addError(errors, TrainingPlanErrorCode.PLAN_SECURITY_POLICY_VIOLATION,
                    "resourceProfile.nodeSelector 只允许 tss.ai/node-pool=cpu");
        }
    }

    private void validateOutputs(
            TrainingPlanDefinition.Outputs outputs,
            boolean v2,
            List<String> errors
    ) {
        if (outputs == null) {
            errors.add("outputs 不能为空");
            return;
        }
        requireEquals(outputs.progressProtocol(), PROGRESS_PROTOCOL, "outputs.progressProtocol", errors);
        validateRelativePath(outputs.metricsPath(), "outputs.metricsPath", false, errors);
        validateRelativePath(outputs.logPath(), "outputs.logPath", false, errors);
        if (outputs.artifacts() == null || outputs.artifacts().isEmpty()) {
            errors.add("outputs.artifacts 至少包含一个产物");
            return;
        }
        if (outputs.artifacts().size() > 128) {
            errors.add("outputs.artifacts 不能超过128项");
        }
        Set<String> paths = new HashSet<>();
        int publishCount = 0;
        boolean metricsDeclared = false;
        boolean logDeclared = false;
        List<String> packagingSources = new ArrayList<>();
        for (TrainingPlanDefinition.Artifact artifact : outputs.artifacts()) {
            if (artifact == null) {
                errors.add("outputs.artifacts 不能包含空项");
                continue;
            }
            validateRelativePath(artifact.path(), "artifact.path", false, errors);
            if (artifact.path() != null && !paths.add(artifact.path())) {
                errors.add("artifact.path 重复: " + artifact.path());
            }
            if (artifact.role() == null) {
                errors.add("artifact.role 不能为空");
            }
            requireBoolean(artifact.required(), "artifact.required", errors);
            requirePattern(artifact.format(), FORMAT_PATTERN, "artifact.format", errors);
            requireBoolean(artifact.publishAsModel(), "artifact.publishAsModel", errors);
            if (artifact.packaging() != null) {
                if (artifact.packaging().type() == null) {
                    errors.add("artifact.packaging.type 不能为空");
                }
                validateRelativePath(
                        artifact.packaging().sourcePath(),
                        "artifact.packaging.sourcePath",
                        false,
                        errors
                );
                validateRelativePath(
                        artifact.packaging().entryName(),
                        "artifact.packaging.entryName",
                        false,
                        errors
                );
                if (artifact.path() != null && artifact.path().equals(artifact.packaging().sourcePath())) {
                    errors.add("artifact.packaging.sourcePath 不能与目标 path 相同");
                }
                if (artifact.path() != null && !artifact.path().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    errors.add("ZIP_SINGLE_FILE 目标 path 必须以 .zip 结尾");
                }
                packagingSources.add(artifact.packaging().sourcePath());
            }
            if (Boolean.TRUE.equals(artifact.publishAsModel())) {
                publishCount++;
                if (!Boolean.TRUE.equals(artifact.required())
                        || artifact.role() != TrainingPlanDefinition.ArtifactRole.PRIMARY_MODEL) {
                    errors.add("发布模型产物必须是 required=true 的 PRIMARY_MODEL");
                }
                if (v2) {
                    validatePublishedModelSpec(artifact.publishedModelSpecId(), errors);
                }
            } else if (v2 && !isBlank(artifact.publishedModelSpecId())) {
                errors.add("非发布模型产物不能声明 publishedModelSpecId: " + artifact.path());
            }
            metricsDeclared |= artifact.role() == TrainingPlanDefinition.ArtifactRole.METRICS
                    && artifact.path() != null && artifact.path().equals(outputs.metricsPath());
            logDeclared |= artifact.role() == TrainingPlanDefinition.ArtifactRole.LOG
                    && artifact.path() != null && artifact.path().equals(outputs.logPath());
        }
        if (publishCount != 1) {
            errors.add("每个训练方案必须且只能声明一个 publishAsModel=true 的产物");
        }
        if (!metricsDeclared) {
            errors.add("metricsPath 必须对应 METRICS 产物");
        }
        if (!logDeclared) {
            errors.add("logPath 必须对应 LOG 产物");
        }
        for (String source : packagingSources) {
            if (source != null && !paths.contains(source)) {
                errors.add("artifact.packaging.sourcePath 必须引用已声明产物: " + source);
            }
        }
    }

    private void validatePublishedModelSpec(String specId, List<String> errors) {
        if (isBlank(specId)) {
            errors.add("发布模型产物必须声明 publishedModelSpecId");
            return;
        }
        ArtifactSpecDefinition definition = artifactSpecRegistry.find(specId.trim()).orElse(null);
        if (definition == null) {
            addError(errors, TrainingPlanErrorCode.PLAN_SPEC_UNKNOWN,
                    "publishedModelSpecId 未注册: " + specId.trim());
        } else if (definition.assetKind() != AssetKind.MODEL) {
            addError(errors, TrainingPlanErrorCode.PLAN_SPEC_KIND_MISMATCH,
                    "publishedModelSpecId 必须引用模型规范: " + specId.trim());
        } else if (!definition.canBeAcceptedByTrainingPlan()) {
            addError(errors, TrainingPlanErrorCode.PLAN_SPEC_NOT_TRAINING_READY,
                    "publishedModelSpecId 尚未具备训练能力: " + specId.trim());
        }
    }

    private void validateSecurity(TrainingPlanDefinition.Security security, List<String> errors) {
        if (security == null) {
            errors.add("security 不能为空");
            return;
        }
        if (security.networkPolicy() == null) {
            errors.add("security.networkPolicy 不能为空");
        }
        if (!Boolean.TRUE.equals(security.runAsNonRoot())) {
            errors.add("security.runAsNonRoot 必须为true");
        }
        if (!Boolean.FALSE.equals(security.allowPrivilegeEscalation())) {
            errors.add("security.allowPrivilegeEscalation 必须为false");
        }
        if (!Boolean.FALSE.equals(security.automountServiceAccountToken())) {
            errors.add("security.automountServiceAccountToken 必须为false");
        }
        if (security.maxRuntimeSeconds() == null
                || security.maxRuntimeSeconds() < 60
                || security.maxRuntimeSeconds() > 604800) {
            errors.add("security.maxRuntimeSeconds 必须在60到604800之间");
        }
    }

    private void validateParameterValue(
            TrainingPlanDefinition.Parameter definition,
            Object value,
            String field,
            List<String> errors
    ) {
        if (definition.type() == null) {
            return;
        }
        boolean typeValid = switch (definition.type()) {
            case INTEGER -> isIntegralNumber(value);
            case NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
        if (!typeValid) {
            errors.add(field + " 类型必须为 " + definition.type());
            return;
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (definition.minimum() != null && numeric < definition.minimum()) {
                errors.add(field + " 不能小于 " + definition.minimum());
            }
            if (definition.maximum() != null && numeric > definition.maximum()) {
                errors.add(field + " 不能大于 " + definition.maximum());
            }
        }
        if (definition.allowedValues() != null && !definition.allowedValues().isEmpty()
                && definition.allowedValues().stream().noneMatch(allowed -> valuesEqual(allowed, value))) {
            errors.add(field + " 不在 allowedValues 中");
        }
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString())) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    private Integer parseCpuMillis(String value) {
        if (value == null || !CPU_PATTERN.matcher(value).matches()) {
            return null;
        }
        try {
            return value.endsWith("m")
                    ? Integer.parseInt(value.substring(0, value.length() - 1))
                    : Math.multiplyExact(Integer.parseInt(value), 1000);
        } catch (ArithmeticException | NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private Long parseMebibytes(String value) {
        if (value == null || !MEMORY_PATTERN.matcher(value).matches()) {
            return null;
        }
        try {
            long amount = Long.parseLong(value.substring(0, value.length() - 2));
            return value.endsWith("Gi") ? Math.multiplyExact(amount, 1024) : amount;
        } catch (ArithmeticException | NumberFormatException exception) {
            return Long.MAX_VALUE;
        }
    }

    private boolean isIntegralNumber(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double numeric = number.doubleValue();
        return Double.isFinite(numeric) && Math.rint(numeric) == numeric;
    }

    private void validateFormats(List<String> formats, String field, List<String> errors) {
        requireNonEmpty(formats, field, errors);
        for (String format : safeList(formats)) {
            requirePattern(format, FORMAT_PATTERN, field, errors);
        }
    }

    private void validateRequiredEntries(List<String> entries, String field, List<String> errors) {
        for (String entry : safeList(entries)) {
            validateRelativePath(entry, field, false, errors);
        }
    }

    private void validateRelativePath(String value, String field, boolean codePath, List<String> errors) {
        if (isBlank(value)) {
            errors.add(field + " 不能为空");
            return;
        }
        if (value.length() > 512) {
            errors.add(field + " 不能超过512个字符");
            return;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//") || normalized.indexOf('\0') >= 0) {
            errors.add(field + " 必须是安全相对路径");
            return;
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                errors.add(field + " 必须是安全相对路径");
                return;
            }
        }
        if (codePath && normalized.toLowerCase(Locale.ROOT).endsWith(".sh")) {
            errors.add(field + " 禁止Shell脚本");
        }
    }

    private void requireNonEmpty(List<?> value, String field, List<String> errors) {
        if (value == null || value.isEmpty()) {
            errors.add(field + " 不能为空");
        }
    }

    private void requireBoolean(Boolean value, String field, List<String> errors) {
        if (value == null) {
            errors.add(field + " 不能为空");
        }
    }

    private void requireText(String value, String field, List<String> errors) {
        if (isBlank(value)) {
            errors.add(field + " 不能为空");
        }
    }

    private void requireMaxLength(String value, int maximum, String field, List<String> errors) {
        if (value != null && value.length() > maximum) {
            errors.add(field + " 不能超过" + maximum + "个字符");
        }
    }

    private void addError(
            List<String> errors,
            TrainingPlanErrorCode code,
            String message
    ) {
        errors.add(code.name() + ": " + message);
    }

    private void requireEquals(String value, String expected, String field, List<String> errors) {
        if (!expected.equals(value)) {
            errors.add(field + " 必须为 " + expected);
        }
    }

    private void requirePattern(String value, Pattern pattern, String field, List<String> errors) {
        if (isBlank(value) || !pattern.matcher(value).matches()) {
            errors.add(field + " 格式非法");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> value) {
        return value == null ? Map.of() : value;
    }
}
