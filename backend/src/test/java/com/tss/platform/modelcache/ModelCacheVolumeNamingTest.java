package com.tss.platform.modelcache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCacheVolumeNamingTest {

    @Test
    void createsStableDnsNameForPhysicalNode() {
        assertEquals(
                "tss-model-cache-k8s-master",
                ModelCacheVolumeNaming.claimNameForNode("k8s-master")
        );
        assertTrue(ModelCacheVolumeNaming.claimNameForNode("worker-1.example")
                .startsWith("tss-model-cache-worker-1-example-"));
    }

    @Test
    void longNamesRemainBoundedAndCollisionResistant() {
        String first = ModelCacheVolumeNaming.claimNameForNode("a".repeat(62) + "1");
        String second = ModelCacheVolumeNaming.claimNameForNode("a".repeat(62) + "2");

        assertTrue(first.length() <= 63);
        assertTrue(second.length() <= 63);
        assertNotEquals(first, second);
    }

    @Test
    void normalizedNamesCannotCollideWithLiteralHyphenNames() {
        assertNotEquals(
                ModelCacheVolumeNaming.claimNameForNode("worker.1"),
                ModelCacheVolumeNaming.claimNameForNode("worker-1")
        );
    }

    @Test
    void missingNodeIsRejectedBeforeManifestSubmission() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelCacheVolumeNaming.claimNameForNode(" ")
        );
    }
}
