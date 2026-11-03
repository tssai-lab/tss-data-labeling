package com.tss.platform.service;

import com.tss.platform.dto.v2.V2AdminCodeReviewTask;
import com.tss.platform.dto.v2.V2AdminCodeReviewTaskDetail;
import com.tss.platform.dto.v2.V2AdminCodeReviewTaskPage;
import com.tss.platform.dto.v2.V2AdminCodeRiskAssessment;
import com.tss.platform.dto.v2.V2AdminCodeRiskFinding;
import com.tss.platform.dto.v2.V2CodeFileContent;
import com.tss.platform.dto.v2.V2CodeFileNode;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeRiskLevel;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeRiskFindingRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Administrator-only facade for immutable code review evidence. */
@Service
public class V2AdminCodeReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 255;
    private static final Set<String> APPROVAL_STATUSES = Set.of(
            "PENDING", "APPROVED", "REJECTED", "REVOKED"
    );
    private static final Set<String> RISK_LEVELS = Set.of(
            CodeRiskLevel.LOW,
            CodeRiskLevel.MEDIUM,
            CodeRiskLevel.HIGH,
            CodeRiskLevel.UNKNOWN
    );

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeRiskAssessmentRepository assessmentRepository;
    private final CodeRiskFindingRepository findingRepository;
    private final CodeVersionArchiveReader archiveReader;
    private final CodePathPolicy pathPolicy;
    private final CodeFilePolicy filePolicy;
    private final CodeRiskAssessmentRescanService rescanService;
    private final AuthContext authContext;

    public V2AdminCodeReviewService(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeRiskAssessmentRepository assessmentRepository,
            CodeRiskFindingRepository findingRepository,
            CodeVersionArchiveReader archiveReader,
            CodePathPolicy pathPolicy,
            CodeFilePolicy filePolicy,
            CodeRiskAssessmentRescanService rescanService,
            AuthContext authContext
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.assessmentRepository = assessmentRepository;
        this.findingRepository = findingRepository;
        this.archiveReader = archiveReader;
        this.pathPolicy = pathPolicy;
        this.filePolicy = filePolicy;
        this.rescanService = rescanService;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public V2AdminCodeReviewTaskPage list(
            String approvalStatus,
            String riskLevel,
            Integer ownerUserId,
            String keyword,
            Instant submittedFrom,
            Instant submittedTo,
            String sortBy,
            String sortDirection,
            int page,
            int pageSize
    ) {
        requireAdministratorAuthority();
        String normalizedApprovalStatus = normalizeApprovalStatus(approvalStatus);
        String normalizedRiskLevel = normalizeRiskLevel(riskLevel);
        String normalizedKeyword = normalizeKeyword(keyword);
        String sortProperty = normalizeSortProperty(sortBy);
        Sort.Direction direction = normalizeSortDirection(sortDirection);
        validateListRequest(ownerUserId, submittedFrom, submittedTo, page, pageSize);

        Page<CodeVersion> versions = versionRepository.findAll(
                reviewTaskSpecification(
                        normalizedApprovalStatus,
                        normalizedRiskLevel,
                        ownerUserId,
                        normalizedKeyword,
                        submittedFrom,
                        submittedTo
                ),
                PageRequest.of(
                        page,
                        pageSize,
                        Sort.by(
                                new Sort.Order(direction, sortProperty),
                                new Sort.Order(direction, "id")
                        )
                )
        );
        Map<String, CodeAsset> assets = assetRepository.findAllById(
                        versions.getContent().stream()
                                .map(CodeVersion::getAssetId)
                                .distinct()
                                .toList()
                ).stream()
                .filter(asset -> !Boolean.TRUE.equals(asset.getDeleted()))
                .collect(Collectors.toMap(CodeAsset::getId, Function.identity()));

        List<V2AdminCodeReviewTask> items = versions.getContent().stream()
                .map(version -> {
                    CodeAsset asset = requireMatchingAsset(version, assets.get(version.getAssetId()));
                    return V2AdminCodeReviewTask.from(
                            version,
                            asset,
                            currentAssessment(version)
                    );
                })
                .toList();
        return new V2AdminCodeReviewTaskPage(
                items,
                versions.getNumber(),
                versions.getSize(),
                versions.getTotalElements(),
                versions.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public V2AdminCodeReviewTaskDetail detail(String versionId) {
        ReviewScope scope = adminScope(versionId);
        return V2AdminCodeReviewTaskDetail.from(
                scope.version(),
                scope.asset(),
                currentAssessment(scope.version())
        );
    }

    @Transactional(readOnly = true)
    public List<V2CodeFileNode> tree(String versionId, String prefix) {
        ReviewScope scope = adminScope(versionId);
        String normalizedPrefix = pathPolicy.normalizeDirectoryPrefix(prefix);
        String prefixWithSlash = normalizedPrefix.isEmpty() ? "" : normalizedPrefix + "/";

        Map<String, V2CodeFileNode> direct = new LinkedHashMap<>();
        for (CodeArchiveEntry entry : archiveReader.list(
                scope.version(), scope.asset().getOwnerUserId()
        )) {
            if (!entry.path().startsWith(prefixWithSlash)) {
                continue;
            }
            String remainder = entry.path().substring(prefixWithSlash.length());
            if (remainder.isEmpty()) {
                continue;
            }
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                String name = remainder.substring(0, slash);
                String directoryPath = prefixWithSlash + name;
                direct.putIfAbsent(
                        directoryPath,
                        V2CodeFileNode.directory(directoryPath, name)
                );
                continue;
            }
            CodeFileDescriptor descriptor = filePolicy.describeTrustedMetadata(
                    entry.path(), entry.uncompressedSize(), null
            );
            direct.put(entry.path(), V2CodeFileNode.file(descriptor, true));
        }
        return direct.values().stream()
                .sorted(Comparator
                        .comparing((V2CodeFileNode node) ->
                                "DIRECTORY".equals(node.nodeType()) ? 0 : 1)
                        .thenComparing(V2CodeFileNode::name)
                        .thenComparing(V2CodeFileNode::path))
                .toList();
    }

    @Transactional(readOnly = true)
    public V2CodeFileContent content(String versionId, String path) {
        ReviewScope scope = adminScope(versionId);
        String normalizedPath = pathPolicy.normalizeFilePath(path);
        CodeArchiveEntry entry = archiveReader.list(
                        scope.version(), scope.asset().getOwnerUserId()
                ).stream()
                .filter(candidate -> normalizedPath.equals(candidate.path()))
                .findFirst()
                .orElseThrow(CodeAssetAccessException::new);
        if (entry.uncompressedSize() > CodeFilePolicy.EDITABLE_LIMIT_BYTES) {
            throw new CodeContentTooLargeException();
        }
        byte[] bytes = archiveReader.read(
                scope.version(),
                scope.asset().getOwnerUserId(),
                entry,
                CodeFilePolicy.EDITABLE_LIMIT_BYTES
        );
        CodeFileDescriptor descriptor = filePolicy.describe(normalizedPath, bytes);
        return V2CodeFileContent.readOnly(descriptor, filePolicy.decodeEditable(bytes));
    }

    @Transactional(readOnly = true)
    public List<V2AdminCodeRiskFinding> findings(String versionId) {
        ReviewScope scope = adminScope(versionId);
        CodeRiskAssessment assessment = currentAssessment(scope.version());
        if (assessment == null) {
            return List.of();
        }
        return findingRepository
                .findByRiskAssessmentIdOrderByFilePathAscLineStartAscIdAsc(
                        assessment.getId()
                ).stream()
                .map(V2AdminCodeRiskFinding::from)
                .toList();
    }

    public V2AdminCodeRiskAssessment rescan(String versionId) {
        ReviewScope scope = adminScope(versionId);
        CodeRiskAssessment assessment = rescanService.rescan(scope.version().getId());
        if (assessment == null
                || !Objects.equals(scope.version().getId(), assessment.getVersionId())
                || !Objects.equals(scope.version().getArtifactSha256(),
                        assessment.getArtifactSha256())) {
            throw new CodeValidationException(
                    "RISK_RESCAN_EVIDENCE_INVALID",
                    "Risk rescan did not return current artifact evidence"
            );
        }
        return V2AdminCodeRiskAssessment.from(assessment);
    }

    private ReviewScope adminScope(String versionId) {
        requireAdministratorAuthority();
        CodeVersion version = versionRepository.findByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        requireMatchingAsset(version, asset);
        return new ReviewScope(asset, version);
    }

    private CodeRiskAssessment currentAssessment(CodeVersion version) {
        if (version.getLatestRiskAssessmentId() == null
                || version.getLatestRiskAssessmentId().isBlank()) {
            return null;
        }
        return assessmentRepository.findById(version.getLatestRiskAssessmentId())
                .filter(assessment -> Objects.equals(
                        version.getId(), assessment.getVersionId()
                ))
                .filter(assessment -> Objects.equals(
                        version.getArtifactSha256(), assessment.getArtifactSha256()
                ))
                .orElse(null);
    }

    private void requireAdministratorAuthority() {
        boolean administrator;
        try {
            administrator = authContext.isAdmin();
        } catch (RuntimeException exception) {
            administrator = false;
        }
        if (!administrator) {
            throw new CodeApprovalForbiddenException();
        }
    }

    private static CodeAsset requireMatchingAsset(CodeVersion version, CodeAsset asset) {
        if (asset == null
                || !Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        return asset;
    }

    private static String normalizeApprovalStatus(String value) {
        String normalized = value == null || value.isBlank()
                ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!APPROVAL_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("approvalStatus is invalid");
        }
        return normalized;
    }

    private static String normalizeRiskLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("riskLevel is invalid");
        }
        return normalized;
    }

    private static String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_KEYWORD_LENGTH || containsControl(normalized)) {
            throw new IllegalArgumentException("keyword is invalid");
        }
        return normalized;
    }

    private static Specification<CodeVersion> reviewTaskSpecification(
            String approvalStatus,
            String riskLevel,
            Integer ownerUserId,
            String keyword,
            Instant submittedFrom,
            Instant submittedTo
    ) {
        return (version, query, criteria) -> {
            var asset = query.from(CodeAsset.class);
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteria.equal(asset.get("id"), version.get("assetId")));
            predicates.add(criteria.isFalse(asset.get("deleted")));
            predicates.add(criteria.isFalse(version.get("deleted")));
            predicates.add(criteria.equal(
                    version.get("approvalStatus"), approvalStatus
            ));
            if (riskLevel != null) {
                predicates.add(criteria.equal(version.get("riskLevel"), riskLevel));
            }
            if (ownerUserId != null) {
                predicates.add(criteria.equal(version.get("ownerUserId"), ownerUserId));
            }
            if (keyword != null) {
                String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteria.or(
                        criteria.like(criteria.lower(asset.get("name")), pattern),
                        criteria.like(criteria.lower(version.get("version")), pattern)
                ));
            }
            if (submittedFrom != null) {
                predicates.add(criteria.greaterThanOrEqualTo(
                        version.get("createdAt"), submittedFrom
                ));
            }
            if (submittedTo != null) {
                predicates.add(criteria.lessThanOrEqualTo(
                        version.get("createdAt"), submittedTo
                ));
            }
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String normalizeSortProperty(String value) {
        if (value == null || value.isBlank()) {
            return "createdAt";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "SUBMITTED_AT", "SUBMITTEDAT", "CREATED_AT", "CREATEDAT" -> "createdAt";
            case "VERSION" -> "version";
            case "RISK_LEVEL", "RISKLEVEL" -> "riskLevel";
            case "OWNER_USER_ID", "OWNERUSERID" -> "ownerUserId";
            default -> throw new IllegalArgumentException("sortBy is invalid");
        };
    }

    private static Sort.Direction normalizeSortDirection(String value) {
        if (value == null || value.isBlank()) {
            return Sort.Direction.DESC;
        }
        try {
            return Sort.Direction.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sortDirection is invalid");
        }
    }

    private static void validateListRequest(
            Integer ownerUserId,
            Instant submittedFrom,
            Instant submittedTo,
            int page,
            int pageSize
    ) {
        if (ownerUserId != null && ownerUserId <= 0) {
            throw new IllegalArgumentException("ownerUserId is invalid");
        }
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pagination is invalid");
        }
        if (submittedFrom != null
                && submittedTo != null
                && submittedFrom.isAfter(submittedTo)) {
            throw new IllegalArgumentException("submission time range is invalid");
        }
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private record ReviewScope(CodeAsset asset, CodeVersion version) {
    }
}
