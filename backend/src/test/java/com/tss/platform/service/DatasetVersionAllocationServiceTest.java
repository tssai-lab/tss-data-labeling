package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetVersionAllocationServiceTest {

    private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
    private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final DatasetVersionAllocationService service =
            new DatasetVersionAllocationService(assetRepo, versionRepo, authContext);

    @Test
    void missingTargetAssetUsesHiddenUploadAccessContract() {
        when(assetRepo.findByIdAndDeletedFalse("missing-asset")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveTargetAsset("missing-asset", "NLP", null, null)
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void forbiddenTargetAssetUsesSameHiddenUploadAccessContract() {
        DatasetAsset asset = asset("asset-1", "NLP");
        when(assetRepo.findByIdAndDeletedFalse("asset-1")).thenReturn(Optional.of(asset));
        when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveTargetAsset("asset-1", "NLP", null, null)
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void missingParentVersionUsesHiddenUploadAccessContract() {
        DatasetAsset asset = asset("asset-1", "NLP");
        when(versionRepo.findByIdAndDeletedFalse("missing-parent"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveParentVersionId("missing-parent", asset)
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void parentVersionFromAnotherAssetUsesHiddenUploadAccessContract() {
        DatasetAsset asset = asset("asset-1", "NLP");
        DatasetVersion parent = version("parent-1", "asset-2", "READY");
        when(versionRepo.findByIdAndDeletedFalse("parent-1"))
                .thenReturn(Optional.of(parent));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveParentVersionId("parent-1", asset)
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void nonReadyParentVersionUsesHiddenUploadAccessContract() {
        DatasetAsset asset = asset("asset-1", "NLP");
        DatasetVersion parent = version("parent-1", "asset-1", "DRAFT");
        when(versionRepo.findByIdAndDeletedFalse("parent-1"))
                .thenReturn(Optional.of(parent));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveParentVersionId("parent-1", asset)
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void targetAssetDisappearingBeforeAllocationUsesHiddenUploadAccessContract() {
        when(assetRepo.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.allocateVersion("asset-1", false, "v2", "parent-1")
        );

        assertAllocationAccessContract(error);
    }

    @Test
    void targetAssetMetadataMismatchRemainsOrdinaryValidationFailure() {
        DatasetAsset asset = asset("asset-1", "CV");
        when(assetRepo.findByIdAndDeletedFalse("asset-1")).thenReturn(Optional.of(asset));
        when(authContext.canAccessOwner(asset.getOwnerUserId())).thenReturn(true);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveTargetAsset("asset-1", "NLP", null, null)
        );

        assertEquals(IllegalArgumentException.class, error.getClass());
        assertEquals("dataset asset type mismatch", error.getMessage());
    }

    @Test
    void parentVersionForNewAssetRemainsOrdinaryValidationFailure() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveParentVersionId("parent-1", null)
        );

        assertEquals(IllegalArgumentException.class, error.getClass());
        assertEquals(
                "parentVersionId is not allowed when creating a new dataset asset",
                error.getMessage()
        );
    }

    private static DatasetAsset asset(String id, String type) {
        DatasetAsset asset = new DatasetAsset();
        asset.setId(id);
        asset.setOwnerUserId(7);
        asset.setType(type);
        return asset;
    }

    private static DatasetVersion version(String id, String assetId, String status) {
        DatasetVersion version = new DatasetVersion();
        version.setId(id);
        version.setAssetId(assetId);
        version.setStatus(status);
        return version;
    }

    private static void assertAllocationAccessContract(IllegalArgumentException error) {
        assertEquals("DatasetAllocationAccessException", error.getClass().getSimpleName());
        assertEquals(
                "dataset asset or parent version not found or no permission",
                error.getMessage()
        );
    }
}
