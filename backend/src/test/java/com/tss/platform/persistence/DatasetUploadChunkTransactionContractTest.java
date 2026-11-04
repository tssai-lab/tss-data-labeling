package com.tss.platform.persistence;

import com.tss.platform.repository.DatasetUploadChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatasetUploadChunkTransactionContractTest {

    @Test
    void chunkDeletionJoinsWorkspaceAbandonTransaction() throws Exception {
        Transactional transactional = DatasetUploadChunkRepository.class
                .getMethod("deleteByUploadId", String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRED, transactional.propagation());
    }
}
