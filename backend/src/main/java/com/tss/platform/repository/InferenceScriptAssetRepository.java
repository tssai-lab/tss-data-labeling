package com.tss.platform.repository;

import com.tss.platform.entity.InferenceScriptAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InferenceScriptAssetRepository extends JpaRepository<InferenceScriptAsset, String> {
    Optional<InferenceScriptAsset> findByIdAndDeletedFalse(String id);

    List<InferenceScriptAsset> findByOwnerUserIdAndDeletedFalse(Integer ownerUserId);
}
