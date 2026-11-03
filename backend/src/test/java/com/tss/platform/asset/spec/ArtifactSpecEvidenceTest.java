package com.tss.platform.asset.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArtifactSpecEvidenceTest {

    @Test
    void otherCategoryNeverSignsATrainingSpecification() {
        assertNull(ArtifactSpecEvidence.recognizeModelArchive(
                "OTHER",
                List.of("model.yaml", "config.json", "weights.safetensors")
        ));
        assertNull(ArtifactSpecEvidence.recognizeDatasetArchive(
                "OTHER",
                null,
                null,
                List.of("data/train/cat/one.jpg")
        ));
        assertNull(ArtifactSpecEvidence.recognizeSingleDataset("OTHER", "notes.yaml"));
    }

    @Test
    void recognizesOnlyCompleteUnambiguousModelContracts() {
        assertEquals(
                ArtifactSpecIds.MODEL_CV_HF_IMAGE,
                ArtifactSpecEvidence.recognizeModelArchive("CV", List.of(
                        "model.yaml",
                        "config.json",
                        "model.safetensors",
                        "preprocessor_config.json"
                ))
        );
        assertEquals(
                ArtifactSpecIds.MODEL_CV_YOLO_WEIGHT,
                ArtifactSpecEvidence.recognizeModelArchive("CV", List.of("yolo11n.pt"))
        );

        assertNull(ArtifactSpecEvidence.recognizeModelArchive(
                "CV",
                List.of("config.json", "model.safetensors")
        ));
        assertNull(ArtifactSpecEvidence.recognizeModelArchive(
                "CV",
                List.of("model.yaml", "config.json", "model.bin", "yolo11n.pt")
        ));
        assertNull(ArtifactSpecEvidence.recognizeModelArchive(
                "NLP",
                List.of("model.yaml", "config.json", "model.safetensors")
        ));
        assertEquals(
                ArtifactSpecIds.MODEL_CV_YOLO_WEIGHT,
                ArtifactSpecEvidence.recognizeSingleModel("CV", "models/yolo11n.pt")
        );
        assertNull(ArtifactSpecEvidence.recognizeSingleModel("CV", "models/best.pt"));
        assertNull(ArtifactSpecEvidence.recognizeSingleModel("NLP", "yolo11n.pt"));

        assertEquals(
                ArtifactSpecIds.MODEL_NLP_BERT_SEQUENCE_CLASSIFICATION,
                ArtifactSpecEvidence.recognizeModelArchive("NLP", List.of(
                        "model.yaml",
                        "config.json",
                        "pytorch_model.bin",
                        "vocab.txt"
                ))
        );
        assertNull(ArtifactSpecEvidence.recognizeModelArchive(
                "NLP",
                List.of("config.json", "pytorch_model.bin", "vocab.txt")
        ));
    }

    @Test
    void requiresTheExactImageFolderTrainingSplits() {
        List<String> complete = List.of(
                "data/train/cats/one.jpg",
                "data/validation/cats/two.jpg",
                "data/test/cats/three.jpg"
        );
        assertEquals(
                ArtifactSpecIds.DATASET_CV_IMAGE_FOLDER,
                ArtifactSpecEvidence.recognizeDatasetArchive(
                        "CV",
                        "IMAGE_CLASSIFICATION",
                        "FOLDER_CLASSIFICATION",
                        complete
                )
        );
        assertNull(ArtifactSpecEvidence.recognizeDatasetArchive(
                "CV",
                "IMAGE_CLASSIFICATION",
                "FOLDER_CLASSIFICATION",
                complete.subList(0, 2)
        ));
        assertNull(ArtifactSpecEvidence.recognizeDatasetArchive(
                "CV",
                "UNLABELED",
                "FOLDER_CLASSIFICATION",
                complete
        ));
    }

    @Test
    void recognizesYoloOnlyWithManifestAndMatchingContentShape() {
        List<String> complete = List.of(
                "data.yaml",
                "images/train/one.jpg",
                "labels/train/one.txt"
        );
        assertEquals(
                ArtifactSpecIds.DATASET_CV_YOLO,
                ArtifactSpecEvidence.recognizeDatasetArchive(
                        "CV",
                        "OBJECT_DETECTION",
                        "YOLO",
                        complete
                )
        );
        assertNull(ArtifactSpecEvidence.recognizeDatasetArchive(
                "CV",
                "OBJECT_DETECTION",
                "YOLO",
                complete.subList(1, 3)
        ));
    }

    @Test
    void storageOnlyEvidenceDoesNotDependOnATrainingPlan() {
        assertEquals(
                ArtifactSpecIds.DATASET_POINT_CLOUD_PLY_PCD,
                ArtifactSpecEvidence.recognizeSingleDataset("POINT_CLOUD", "sample.ply")
        );
        assertEquals(
                ArtifactSpecIds.DATASET_NLP_DOCUMENTS,
                ArtifactSpecEvidence.recognizeSingleDataset("NLP", "corpus.jsonl")
        );
        assertNull(ArtifactSpecEvidence.recognizeSingleDataset("CV", "image.jpg"));
    }

    @Test
    void textClassificationRequiresAllThreeJsonlSplitsAndManifest() {
        List<String> complete = List.of(
                "dataset.json",
                "data/train.jsonl",
                "data/validation.jsonl",
                "data/test.jsonl"
        );
        assertEquals(
                ArtifactSpecIds.DATASET_NLP_TEXT_CLASSIFICATION_JSONL,
                ArtifactSpecEvidence.recognizeDatasetArchive(
                        "NLP", null, null, complete
                )
        );
        assertEquals(
                ArtifactSpecIds.DATASET_NLP_DOCUMENTS,
                ArtifactSpecEvidence.recognizeDatasetArchive(
                        "NLP", null, null, complete.subList(0, 3)
                )
        );
        assertNull(ArtifactSpecEvidence.recognizeDatasetArchive(
                "OTHER", null, null, complete
        ));
    }

    @Test
    void recognizesLeRobotWithOrWithoutOneArchiveWrapperDirectory() {
        List<String> root = List.of(
                "meta/info.json",
                "meta/episodes/chunk-000/episode_000000.parquet",
                "data/chunk-000/file-000.parquet",
                "videos/chunk-000/camera.mp4"
        );
        assertEquals(
                ArtifactSpecIds.DATASET_ROBOT_LEROBOT,
                ArtifactSpecEvidence.recognizeDatasetArchive("LEROBOT", null, null, root)
        );
        assertEquals(
                ArtifactSpecIds.DATASET_ROBOT_LEROBOT,
                ArtifactSpecEvidence.recognizeDatasetArchive(
                        "LEROBOT",
                        null,
                        null,
                        root.stream().map(path -> "dataset/" + path).toList()
                )
        );
    }
}
