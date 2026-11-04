package com.tss.platform.asset.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactSpecRegistryTest {

    @Test
    void exposesOnlyVersionedReviewedSpecsAndNeverRegistersOtherAsTrustedSpec() {
        ArtifactSpecRegistry registry = new ArtifactSpecRegistry();

        assertTrue(registry.require("model.cv.yolo-weight/v1").canBeAcceptedByTrainingPlan());
        assertTrue(registry.require("dataset.cv.imagefolder/v1").canBeAcceptedByTrainingPlan());
        assertTrue(registry.require(
                "model.nlp.bert-sequence-classification/v1"
        ).canBeAcceptedByTrainingPlan());
        assertTrue(registry.require(
                "dataset.nlp.text-classification-jsonl/v1"
        ).canBeAcceptedByTrainingPlan());
        assertFalse(registry.require("dataset.pointcloud.ply-pcd/v1").canBeAcceptedByTrainingPlan());
        assertEquals(AssetDirectoryCategory.ROBOT,
                registry.require("dataset.robot.lerobot/v1").category());
        assertTrue(registry.list().stream()
                .noneMatch(spec -> spec.category() == AssetDirectoryCategory.OTHER));
    }

    @Test
    void supportsOtherAsDirectoryMetadataForBothAssetKinds() {
        assertTrue(AssetDirectoryCategory.OTHER.supports(AssetKind.MODEL));
        assertTrue(AssetDirectoryCategory.OTHER.supports(AssetKind.DATASET));
        assertFalse(AssetDirectoryCategory.MULTIMODAL.supports(AssetKind.MODEL));
    }

    @Test
    void rejectsDuplicateOrOtherTrustedDefinitions() {
        ArtifactSpecDefinition definition = new ArtifactSpecDefinition(
                "model.cv.test/v1",
                AssetKind.MODEL,
                AssetDirectoryCategory.CV,
                "test",
                ArtifactSpecDefinition.Capability.TRAINING_READY
        );
        assertThrows(IllegalStateException.class,
                () -> new ArtifactSpecRegistry(List.of(definition, definition)));

        ArtifactSpecDefinition other = new ArtifactSpecDefinition(
                "model.other.generic/v1",
                AssetKind.MODEL,
                AssetDirectoryCategory.OTHER,
                "generic",
                ArtifactSpecDefinition.Capability.TRAINING_READY
        );
        assertThrows(IllegalStateException.class,
                () -> new ArtifactSpecRegistry(List.of(other)));
    }
}
