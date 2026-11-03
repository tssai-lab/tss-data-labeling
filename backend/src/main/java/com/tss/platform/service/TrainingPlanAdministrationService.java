package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.TrainingPlanAdminDtos;
import com.tss.platform.entity.TrainingPlanDefinitionEntity;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.repository.TrainingPlanDefinitionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.plan.TrainingPlanContent;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import com.tss.platform.training.plan.TrainingPlanValidationException;
import com.tss.platform.training.plan.TrainingPlanValidator;
import com.tss.platform.training.plan.TrainingPlanViolation;
import com.tss.platform.training.plan.TrainingPlanYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class TrainingPlanAdministrationService implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingPlanAdministrationService.class);
    private static final String SOURCE_BUILT_IN = "BUILT_IN";
    private static final String SOURCE_ONLINE = "ONLINE";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Map<String, String> TEMPLATE_RESOURCES = Map.of(
            "cv-cpu-v2", "training-plan-templates/cv-cpu-v2.yaml"
    );

    private final TrainingPlanDefinitionRepository repository;
    private final TrainingPlanRegistry registry;
    private final TrainingPlanYamlParser yamlParser;
    private final TrainingPlanValidator validator;
    private final AuthContext authContext;
    private final AuditRecordService auditRecordService;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock publicationLock = new ReentrantLock();

    public TrainingPlanAdministrationService(
            TrainingPlanDefinitionRepository repository,
            TrainingPlanRegistry registry,
            TrainingPlanYamlParser yamlParser,
            TrainingPlanValidator validator,
            AuthContext authContext,
            AuditRecordService auditRecordService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.registry = registry;
        this.yamlParser = yamlParser;
        this.validator = validator;
        this.authContext = authContext;
        this.auditRecordService = auditRecordService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<TrainingPlanDefinition> active = loadActiveDefinitions();
        registry.replaceOnlinePlans(active);
        LOG.info("Online training plans restored: activeCount={}", active.size());
    }

    public TrainingPlanAdminDtos.Preview preview(MultipartFile file) {
        Actor actor = actor();
        try {
            requireSuperAdministrator();
            TrainingPlanAdminDtos.Preview preview = analyze(file);
            if (preview.publishable()) {
                auditSuccess(AuditActionType.UPLOAD, auditId(preview.definition()),
                        "TRAINING_PLAN_PREVIEW sha256=" + preview.sha256());
            } else {
                auditFailed(AuditActionType.UPLOAD, auditId(preview.definition()),
                        firstIssueCode(preview),
                        "TRAINING_PLAN_PREVIEW sha256=" + safeSha(preview.sha256()));
            }
            return preview;
        } catch (RuntimeException exception) {
            auditException(AuditActionType.UPLOAD, "unparsed", "TRAINING_PLAN_PREVIEW", exception, actor);
            throw exception;
        }
    }

    public TrainingPlanAdminDtos.Detail publish(MultipartFile file, String expectedSha256) {
        Actor actor = actor();
        TrainingPlanAdminDtos.Preview preview = null;
        try {
            requireSuperAdministrator();
            requireExpectedSha(expectedSha256);
            preview = analyze(file);
            if (!preview.publishable() && preview.sha256() == null) {
                throw new V2BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TRAINING_PLAN_NOT_PUBLISHABLE",
                        "训练方案未通过发布校验",
                        java.util.Map.of("violations", preview.issues())
                );
            }
            if (!expectedSha256.equals(preview.sha256())) {
                throw business(
                        HttpStatus.CONFLICT,
                        "TRAINING_PLAN_SHA_MISMATCH",
                        "上传内容与预览时的 SHA-256 不一致"
                );
            }
            Optional<TrainingPlanAdminDtos.Issue> conflict = preview.issues().stream()
                    .filter(TrainingPlanAdministrationService::isPublishConflict)
                    .findFirst();
            if (conflict.isPresent()) {
                throw business(
                        HttpStatus.CONFLICT,
                        conflict.get().code(),
                        conflict.get().message()
                );
            }
            if (!preview.publishable() || preview.definition() == null) {
                throw new V2BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TRAINING_PLAN_NOT_PUBLISHABLE",
                        "训练方案未通过发布校验",
                        java.util.Map.of("violations", preview.issues())
                );
            }

            TrainingPlanDefinition candidate = preview.definition();
            byte[] content = readUpload(file);
            String yamlContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            TrainingPlanAdminDtos.Detail result;
            publicationLock.lock();
            try {
                result = publishLocked(candidate, yamlContent, preview.sha256(), actor.userId());
            } finally {
                publicationLock.unlock();
            }
            auditSuccess(AuditActionType.UPLOAD, auditId(candidate),
                    "TRAINING_PLAN_PUBLISH sha256=" + preview.sha256());
            return result;
        } catch (RuntimeException exception) {
            auditException(
                    AuditActionType.UPLOAD,
                    preview == null ? "unparsed" : auditId(preview.definition()),
                    "TRAINING_PLAN_PUBLISH sha256=" + safeSha(preview == null ? null : preview.sha256()),
                    exception,
                    actor
            );
            throw exception;
        }
    }

    public TrainingPlanAdminDtos.Detail disable(String planId, String version) {
        Actor actor = actor();
        String normalizedId = "invalid";
        String normalizedVersion = "invalid";
        try {
            requireSuperAdministrator();
            normalizedId = normalizeRequired(planId, "planId");
            normalizedVersion = normalizeRequired(version, "version");
            TrainingPlanAdminDtos.Detail result;
            publicationLock.lock();
            try {
                result = disableLocked(normalizedId, normalizedVersion, actor.userId());
            } finally {
                publicationLock.unlock();
            }
            auditSuccess(AuditActionType.DELETE, normalizedId + "@" + normalizedVersion,
                    "TRAINING_PLAN_DISABLE");
            return result;
        } catch (RuntimeException exception) {
            auditException(AuditActionType.DELETE, normalizedId + "@" + normalizedVersion,
                    "TRAINING_PLAN_DISABLE", exception, actor);
            throw exception;
        }
    }

    public List<TrainingPlanAdminDtos.Summary> list() {
        requireSuperAdministrator();
        List<TrainingPlanAdminDtos.Summary> result = new ArrayList<>();
        registry.listBuiltInPlans().forEach(item -> result.add(summary(item)));
        repository.findAllByOrderByPlanIdAscPlanVersionAsc().forEach(entity ->
                result.add(summary(entity, decode(entity)))
        );
        result.sort(Comparator
                .comparing(TrainingPlanAdminDtos.Summary::planId)
                .thenComparingInt(item -> versionNumber(item.planVersion()))
                .thenComparing(TrainingPlanAdminDtos.Summary::source));
        return List.copyOf(result);
    }

    public TrainingPlanAdminDtos.TemplateFile template(String templateId) {
        requireSuperAdministrator();
        String normalizedId = normalizeRequired(templateId, "templateId");
        String resourcePath = TEMPLATE_RESOURCES.get(normalizedId);
        if (resourcePath == null) {
            throw business(
                    HttpStatus.NOT_FOUND,
                    "TRAINING_PLAN_TEMPLATE_NOT_FOUND",
                    "训练方案模板不存在"
            );
        }
        try (InputStream input = new ClassPathResource(resourcePath).getInputStream()) {
            byte[] content = input.readAllBytes();
            TrainingPlanDefinition definition = yamlParser.parse(content, resourcePath);
            validator.validate(definition, resourcePath);
            if (!TrainingPlanValidator.SCHEMA_VERSION_V2.equals(definition.schemaVersion())) {
                throw new IllegalStateException("内置训练方案模板必须使用 v2 Schema");
            }
            return new TrainingPlanAdminDtos.TemplateFile(
                    normalizedId,
                    "training-plan-" + normalizedId + ".yaml",
                    new String(content, java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("内置训练方案模板无效: " + normalizedId, exception);
        }
    }

    public TrainingPlanAdminDtos.Detail get(String planId, String version) {
        requireSuperAdministrator();
        String normalizedId = normalizeRequired(planId, "planId");
        String normalizedVersion = normalizeRequired(version, "version");
        Optional<TrainingPlanDefinitionEntity> online = repository.findByPlanIdAndPlanVersion(
                normalizedId, normalizedVersion
        );
        if (online.isPresent()) {
            TrainingPlanDefinition definition = decode(online.get());
            return detail(online.get(), definition);
        }
        return registry.listBuiltInPlans().stream()
                .filter(item -> normalizedId.equals(item.definition().id())
                        && normalizedVersion.equals(item.definition().version()))
                .findFirst()
                .map(this::detail)
                .orElseThrow(() -> business(
                        HttpStatus.NOT_FOUND,
                        "TRAINING_PLAN_NOT_FOUND",
                        "训练方案不存在"
                ));
    }

    private TrainingPlanAdminDtos.Preview analyze(MultipartFile file) {
        if (file == null) {
            return invalidPreview(null, "YAML_EMPTY", null, "请选择 YAML 文件");
        }
        if (file.getSize() > TrainingPlanYamlParser.MAX_BYTES) {
            return invalidPreview(null, "YAML_TOO_LARGE", null,
                    "文件不能超过 " + TrainingPlanYamlParser.MAX_BYTES + " 字节");
        }
        byte[] content = readUpload(file);
        String sha256 = TrainingPlanContent.sha256(content);
        try {
            TrainingPlanDefinition candidate = yamlParser.parse(content, "uploaded-training-plan.yaml");
            validator.validate(candidate, "uploaded-training-plan.yaml");
            List<TrainingPlanAdminDtos.Issue> issues = publicationIssues(candidate, sha256);
            TrainingPlanAdminDtos.Reference current = currentReference(candidate.id());
            TrainingPlanDefinition currentDefinition = registry.find(candidate.id(), null).orElse(null);
            List<TrainingPlanAdminDtos.Issue> warnings = List.of(new TrainingPlanAdminDtos.Issue(
                    "ASSET_COMPATIBILITY_COUNT_PENDING",
                    "acceptedSpecIds",
                    "历史资产尚未持久化规范标识，兼容数量将在资产迁移阶段接入"
            ));
            return new TrainingPlanAdminDtos.Preview(
                    sha256,
                    issues.isEmpty(),
                    candidate,
                    current,
                    List.copyOf(issues),
                    warnings,
                    changes(currentDefinition, candidate)
            );
        } catch (TrainingPlanValidationException exception) {
            return new TrainingPlanAdminDtos.Preview(
                    sha256,
                    false,
                    null,
                    null,
                    exception.getDetails().stream().map(TrainingPlanAdministrationService::issue).toList(),
                    List.of(),
                    List.of()
            );
        }
    }

    private List<TrainingPlanAdminDtos.Issue> publicationIssues(
            TrainingPlanDefinition candidate,
            String sha256
    ) {
        List<TrainingPlanAdminDtos.Issue> issues = new ArrayList<>();
        if (!TrainingPlanValidator.SCHEMA_VERSION_V2.equals(candidate.schemaVersion())) {
            issues.add(issue("TRAINING_PLAN_SCHEMA_NOT_ONLINE_PUBLISHABLE", "schemaVersion",
                    "在线方案只允许 tss.training.plan/v2"));
        }
        if (!Boolean.TRUE.equals(candidate.enabled())) {
            issues.add(issue("TRAINING_PLAN_MUST_BE_ENABLED", "enabled", "发布内容必须设置 enabled: true"));
        }
        if (registry.isBuiltIn(candidate.id(), candidate.version())) {
            issues.add(issue("TRAINING_PLAN_BUILT_IN_IMMUTABLE", "id", "不能覆盖同 ID 和版本的内置方案"));
        }
        Optional<TrainingPlanDefinitionEntity> exact = repository.findByPlanIdAndPlanVersion(
                candidate.id(), candidate.version()
        );
        if (exact.isPresent() && !sha256.equals(exact.get().getContentSha256())) {
            issues.add(issue("TRAINING_PLAN_VERSION_CONTENT_CONFLICT", "version",
                    "同 ID 和版本已存在不同内容，请提高版本号"));
        }
        if (exact.isEmpty()) {
            int highest = highestKnownVersion(candidate.id());
            if (highest >= versionNumber(candidate.version())) {
                issues.add(issue("TRAINING_PLAN_VERSION_NOT_NEWER", "version",
                        "新版本必须高于该方案已有的最高版本 v" + highest));
            }
        }
        if (issues.isEmpty()) {
            try {
                registry.prepareOnlinePlans(activeDefinitionsWith(candidate));
            } catch (RuntimeException exception) {
                LOG.warn("Training plan candidate snapshot rejected: plan={}@{}, error={}",
                        candidate.id(), candidate.version(), exception.getMessage());
                issues.add(issue("TRAINING_PLAN_REGISTRY_CONFLICT", null, "候选方案无法与当前方案目录合并"));
            }
        }
        return issues;
    }

    private TrainingPlanAdminDtos.Detail publishLocked(
            TrainingPlanDefinition candidate,
            String yamlContent,
            String sha256,
            Integer actorUserId
    ) {
        requireActorUserId(actorUserId);
        List<TrainingPlanAdminDtos.Issue> liveIssues = publicationIssues(candidate, sha256);
        if (!liveIssues.isEmpty()) {
            TrainingPlanAdminDtos.Issue issue = liveIssues.get(0);
            throw new V2BusinessException(
                    isPublishConflict(issue) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY,
                    issue.code(),
                    issue.message(),
                    java.util.Map.of("violations", liveIssues)
            );
        }
        TrainingPlanRegistry.PreparedOnlineSnapshot prepared = registry.prepareOnlinePlans(
                activeDefinitionsWith(candidate)
        );
        TrainingPlanDefinitionEntity saved;
        try {
            saved = transactionTemplate.execute(status -> publishTransaction(
                    candidate, yamlContent, sha256, actorUserId
            ));
        } catch (DataIntegrityViolationException exception) {
            Optional<TrainingPlanDefinitionEntity> winner = repository.findByPlanIdAndPlanVersion(
                    candidate.id(), candidate.version()
            );
            if (winner.isPresent()
                    && winner.get().isActive()
                    && sha256.equals(winner.get().getContentSha256())) {
                registry.replaceOnlinePlans(loadActiveDefinitions());
                return detail(winner.get(), decode(winner.get()));
            }
            throw business(HttpStatus.CONFLICT, "TRAINING_PLAN_CONCURRENT_CONFLICT",
                    "训练方案已被其他请求更新，请刷新后重试");
        }
        if (saved == null) {
            throw new IllegalStateException("训练方案发布事务未返回结果");
        }
        registry.installOnlinePlans(prepared);
        LOG.info("Online training plan published: plan={}@{}, sha256={}, actorUserId={}",
                candidate.id(), candidate.version(), sha256, actorUserId);
        return detail(saved, candidate);
    }

    private TrainingPlanDefinitionEntity publishTransaction(
            TrainingPlanDefinition candidate,
            String yamlContent,
            String sha256,
            Integer actorUserId
    ) {
        Instant now = Instant.now();
        Optional<TrainingPlanDefinitionEntity> exact = repository.findVersionForUpdate(
                candidate.id(), candidate.version()
        );
        if (exact.isPresent() && !sha256.equals(exact.get().getContentSha256())) {
            throw business(HttpStatus.CONFLICT, "TRAINING_PLAN_VERSION_CONTENT_CONFLICT",
                    "同 ID 和版本已存在不同内容，请提高版本号");
        }
        if (exact.isPresent() && exact.get().isActive()) {
            return exact.get();
        }

        List<TrainingPlanDefinitionEntity> activePlans =
                repository.findActiveByPlanIdForUpdate(candidate.id());
        for (TrainingPlanDefinitionEntity active : activePlans) {
            active.setStatus(TrainingPlanDefinitionEntity.STATUS_DISABLED);
            active.setDisabledByUserId(actorUserId);
            active.setDisabledAt(now);
            repository.save(active);
        }
        if (!activePlans.isEmpty()) {
            // PostgreSQL enforces one ACTIVE row per plan ID with a partial unique index.
            // Hibernate may otherwise insert the new ACTIVE version before flushing these updates.
            repository.flush();
        }

        TrainingPlanDefinitionEntity entity = exact.orElseGet(TrainingPlanDefinitionEntity::new);
        if (exact.isEmpty()) {
            entity.setPlanId(candidate.id());
            entity.setPlanVersion(candidate.version());
            entity.setSchemaVersion(candidate.schemaVersion());
            entity.setYamlContent(yamlContent);
            entity.setContentSha256(sha256);
            entity.setImportedByUserId(actorUserId);
            entity.setImportedAt(now);
        }
        entity.setStatus(TrainingPlanDefinitionEntity.STATUS_ACTIVE);
        entity.setPublishedByUserId(actorUserId);
        entity.setPublishedAt(now);
        entity.setDisabledByUserId(null);
        entity.setDisabledAt(null);
        return repository.saveAndFlush(entity);
    }

    private TrainingPlanAdminDtos.Detail disableLocked(
            String planId,
            String version,
            Integer actorUserId
    ) {
        requireActorUserId(actorUserId);
        if (registry.isBuiltIn(planId, version)) {
            throw business(HttpStatus.CONFLICT, "TRAINING_PLAN_BUILT_IN_READ_ONLY",
                    "内置训练方案不可停用");
        }
        repository.findByPlanIdAndPlanVersion(planId, version)
                .orElseThrow(() -> business(HttpStatus.NOT_FOUND, "TRAINING_PLAN_NOT_FOUND", "训练方案不存在"));
        TrainingPlanRegistry.PreparedOnlineSnapshot prepared = registry.prepareOnlinePlans(
                loadActiveDefinitions().stream()
                        .filter(plan -> !(planId.equals(plan.id()) && version.equals(plan.version())))
                        .toList()
        );
        TrainingPlanDefinitionEntity saved = transactionTemplate.execute(status -> {
            TrainingPlanDefinitionEntity entity = repository.findVersionForUpdate(planId, version)
                    .orElseThrow(() -> business(HttpStatus.NOT_FOUND,
                            "TRAINING_PLAN_NOT_FOUND", "训练方案不存在"));
            if (!entity.isActive()) {
                return entity;
            }
            entity.setStatus(TrainingPlanDefinitionEntity.STATUS_DISABLED);
            entity.setDisabledByUserId(actorUserId);
            entity.setDisabledAt(Instant.now());
            return repository.saveAndFlush(entity);
        });
        if (saved == null) {
            throw new IllegalStateException("训练方案停用事务未返回结果");
        }
        registry.installOnlinePlans(prepared);
        LOG.info("Online training plan disabled: plan={}@{}, actorUserId={}",
                planId, version, actorUserId);
        return detail(saved, decode(saved));
    }

    private List<TrainingPlanDefinition> activeDefinitionsWith(TrainingPlanDefinition candidate) {
        List<TrainingPlanDefinition> definitions = new ArrayList<>();
        for (TrainingPlanDefinitionEntity entity : repository.findByStatusOrderByPlanIdAscPlanVersionAsc(
                TrainingPlanDefinitionEntity.STATUS_ACTIVE
        )) {
            if (!entity.getPlanId().equals(candidate.id())) {
                definitions.add(decode(entity));
            }
        }
        definitions.add(candidate);
        return List.copyOf(definitions);
    }

    private List<TrainingPlanDefinition> loadActiveDefinitions() {
        return repository.findByStatusOrderByPlanIdAscPlanVersionAsc(
                TrainingPlanDefinitionEntity.STATUS_ACTIVE
        ).stream().map(this::decode).toList();
    }

    private TrainingPlanDefinition decode(TrainingPlanDefinitionEntity entity) {
        byte[] content = TrainingPlanContent.utf8Bytes(entity.getYamlContent());
        String actualSha = TrainingPlanContent.sha256(content);
        if (!actualSha.equals(entity.getContentSha256())) {
            throw new IllegalStateException("在线训练方案内容摘要不一致: "
                    + entity.getPlanId() + "@" + entity.getPlanVersion());
        }
        TrainingPlanDefinition definition = yamlParser.parse(
                content,
                "database:" + entity.getPlanId() + "@" + entity.getPlanVersion()
        );
        validator.validate(definition, "database:" + entity.getPlanId() + "@" + entity.getPlanVersion());
        if (!entity.getPlanId().equals(definition.id())
                || !entity.getPlanVersion().equals(definition.version())
                || !entity.getSchemaVersion().equals(definition.schemaVersion())) {
            throw new IllegalStateException("在线训练方案数据库索引字段与 YAML 不一致: "
                    + entity.getPlanId() + "@" + entity.getPlanVersion());
        }
        if (!TrainingPlanValidator.SCHEMA_VERSION_V2.equals(definition.schemaVersion())) {
            throw new IllegalStateException("在线训练方案不是受支持的 v2 契约: "
                    + entity.getPlanId() + "@" + entity.getPlanVersion());
        }
        if (entity.isActive() && !Boolean.TRUE.equals(definition.enabled())) {
            throw new IllegalStateException("活动在线训练方案的 YAML 未启用: "
                    + entity.getPlanId() + "@" + entity.getPlanVersion());
        }
        return definition;
    }

    private int highestKnownVersion(String planId) {
        int highest = registry.listBuiltInPlans().stream()
                .filter(item -> planId.equals(item.definition().id()))
                .mapToInt(item -> versionNumber(item.definition().version()))
                .max().orElse(0);
        int online = repository.findByPlanIdOrderByPlanVersionAsc(planId).stream()
                .mapToInt(item -> versionNumber(item.getPlanVersion()))
                .max().orElse(0);
        return Math.max(highest, online);
    }

    private TrainingPlanAdminDtos.Reference currentReference(String planId) {
        TrainingPlanDefinition current = registry.find(planId, null).orElse(null);
        if (current == null) {
            return null;
        }
        Optional<TrainingPlanDefinitionEntity> online = repository.findByPlanIdAndPlanVersion(
                current.id(), current.version()
        ).filter(TrainingPlanDefinitionEntity::isActive);
        if (online.isPresent()) {
            return new TrainingPlanAdminDtos.Reference(
                    current.id(), current.version(), SOURCE_ONLINE,
                    online.get().getStatus(), online.get().getContentSha256()
            );
        }
        return registry.listBuiltInPlans().stream()
                .filter(item -> current.id().equals(item.definition().id())
                        && current.version().equals(item.definition().version()))
                .findFirst()
                .map(item -> new TrainingPlanAdminDtos.Reference(
                        current.id(), current.version(), SOURCE_BUILT_IN,
                        Boolean.TRUE.equals(current.enabled()) ? "ACTIVE" : "DISABLED",
                        item.sha256()
                ))
                .orElse(null);
    }

    private List<TrainingPlanAdminDtos.Change> changes(
            TrainingPlanDefinition current,
            TrainingPlanDefinition candidate
    ) {
        List<TrainingPlanAdminDtos.Change> result = new ArrayList<>();
        addChange(result, "metadata", "LOW", current == null
                        || !Objects.equals(current.displayName(), candidate.displayName())
                        || !Objects.equals(current.description(), candidate.description())
                        || !Objects.equals(current.category(), candidate.category())
                        || !Objects.equals(current.trainingModes(), candidate.trainingModes()),
                current == null);
        addChange(result, "execution", "HIGH", current == null || !Objects.equals(
                current.execution(), candidate.execution()), current == null);
        addChange(result, "inputSpecs", "HIGH", current == null || !Objects.equals(
                current.inputs(), candidate.inputs()), current == null);
        addChange(result, "parameters", "MEDIUM", current == null || !Objects.equals(
                current.parameters(), candidate.parameters()), current == null);
        addChange(result, "runtimeImagesAndResources", "HIGH", current == null || !Objects.equals(
                current.runtimes(), candidate.runtimes()), current == null);
        addChange(result, "outputs", "HIGH", current == null || !Objects.equals(
                current.outputs(), candidate.outputs()), current == null);
        addChange(result, "security", "HIGH", current == null || !Objects.equals(
                current.security(), candidate.security()), current == null);
        return List.copyOf(result);
    }

    private static void addChange(
            List<TrainingPlanAdminDtos.Change> result,
            String section,
            String risk,
            boolean changed,
            boolean added
    ) {
        if (changed) {
            result.add(new TrainingPlanAdminDtos.Change(section, added ? "ADDED" : "CHANGED", risk));
        }
    }

    private TrainingPlanAdminDtos.Summary summary(TrainingPlanRegistry.BuiltInPlan item) {
        TrainingPlanDefinition definition = item.definition();
        return new TrainingPlanAdminDtos.Summary(
                null,
                SOURCE_BUILT_IN,
                Boolean.TRUE.equals(definition.enabled()) ? "ACTIVE" : "DISABLED",
                definition.id(),
                definition.version(),
                definition.schemaVersion(),
                definition.category(),
                definition.displayName(),
                item.sha256(),
                null, null, null, null, null, null
        );
    }

    private TrainingPlanAdminDtos.Summary summary(
            TrainingPlanDefinitionEntity entity,
            TrainingPlanDefinition definition
    ) {
        return new TrainingPlanAdminDtos.Summary(
                entity.getId(), SOURCE_ONLINE, entity.getStatus(), entity.getPlanId(),
                entity.getPlanVersion(), entity.getSchemaVersion(), definition.category(),
                definition.displayName(), entity.getContentSha256(), entity.getImportedByUserId(),
                entity.getImportedAt(), entity.getPublishedByUserId(), entity.getPublishedAt(),
                entity.getDisabledByUserId(), entity.getDisabledAt()
        );
    }

    private TrainingPlanAdminDtos.Detail detail(
            TrainingPlanDefinitionEntity entity,
            TrainingPlanDefinition definition
    ) {
        return new TrainingPlanAdminDtos.Detail(summary(entity, definition), definition, entity.getYamlContent());
    }

    private TrainingPlanAdminDtos.Detail detail(TrainingPlanRegistry.BuiltInPlan item) {
        return new TrainingPlanAdminDtos.Detail(summary(item), item.definition(), item.yamlContent());
    }

    private void requireSuperAdministrator() {
        try {
            if (authContext.isSuperAdmin()) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
        throw business(HttpStatus.FORBIDDEN, "TRAINING_PLAN_ADMIN_FORBIDDEN",
                "仅超级管理员可以管理训练方案");
    }

    private Actor actor() {
        Integer userId = null;
        try {
            userId = authContext.currentUserId();
        } catch (RuntimeException ignored) {
        }
        return new Actor(userId);
    }

    private byte[] readUpload(MultipartFile file) {
        if (file == null) {
            return new byte[0];
        }
        if (file.getSize() > TrainingPlanYamlParser.MAX_BYTES) {
            throw new V2BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "YAML_TOO_LARGE", "训练方案 YAML 文件过大");
        }
        try {
            byte[] content = file.getBytes();
            if (content.length > TrainingPlanYamlParser.MAX_BYTES) {
                throw new V2BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "YAML_TOO_LARGE", "训练方案 YAML 文件过大");
            }
            return content;
        } catch (IOException exception) {
            throw new V2BusinessException(HttpStatus.BAD_REQUEST,
                    "YAML_READ_FAILED", "无法读取训练方案 YAML 文件");
        }
    }

    private void requireExpectedSha(String expectedSha256) {
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new V2BusinessException(HttpStatus.BAD_REQUEST,
                    "TRAINING_PLAN_EXPECTED_SHA_INVALID", "expectedSha256 必须是小写 64 位 SHA-256");
        }
    }

    private void auditSuccess(AuditActionType action, String objectId, String detail) {
        try {
            auditRecordService.recordSuccess(action, AuditObjectType.TRAINING_PLAN, objectId, detail);
        } catch (RuntimeException exception) {
            LOG.error("Training plan audit write failed after successful operation: action={}, objectId={}",
                    action, objectId, exception);
        }
    }

    private void auditFailed(
            AuditActionType action,
            String objectId,
            String reason,
            String detail
    ) {
        try {
            auditRecordService.recordFailed(
                    action, AuditObjectType.TRAINING_PLAN, objectId,
                    safeAuditText(reason), safeAuditText(detail)
            );
        } catch (RuntimeException exception) {
            LOG.error("Training plan failure audit write failed: action={}, objectId={}",
                    action, objectId, exception);
        }
    }

    private void auditException(
            AuditActionType action,
            String objectId,
            String operation,
            RuntimeException exception,
            Actor actor
    ) {
        String code = exception instanceof V2BusinessException business
                ? business.getErrorCode()
                : exception.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        auditFailed(action, objectId, code,
                operation + " actorUserId=" + actor.userId() + " result=FAILED");
    }

    private static String firstIssueCode(TrainingPlanAdminDtos.Preview preview) {
        return preview.issues().isEmpty() ? "VALIDATION_FAILED" : preview.issues().get(0).code();
    }

    private static boolean isPublishConflict(TrainingPlanAdminDtos.Issue issue) {
        return switch (issue.code()) {
            case "TRAINING_PLAN_BUILT_IN_IMMUTABLE", "TRAINING_PLAN_VERSION_CONTENT_CONFLICT",
                    "TRAINING_PLAN_VERSION_NOT_NEWER", "TRAINING_PLAN_REGISTRY_CONFLICT" -> true;
            default -> false;
        };
    }

    private static void requireActorUserId(Integer actorUserId) {
        if (actorUserId == null) {
            throw new V2BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "TRAINING_PLAN_ACTOR_UNAVAILABLE",
                    "登录身份不可用，请重新登录"
            );
        }
    }

    private static String safeAuditText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > 256 ? normalized.substring(0, 256) : normalized;
    }

    private static TrainingPlanAdminDtos.Preview invalidPreview(
            String sha256,
            String code,
            String path,
            String message
    ) {
        return new TrainingPlanAdminDtos.Preview(
                sha256, false, null, null,
                List.of(issue(code, path, message)), List.of(), List.of()
        );
    }

    private static TrainingPlanAdminDtos.Issue issue(TrainingPlanViolation violation) {
        return issue(violation.code().name(), violation.path(), violation.message());
    }

    private static TrainingPlanAdminDtos.Issue issue(String code, String path, String message) {
        return new TrainingPlanAdminDtos.Issue(code, path, message);
    }

    private static String auditId(TrainingPlanDefinition definition) {
        return definition == null ? "unparsed" : definition.id() + "@" + definition.version();
    }

    private static String safeSha(String sha256) {
        return sha256 == null ? "unavailable" : sha256;
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new V2BusinessException(HttpStatus.BAD_REQUEST,
                    "TRAINING_PLAN_IDENTIFIER_INVALID", field + " 不能为空");
        }
        return value.trim();
    }

    private static int versionNumber(String version) {
        try {
            return Integer.parseInt(version.substring(1));
        } catch (RuntimeException exception) {
            return Integer.MIN_VALUE;
        }
    }

    private static V2BusinessException business(HttpStatus status, String code, String message) {
        return new V2BusinessException(status, code, message);
    }

    private record Actor(Integer userId) {
    }
}
