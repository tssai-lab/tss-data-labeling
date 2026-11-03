package com.tss.platform.repository;

import com.tss.platform.entity.InferenceScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InferenceScriptVersionRepository extends JpaRepository<InferenceScriptVersion, String> {
    Optional<InferenceScriptVersion> findByIdAndDeletedFalse(String id);

    List<InferenceScriptVersion> findByDeletedFalseOrderByCreatedAtDesc();

    List<InferenceScriptVersion> findByOwnerUserIdAndDeletedFalseOrderByCreatedAtDesc(Integer ownerUserId);

    List<InferenceScriptVersion> findByOwnerUserIdInAndDeletedFalseOrderByCreatedAtDesc(
            Collection<Integer> ownerUserIds
    );

    List<InferenceScriptVersion> findByAssetIdInAndDeletedFalseOrderByCreatedAtDesc(Collection<String> assetIds);

    boolean existsByAssetIdAndVersion(String assetId, String version);

    long countByAssetIdAndDeletedFalse(String assetId);
}
