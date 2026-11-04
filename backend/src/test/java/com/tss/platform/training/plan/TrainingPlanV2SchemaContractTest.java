package com.tss.platform.training.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingPlanV2SchemaContractTest {

    @Test
    void schemaDeclaresV2SpecBasedSingleGpuContractWithoutLegacyMatchingFields() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/training-plans/schema/training-plan-v2.schema.json"
        )) {
            assertNotNull(input);
            JsonNode schema = new ObjectMapper().readTree(input);

            assertEquals("tss.training.plan/v2",
                    schema.at("/properties/schemaVersion/const").asText());
            assertTrue(schema.at("/properties/category/enum").toString().contains("OTHER"));
            assertTrue(schema.at("/$defs/runtime/properties/deviceType/enum").toString()
                    .contains("NVIDIA_GPU"));
            assertEquals(1,
                    schema.at("/$defs/resourceProfile/properties/gpuCount/maximum").asInt());
            assertTrue(schema.at("/$defs/modelInput/required").toString().contains("acceptedSpecIds"));
            assertTrue(schema.at("/$defs/datasetInput/required").toString().contains("acceptedSpecIds"));
            assertFalse(schema.at("/$defs/modelInput/properties").has("taskTypes"));
            assertFalse(schema.at("/$defs/datasetInput/properties").has("annotationFormats"));
        }
    }
}
