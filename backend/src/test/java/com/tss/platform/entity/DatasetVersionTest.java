package com.tss.platform.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatasetVersionTest {

    @Test
    void activeDraftAssetIdIsMaintainedFromStatusAndDeletedFlag() {
        DatasetVersion version = new DatasetVersion();
        version.setAssetId("asset-1");
        version.setStatus("DRAFT");
        version.setDeleted(false);

        version.updateActiveDraftAssetId();

        assertEquals("asset-1", version.getActiveDraftAssetId());

        version.setStatus("READY");
        version.updateActiveDraftAssetId();

        assertNull(version.getActiveDraftAssetId());

        version.setStatus("DRAFT");
        version.setDeleted(true);
        version.updateActiveDraftAssetId();

        assertNull(version.getActiveDraftAssetId());
    }

    @Test
    void activeDraftAssetIdIsInternalOnly() throws Exception {
        DatasetVersion version = new DatasetVersion();
        version.setAssetId("asset-1");
        version.setStatus("DRAFT");
        version.setDeleted(false);
        version.setWorkspaceHeadVersionId("ready-1");
        version.updateActiveDraftAssetId();

        String json = new ObjectMapper().writeValueAsString(version);

        assertFalse(json.contains("activeDraftAssetId"));
        assertFalse(json.contains("workspaceHeadVersionId"));
    }
}
