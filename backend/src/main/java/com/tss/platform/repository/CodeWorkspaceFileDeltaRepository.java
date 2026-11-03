package com.tss.platform.repository;

import com.tss.platform.entity.CodeWorkspaceFileDelta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CodeWorkspaceFileDeltaRepository
        extends JpaRepository<CodeWorkspaceFileDelta, String> {

    interface DeltaMetadata {

        String getPath();

        String getOperation();

        Long getSizeBytes();

        String getContentHash();
    }

    @Query("""
            select d.path as path,
                   d.operation as operation,
                   d.sizeBytes as sizeBytes,
                   d.contentHash as contentHash
              from CodeWorkspaceFileDelta d
             where d.workspaceId = :workspaceId
             order by d.path asc
            """)
    List<DeltaMetadata> findMetadataByWorkspaceIdOrderByPathAsc(
            @Param("workspaceId") String workspaceId
    );

    @Query("""
            select d.path as path,
                   d.operation as operation,
                   d.sizeBytes as sizeBytes,
                   d.contentHash as contentHash
              from CodeWorkspaceFileDelta d
             where d.workspaceId = :workspaceId
               and d.path like concat(:pathPrefix, '%') escape '!'
             order by d.path asc
            """)
    List<DeltaMetadata> findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
            @Param("workspaceId") String workspaceId,
            @Param("pathPrefix") String pathPrefix
    );

    Optional<CodeWorkspaceFileDelta> findByWorkspaceIdAndPath(String workspaceId, String path);

    List<CodeWorkspaceFileDelta> findByWorkspaceIdOrderByPathAsc(String workspaceId);
}
