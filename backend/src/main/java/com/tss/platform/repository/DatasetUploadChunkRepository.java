package com.tss.platform.repository;

import com.tss.platform.entity.DatasetUploadChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DatasetUploadChunkRepository extends JpaRepository<DatasetUploadChunk, String> {
    List<DatasetUploadChunk> findByUploadIdOrderByPartIndexAsc(String uploadId);

    @Query("""
            select count(c) as uploadedChunks,
                   coalesce(sum(c.sizeBytes), 0) as uploadedBytes
            from DatasetUploadChunk c
            where c.uploadId = :uploadId
            """)
    UploadChunkProgressSummary summarizeProgressByUploadId(@Param("uploadId") String uploadId);

    @Query("""
            select c.partIndex
            from DatasetUploadChunk c
            where c.uploadId = :uploadId
            order by c.partIndex asc
            """)
    List<Integer> findPartIndexesByUploadIdOrderByPartIndexAsc(@Param("uploadId") String uploadId);

    Optional<DatasetUploadChunk> findByUploadIdAndPartIndex(String uploadId, Integer partIndex);

    long countByUploadId(String uploadId);

    @Transactional
    void deleteByUploadId(String uploadId);
}
