package com.tss.platform.training.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingRunSpecArtifactSpecTest {

    @Test
    void keepsLegacyPlansOnTheirExistingCompatibilityPath() {
        assertThat(TrainingRunSpecFactory.resolveAcceptedSpec(
                null,
                "model.cv.hf-image/v1",
                "model"
        )).isNull();
    }

    @Test
    void acceptsOnlyTheExactServerVerifiedSpecification() {
        assertThat(TrainingRunSpecFactory.resolveAcceptedSpec(
                List.of("model.cv.hf-image/v1"),
                "model.cv.hf-image/v1",
                "model"
        )).isEqualTo("model.cv.hf-image/v1");

        assertThatThrownBy(() -> TrainingRunSpecFactory.resolveAcceptedSpec(
                List.of("model.cv.hf-image/v1"),
                "model.cv.yolo-weight/v1",
                "model"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model artifact specification is incompatible with selected plan");
    }

    @Test
    void rejectsUnverifiedAssetsAndInvalidEmptyPlanContracts() {
        assertThatThrownBy(() -> TrainingRunSpecFactory.resolveAcceptedSpec(
                List.of("dataset.cv.imagefolder/v1"),
                null,
                "dataset"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset version has no verified artifact specification");

        assertThatThrownBy(() -> TrainingRunSpecFactory.resolveAcceptedSpec(
                List.of(),
                "dataset.cv.imagefolder/v1",
                "dataset"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset acceptedSpecIds cannot be empty");
    }

    @Test
    void persistsSpecificationEvidenceInsideTheImmutableRunSpecInput() {
        TrainingRunSpec.InputArtifact artifact = new TrainingRunSpec.InputArtifact(
                "version-1",
                "models/example.zip",
                "a".repeat(64),
                1024L,
                "model.cv.hf-image/v1",
                "example.zip",
                true,
                List.of(),
                "model.cv.hf-image/v1"
        );

        assertThat(artifact.artifactSpecId()).isEqualTo("model.cv.hf-image/v1");
        assertThat(artifact.format()).isEqualTo("model.cv.hf-image/v1");
    }
}
