package com.tss.platform.repository;

import com.tss.platform.entity.ModelUploadChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ModelUploadChunkRepository extends JpaRepository<ModelUploadChunk, String> {
    List<ModelUploadChunk> findByUploadIdOrderByPartIndexAsc(String uploadId);

    @Query("""
            select count(c) as uploadedChunks,
                   coalesce(sum(c.sizeBytes), 0) as uploadedBytes
            from ModelUploadChunk c
            where c.uploadId = :uploadId
            """)
    UploadChunkProgressSummary summarizeProgressByUploadId(@Param("uploadId") String uploadId);

    @Query("""
            select c.partIndex
            from ModelUploadChunk c
            where c.uploadId = :uploadId
            order by c.partIndex asc
            """)
    List<Integer> findPartIndexesByUploadIdOrderByPartIndexAsc(@Param("uploadId") String uploadId);

    Optional<ModelUploadChunk> findByUploadIdAndPartIndex(String uploadId, Integer partIndex);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deleteByUploadId(String uploadId);
}
