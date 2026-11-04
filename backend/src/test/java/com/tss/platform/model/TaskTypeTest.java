package com.tss.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskTypeTest {

    @Test
    void normalizesLegacyAndOtherModelCategories() {
        assertEquals("CV", TaskType.normalize("cv"));
        assertEquals("NLP", TaskType.normalize(" nlp "));
        assertEquals("POINT_CLOUD", TaskType.normalize("point_cloud"));
        assertEquals("ROBOT", TaskType.normalize("robot"));
        assertEquals("OTHER", TaskType.normalize("other"));
    }

    @Test
    void rejectsUnsupportedModelCategory() {
        assertThrows(IllegalArgumentException.class, () -> TaskType.normalize("VIDEO"));
    }
}
