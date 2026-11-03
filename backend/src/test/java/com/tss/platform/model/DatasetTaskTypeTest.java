package com.tss.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetTaskTypeTest {

    @Test
    void normalizesAllDatasetTypesIncludingMultimodal() {
        assertEquals("CV", DatasetTaskType.normalize("cv"));
        assertEquals("NLP", DatasetTaskType.normalize(" nlp "));
        assertEquals("POINT_CLOUD", DatasetTaskType.normalize("point_cloud"));
        assertEquals("ROBOT", DatasetTaskType.normalize("robot"));
        assertEquals("LEROBOT", DatasetTaskType.normalize("lerobot"));
        assertEquals("MULTIMODAL", DatasetTaskType.normalize("multimodal"));
        assertEquals("OTHER", DatasetTaskType.normalize(" other "));
    }

    @Test
    void rejectsUnsupportedDatasetType() {
        assertThrows(IllegalArgumentException.class, () -> DatasetTaskType.normalize("VIDEO"));
    }
}
