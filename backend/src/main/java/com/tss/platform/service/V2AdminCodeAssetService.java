package com.tss.platform.service;

import com.tss.platform.dto.v2.V2AdminCodeAssetDto;
import com.tss.platform.dto.v2.V2AdminCodeAssetPage;
import com.tss.platform.dto.v2.V2CodeAssetDto;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceOpenRequest;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit administrator facade for cross-owner code-asset management. */
@Service
public class V2AdminCodeAssetService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 255;

    private final CodeAssetRepository assetRepository;
    private final CodeWorkspaceRepository workspaceRepository;
    private final V2CodeAssetService assetService;
    private final CodeAccessPolicy accessPolicy;

    public V2AdminCodeAssetService(
            CodeAssetRepository assetRepository,
            CodeWorkspaceRepository workspaceRepository,
            V2CodeAssetService assetService,
            CodeAccessPolicy accessPolicy
    ) {
        this.assetRepository = assetRepository;
        this.workspaceRepository = workspaceRepository;
        this.assetService = assetService;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public V2AdminCodeAssetPage list(
            Integer ownerUserId,
            String keyword,
            String trainingProfile,
            int page,
            int pageSize,
            String sortBy,
            String sortDirection
    ) {
        accessPolicy.requireAdministrator();
        validatePage(ownerUserId, page, pageSize);
        String normalizedKeyword = normalizeOptional(
                keyword, "keyword", MAX_KEYWORD_LENGTH
        );
        String normalizedProfile = normalizeOptional(
                trainingProfile, "trainingProfile", 128
        );
        Page<CodeAsset> result = assetRepository.findAll(
                specification(ownerUserId, normalizedKeyword, normalizedProfile),
                PageRequest.of(
                        page,
                        pageSize,
                        Sort.by(
                                new Sort.Order(
                                        normalizeDirection(sortDirection),
                                        normalizeSortProperty(sortBy)
                                ),
                                new Sort.Order(
                                        normalizeDirection(sortDirection),
                                        "id"
                                )
                        )
                )
        );
        List<V2AdminCodeAssetDto> items = result.getContent().stream()
                .map(asset -> V2AdminCodeAssetDto.from(
                        asset,
                        workspaceRepository.findOpenByAssetId(asset.getId()).isPresent()
                ))
                .toList();
        return new V2AdminCodeAssetPage(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public V2AdminCodeAssetDto get(String assetId) {
        Integer ownerUserId = ownerUserId(assetId);
        return V2AdminCodeAssetDto.from(assetService.getAdmin(assetId), ownerUserId);
    }

    public V2AdminCodeAssetDto patch(
            String assetId,
            V2CodeAssetPatchRequest request
    ) {
        Integer ownerUserId = ownerUserId(assetId);
        V2CodeAssetDto updated = assetService.patchAdmin(assetId, request);
        return V2AdminCodeAssetDto.from(updated, ownerUserId);
    }

    public void delete(String assetId, long expectedAssetRevision) {
        assetService.deleteAdmin(assetId, expectedAssetRevision);
    }

    public List<V2CodeWorkspaceDto> openWorkspaces(String assetId) {
        return assetService.openWorkspacesAdmin(assetId);
    }

    public V2CodeWorkspaceDto openWorkspace(
            String assetId,
            V2CodeWorkspaceOpenRequest request
    ) {
        return assetService.openWorkspaceAdmin(assetId, request);
    }

    private Integer ownerUserId(String assetId) {
        accessPolicy.requireAdministrator();
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        accessPolicy.require(CodeAccessScope.ADMIN, asset.getOwnerUserId());
        return asset.getOwnerUserId();
    }

    private static Specification<CodeAsset> specification(
            Integer ownerUserId,
            String keyword,
            String trainingProfile
    ) {
        return (asset, query, criteria) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteria.isFalse(asset.get("deleted")));
            predicates.add(criteria.isNotNull(asset.get("ownerUserId")));
            if (ownerUserId != null) {
                predicates.add(criteria.equal(asset.get("ownerUserId"), ownerUserId));
            }
            if (keyword != null) {
                predicates.add(criteria.like(
                        criteria.lower(asset.get("name")),
                        "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%",
                        '\\'
                ));
            }
            if (trainingProfile != null) {
                predicates.add(criteria.equal(
                        criteria.lower(asset.get("trainingProfile")),
                        trainingProfile.toLowerCase(Locale.ROOT)
                ));
            }
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void validatePage(Integer ownerUserId, int page, int pageSize) {
        if (ownerUserId != null && ownerUserId <= 0) {
            throw new IllegalArgumentException("ownerUserId is invalid");
        }
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pagination is invalid");
        }
    }

    private static String normalizeOptional(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || containsControl(normalized)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizeSortProperty(String value) {
        if (value == null || value.isBlank()) {
            return "updatedAt";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "UPDATED_AT", "UPDATEDAT" -> "updatedAt";
            case "CREATED_AT", "CREATEDAT" -> "createdAt";
            case "NAME" -> "name";
            case "OWNER_USER_ID", "OWNERUSERID" -> "ownerUserId";
            default -> throw new IllegalArgumentException("sortBy is invalid");
        };
    }

    private static Sort.Direction normalizeDirection(String value) {
        if (value == null || value.isBlank()) {
            return Sort.Direction.DESC;
        }
        try {
            return Sort.Direction.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sortDirection is invalid");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
