package com.tss.platform.repository;

import com.tss.platform.entity.CodeValidationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CodeValidationRunRepository extends JpaRepository<CodeValidationRun, String> {

    Optional<CodeValidationRun> findTopByVersionIdOrderByCreatedAtDescIdDesc(String versionId);
}
