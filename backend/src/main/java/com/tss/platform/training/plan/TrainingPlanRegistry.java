package com.tss.platform.training.plan;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
public class TrainingPlanRegistry {

    static final String RESOURCE_PATTERN = "classpath*:training-plans/*.yaml";

    private final TrainingPlanValidator validator;
    private final TrainingPlanYamlParser yamlParser;
    private volatile Map<String, Map<String, TrainingPlanDefinition>> builtInPlansById = Map.of();
    private volatile Map<String, Map<String, TrainingPlanDefinition>> onlinePlansById = Map.of();
    private volatile List<BuiltInPlan> builtInPlans = List.of();
    private volatile Map<String, Map<String, TrainingPlanDefinition>> plansById = Map.of();

    public TrainingPlanRegistry(
            TrainingPlanValidator validator,
            TrainingPlanYamlParser yamlParser
    ) {
        this.validator = validator;
        this.yamlParser = yamlParser;
    }

    @PostConstruct
    public void initialize() {
        reload();
    }

    public synchronized void reload() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("扫描训练方案配置失败", e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("没有找到训练方案配置: " + RESOURCE_PATTERN);
        }

        Map<String, Map<String, TrainingPlanDefinition>> loaded = new TreeMap<>();
        List<BuiltInPlan> loadedMetadata = new ArrayList<>();
        for (Resource resource : resources) {
            String source = resource.getDescription();
            byte[] content;
            try (InputStream inputStream = resource.getInputStream()) {
                content = inputStream.readAllBytes();
            } catch (IOException exception) {
                throw new IllegalStateException("读取训练方案失败 " + source, exception);
            }
            TrainingPlanDefinition plan = read(content, source);
            validator.validate(plan, source);
            Map<String, TrainingPlanDefinition> versions = loaded.computeIfAbsent(
                    plan.id(), ignored -> new TreeMap<>(TrainingPlanRegistry::compareVersions)
            );
            if (versions.putIfAbsent(plan.version(), plan) != null) {
                throw new IllegalStateException("训练方案ID和版本重复: " + plan.id() + "@" + plan.version());
            }
            loadedMetadata.add(new BuiltInPlan(
                    plan,
                    new String(content, StandardCharsets.UTF_8),
                    TrainingPlanContent.sha256(content)
            ));
        }

        Map<String, Map<String, TrainingPlanDefinition>> immutable = immutable(loaded);
        loadedMetadata.sort(Comparator
                .comparing((BuiltInPlan item) -> item.definition().id())
                .thenComparing(item -> item.definition().version(), TrainingPlanRegistry::compareVersions));
        builtInPlansById = immutable;
        builtInPlans = List.copyOf(loadedMetadata);
        plansById = merge(immutable, onlinePlansById);
    }

    public synchronized PreparedOnlineSnapshot prepareOnlinePlans(
            List<TrainingPlanDefinition> onlinePlans
    ) {
        Map<String, Map<String, TrainingPlanDefinition>> loaded = new TreeMap<>();
        for (TrainingPlanDefinition plan : onlinePlans == null ? List.<TrainingPlanDefinition>of() : onlinePlans) {
            validator.validate(plan, "online:" + (plan == null ? "unknown" : plan.id()));
            if (isBuiltIn(plan.id(), plan.version())) {
                throw new IllegalArgumentException(
                        "在线训练方案不能覆盖内置方案: " + plan.id() + "@" + plan.version()
                );
            }
            Map<String, TrainingPlanDefinition> versions = loaded.computeIfAbsent(
                    plan.id(), ignored -> new TreeMap<>(TrainingPlanRegistry::compareVersions)
            );
            if (versions.putIfAbsent(plan.version(), plan) != null) {
                throw new IllegalArgumentException(
                        "在线训练方案ID和版本重复: " + plan.id() + "@" + plan.version()
                );
            }
        }
        Map<String, Map<String, TrainingPlanDefinition>> online = immutable(loaded);
        return new PreparedOnlineSnapshot(online, merge(builtInPlansById, online));
    }

    public synchronized void installOnlinePlans(PreparedOnlineSnapshot prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared online training plans cannot be null");
        }
        onlinePlansById = prepared.onlinePlansById;
        plansById = prepared.combinedPlansById;
    }

    public synchronized void replaceOnlinePlans(List<TrainingPlanDefinition> onlinePlans) {
        installOnlinePlans(prepareOnlinePlans(onlinePlans));
    }

    public List<BuiltInPlan> listBuiltInPlans() {
        return builtInPlans;
    }

    public boolean isBuiltIn(String planId, String version) {
        if (planId == null || version == null) {
            return false;
        }
        Map<String, TrainingPlanDefinition> versions = builtInPlansById.get(planId.trim());
        return versions != null && versions.containsKey(version.trim());
    }

    public List<TrainingPlanDefinition> listLatest(boolean includeDisabled) {
        List<TrainingPlanDefinition> result = new ArrayList<>();
        plansById.values().forEach(versions -> latestOf(versions).ifPresent(plan -> {
            if (includeDisabled || Boolean.TRUE.equals(plan.enabled())) {
                result.add(plan);
            }
        }));
        result.sort(Comparator.comparing(TrainingPlanDefinition::id));
        return List.copyOf(result);
    }

    public Optional<TrainingPlanDefinition> find(String planId, String version) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        Map<String, TrainingPlanDefinition> versions = plansById.get(planId.trim());
        if (versions == null) {
            return Optional.empty();
        }
        if (version == null || version.isBlank()) {
            return latestOf(versions);
        }
        return Optional.ofNullable(versions.get(version.trim()));
    }

    public TrainingPlanDefinition require(String planId, String version) {
        return find(planId, version).orElseThrow(() -> new IllegalArgumentException(
                "训练方案不存在: " + planId + (version == null || version.isBlank() ? "" : "@" + version)
        ));
    }

    public TrainingPlanDefinition requireEnabled(String planId, String version) {
        TrainingPlanDefinition plan = require(planId, version);
        if (!Boolean.TRUE.equals(plan.enabled())) {
            String reason = plan.unavailableReason() == null ? "方案未启用" : plan.unavailableReason();
            throw new IllegalArgumentException("训练方案不可用: " + plan.id() + "，" + reason);
        }
        return plan;
    }

    public ResolvedRuntime resolveRuntime(TrainingPlanDefinition plan, String resourceProfileId) {
        if (plan == null || resourceProfileId == null || resourceProfileId.isBlank()) {
            throw new IllegalArgumentException("训练方案和resourceProfileId不能为空");
        }
        for (TrainingPlanDefinition.RuntimeVariant runtime : plan.runtimes()) {
            for (TrainingPlanDefinition.ResourceProfile profile : runtime.resourceProfiles()) {
                if (resourceProfileId.trim().equals(profile.id())) {
                    return new ResolvedRuntime(runtime, profile);
                }
            }
        }
        throw new IllegalArgumentException(
                "训练方案不支持资源规格: " + plan.id() + " -> " + resourceProfileId
        );
    }

    private TrainingPlanDefinition read(byte[] content, String source) {
        return yamlParser.parse(content, source);
    }

    private Optional<TrainingPlanDefinition> latestOf(Map<String, TrainingPlanDefinition> versions) {
        return versions.values().stream().max(
                Comparator.comparingInt(plan -> parseVersion(plan.version()))
        );
    }

    private static int compareVersions(String left, String right) {
        return Integer.compare(parseVersion(left), parseVersion(right));
    }

    private static int parseVersion(String version) {
        return Integer.parseInt(version.substring(1));
    }

    private static Map<String, Map<String, TrainingPlanDefinition>> immutable(
            Map<String, Map<String, TrainingPlanDefinition>> source
    ) {
        Map<String, Map<String, TrainingPlanDefinition>> result = new LinkedHashMap<>();
        source.forEach((id, versions) -> result.put(id, Map.copyOf(versions)));
        return Map.copyOf(result);
    }

    private static Map<String, Map<String, TrainingPlanDefinition>> merge(
            Map<String, Map<String, TrainingPlanDefinition>> builtIns,
            Map<String, Map<String, TrainingPlanDefinition>> online
    ) {
        Map<String, Map<String, TrainingPlanDefinition>> merged = new TreeMap<>();
        builtIns.forEach((id, versions) -> merged.put(id, new TreeMap<>(versions)));
        online.forEach((id, versions) -> {
            Map<String, TrainingPlanDefinition> target = merged.computeIfAbsent(
                    id, ignored -> new TreeMap<>(TrainingPlanRegistry::compareVersions)
            );
            versions.forEach((version, plan) -> {
                if (target.putIfAbsent(version, plan) != null) {
                    throw new IllegalArgumentException(
                            "在线训练方案不能覆盖内置方案: " + id + "@" + version
                    );
                }
            });
        });
        return immutable(merged);
    }

    public record BuiltInPlan(
            TrainingPlanDefinition definition,
            String yamlContent,
            String sha256
    ) {
    }

    public static final class PreparedOnlineSnapshot {
        private final Map<String, Map<String, TrainingPlanDefinition>> onlinePlansById;
        private final Map<String, Map<String, TrainingPlanDefinition>> combinedPlansById;

        private PreparedOnlineSnapshot(
                Map<String, Map<String, TrainingPlanDefinition>> onlinePlansById,
                Map<String, Map<String, TrainingPlanDefinition>> combinedPlansById
        ) {
            this.onlinePlansById = onlinePlansById;
            this.combinedPlansById = combinedPlansById;
        }
    }

    public record ResolvedRuntime(
            TrainingPlanDefinition.RuntimeVariant runtime,
            TrainingPlanDefinition.ResourceProfile resourceProfile
    ) {
    }
}
