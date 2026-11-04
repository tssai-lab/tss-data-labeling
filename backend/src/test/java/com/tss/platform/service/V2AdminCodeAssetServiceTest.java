package com.tss.platform.service;

import com.tss.platform.dto.v2.V2AdminCodeAssetPage;
import com.tss.platform.dto.v2.V2CodeAssetDto;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class V2AdminCodeAssetServiceTest {

    private CodeAssetRepository assetRepository;
    private CodeWorkspaceRepository workspaceRepository;
    private V2CodeAssetService assetService;
    private AuthContext authContext;
    private V2AdminCodeAssetService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(CodeAssetRepository.class);
        workspaceRepository = mock(CodeWorkspaceRepository.class);
        assetService = mock(V2CodeAssetService.class);
        authContext = mock(AuthContext.class);
        service = new V2AdminCodeAssetService(
                assetRepository,
                workspaceRepository,
                assetService,
                new CodeAccessPolicy(authContext)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsCrossOwnerAssetsWithPagingOwnershipAndNoStorageProjection() {
        when(authContext.isAdmin()).thenReturn(true);
        CodeAsset first = asset("asset-1", 7, "Alpha", 4L);
        CodeAsset second = asset("asset-2", 8, "Beta", 2L);
        when(assetRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(first, second),
                PageRequest.of(0, 20),
                2
        ));

        V2AdminCodeAssetPage result = service.list(
                null, "a", null, 0, 20, "UPDATED_AT", "DESC"
        );

        assertEquals(2, result.totalElements());
        assertEquals(List.of(7, 8), result.items().stream()
                .map(item -> item.ownerUserId())
                .toList());
        assertEquals(4L, result.items().get(0).assetRevision());
    }

    @Test
    void patchesForeignAssetThroughExplicitAdministratorDelegate() {
        when(authContext.isAdmin()).thenReturn(true);
        CodeAsset foreign = asset("asset-1", 7, "Before", 4L);
        V2CodeAssetDto updated = dto("asset-1", "After", 5L);
        V2CodeAssetPatchRequest request = mock(V2CodeAssetPatchRequest.class);
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(foreign));
        when(assetService.patchAdmin("asset-1", request)).thenReturn(updated);

        var result = service.patch("asset-1", request);

        assertEquals(7, result.ownerUserId());
        assertEquals("After", result.name());
        verify(assetService).patchAdmin("asset-1", request);
    }

    @Test
    void nonAdministratorIsRejectedBeforeRepositoryOrDelegateAccess() {
        when(authContext.isAdmin()).thenReturn(false);

        assertThrows(
                CodeApprovalForbiddenException.class,
                () -> service.list(null, null, null, 0, 20, "UPDATED_AT", "DESC")
        );

        verifyNoInteractions(assetRepository, workspaceRepository, assetService);
    }

    @Test
    void rejectsInvalidPaginationBeforeQuery() {
        when(authContext.isAdmin()).thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.list(null, null, null, 0, 101, "UPDATED_AT", "DESC")
        );

        verify(assetRepository, never()).findAll(
                any(Specification.class),
                any(Pageable.class)
        );
    }

    private static CodeAsset asset(
            String id,
            int ownerUserId,
            String name,
            long revision
    ) {
        CodeAsset asset = new CodeAsset();
        asset.setId(id);
        asset.setOwnerUserId(ownerUserId);
        asset.setName(name);
        asset.setTrainingProfile("CUSTOM_PYTHON");
        asset.setRowVersion(revision);
        asset.setCreatedAt(Instant.EPOCH);
        asset.setUpdatedAt(Instant.EPOCH);
        asset.setDeleted(false);
        return asset;
    }

    private static V2CodeAssetDto dto(String id, String name, long revision) {
        return new V2CodeAssetDto(
                id,
                name,
                "CUSTOM_PYTHON",
                "training",
                "python3.11",
                "src/train.py",
                "CUSTOM",
                null,
                revision,
                Instant.EPOCH,
                Instant.EPOCH,
                false,
                false
        );
    }
}
