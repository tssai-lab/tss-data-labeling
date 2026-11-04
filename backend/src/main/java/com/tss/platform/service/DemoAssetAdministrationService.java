package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.InferenceScriptAsset;
import com.tss.platform.entity.InferenceScriptVersion;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.InferenceScriptAssetRepository;
import com.tss.platform.repository.InferenceScriptVersionRepository;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 演示资产标记管理（超级管理员）：
 * mark(isDemo=true) 将资产及其所有版本归属迁移到系统账户（ownerUserId=0）并置 is_demo=true，
 * 使其对所有用户可见、只读；unmark(isDemo=false) 仅清除 is_demo 标记（保留归属 0）。
 */
@Service
public class DemoAssetAdministrationService {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "dataset", "model", "code", "inference-script"
    );

    private final AuthContext authContext;
    private final DatasetAssetRepository datasetAssetRepo;
    private final DatasetVersionRepository datasetVersionRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final ModelVersionRepository modelVersionRepo;
    private final CodeAssetRepository codeAssetRepo;
    private final CodeVersionRepository codeVersionRepo;
    private final InferenceScriptAssetRepository scriptAssetRepo;
    private final InferenceScriptVersionRepository scriptVersionRepo;
    private final CodeApprovalService codeApprovalService;

    public DemoAssetAdministrationService(
            AuthContext authContext,
            DatasetAssetRepository datasetAssetRepo,
            DatasetVersionRepository datasetVersionRepo,
            ModelAssetRepository modelAssetRepo,
            ModelVersionRepository modelVersionRepo,
            CodeAssetRepository codeAssetRepo,
            CodeVersionRepository codeVersionRepo,
            InferenceScriptAssetRepository scriptAssetRepo,
            InferenceScriptVersionRepository scriptVersionRepo,
            CodeApprovalService codeApprovalService
    ) {
        this.authContext = authContext;
        this.datasetAssetRepo = datasetAssetRepo;
        this.datasetVersionRepo = datasetVersionRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.modelVersionRepo = modelVersionRepo;
        this.codeAssetRepo = codeAssetRepo;
        this.codeVersionRepo = codeVersionRepo;
        this.scriptAssetRepo = scriptAssetRepo;
        this.scriptVersionRepo = scriptVersionRepo;
        this.codeApprovalService = codeApprovalService;
    }

    @Transactional
    public Map<String, Object> setDemo(String type, String assetId, boolean isDemo) {
        if (!authContext.isSuperAdmin()) {
            throw new V2BusinessException(
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "仅超级管理员可标记演示资产",
                    null
            );
        }
        String normalizedType = type == null ? "" : type.trim();
        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ASSET_TYPE",
                    "不支持的资产类型: " + type + "（支持 dataset/model/code/inference-script）",
                    null
            );
        }
        return switch (normalizedType) {
            case "dataset" -> markDataset(assetId, isDemo);
            case "model" -> markModel(assetId, isDemo);
            case "code" -> markCode(assetId, isDemo);
            default -> markInferenceScript(assetId, isDemo);
        };
    }

    private Map<String, Object> markDataset(String assetId, boolean isDemo) {
        DatasetAsset asset = datasetAssetRepo.findByIdAndDeletedFalse(assetId)
                .orElseThrow(() -> notFound("dataset", assetId));
        List<DatasetVersion> versions = datasetVersionRepo.findByAssetIdAndDeletedFalse(assetId);
        if (isDemo) {
            asset.setIsDemo(true);
            asset.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            asset.setUpdatedAt(Instant.now());
            datasetAssetRepo.save(asset);
            for (DatasetVersion version : versions) {
                version.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            }
            datasetVersionRepo.saveAll(versions);
        } else {
            asset.setIsDemo(false);
            asset.setUpdatedAt(Instant.now());
            datasetAssetRepo.save(asset);
        }
        return result("dataset", asset.getId(), isDemo);
    }

    private Map<String, Object> markModel(String assetId, boolean isDemo) {
        ModelAsset asset = modelAssetRepo.findByIdAndDeletedFalse(assetId)
                .orElseThrow(() -> notFound("model", assetId));
        List<ModelVersion> versions = modelVersionRepo.findByAssetIdAndDeletedFalse(assetId);
        if (isDemo) {
            asset.setIsDemo(true);
            asset.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            asset.setUpdatedAt(Instant.now());
            modelAssetRepo.save(asset);
            for (ModelVersion version : versions) {
                version.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            }
            modelVersionRepo.saveAll(versions);
        } else {
            asset.setIsDemo(false);
            asset.setUpdatedAt(Instant.now());
            modelAssetRepo.save(asset);
        }
        return result("model", asset.getId(), isDemo);
    }

    private Map<String, Object> markCode(String assetId, boolean isDemo) {
        CodeAsset asset = codeAssetRepo.findByIdAndDeletedFalse(assetId)
                .orElseThrow(() -> notFound("code", assetId));
        List<CodeVersion> versions = codeVersionRepo.findByAssetIdAndDeletedFalseOrderByCreatedAtDesc(assetId);
        if (isDemo) {
            // 演示代码必须是"可训练"状态：READY + 已审批（含审批记录/校验证据/风险证据）。
            // 未审批时复用既有审批链自动审批；失败则提示先走既有校验/审批流程。
            for (CodeVersion version : versions) {
                if (!"READY".equals(version.getStatus())) {
                    continue;
                }
                if (!CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())) {
                    try {
                        codeApprovalService.decide(
                                version.getId(),
                                CodeApprovalService.Decision.APPROVE,
                                "演示资产标记：自动审批"
                        );
                    } catch (RuntimeException exception) {
                        throw new V2BusinessException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "DEMO_CODE_NOT_TRAINABLE",
                                "演示代码需先通过校验与审批（校验/风险证据不满足），请先在训练代码管理完成校验审批后重试",
                                Map.of("codeVersionId", version.getId())
                        );
                    }
                }
            }
            asset.setIsDemo(true);
            asset.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            asset.setUpdatedAt(Instant.now());
            codeAssetRepo.save(asset);
            for (CodeVersion version : versions) {
                version.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            }
            codeVersionRepo.saveAll(versions);
        } else {
            asset.setIsDemo(false);
            asset.setUpdatedAt(Instant.now());
            codeAssetRepo.save(asset);
        }
        return result("code", asset.getId(), isDemo);
    }

    private Map<String, Object> markInferenceScript(String assetId, boolean isDemo) {
        InferenceScriptAsset asset = scriptAssetRepo.findByIdAndDeletedFalse(assetId)
                .orElseThrow(() -> notFound("inference-script", assetId));
        List<InferenceScriptVersion> versions = scriptVersionRepo
                .findByAssetIdInAndDeletedFalseOrderByCreatedAtDesc(
                        Collections.singletonList(assetId)
                );
        if (isDemo) {
            asset.setIsDemo(true);
            asset.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            asset.setUpdatedAt(Instant.now());
            scriptAssetRepo.save(asset);
            for (InferenceScriptVersion version : versions) {
                version.setOwnerUserId(AuthContext.SYSTEM_USER_ID);
            }
            scriptVersionRepo.saveAll(versions);
        } else {
            asset.setIsDemo(false);
            asset.setUpdatedAt(Instant.now());
            scriptAssetRepo.save(asset);
        }
        return result("inference-script", asset.getId(), isDemo);
    }

    private Map<String, Object> result(String type, String assetId, boolean isDemo) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("assetId", assetId);
        item.put("isDemo", isDemo);
        return item;
    }

    private V2BusinessException notFound(String type, String assetId) {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "ASSET_NOT_FOUND",
                type + " 资产不存在或已删除: " + assetId,
                null
        );
    }
}
