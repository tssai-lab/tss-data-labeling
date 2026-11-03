package com.tss.platform.repository;

import com.tss.platform.entity.DatasetPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatasetPackageRepository extends JpaRepository<DatasetPackage, String> {
    Optional<DatasetPackage> findByIdAndDeletedFalse(String id);

    Optional<DatasetPackage> findByStoragePathAndDeletedFalse(String storagePath);
}
