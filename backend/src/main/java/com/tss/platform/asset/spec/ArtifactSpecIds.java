package com.tss.platform.asset.spec;

/**
 * Stable IDs for server-reviewed artifact contracts.
 *
 * <p>Keep these values in code: clients may request directory categories, but
 * only server-side validation is allowed to assign a trusted spec ID.</p>
 */
public final class ArtifactSpecIds {

    public static final String MODEL_CV_YOLO_WEIGHT = "model.cv.yolo-weight/v1";
    public static final String MODEL_CV_HF_IMAGE = "model.cv.hf-image/v1";
    public static final String MODEL_NLP_PACKAGE = "model.nlp.package/v1";
    public static final String MODEL_NLP_BERT_SEQUENCE_CLASSIFICATION =
            "model.nlp.bert-sequence-classification/v1";

    public static final String DATASET_CV_IMAGE_FOLDER = "dataset.cv.imagefolder/v1";
    public static final String DATASET_CV_YOLO = "dataset.cv.yolo/v1";
    public static final String DATASET_CV_UNLABELED_IMAGES = "dataset.cv.unlabeled-images/v1";
    public static final String DATASET_NLP_DOCUMENTS = "dataset.nlp.documents/v1";
    public static final String DATASET_NLP_TEXT_CLASSIFICATION_JSONL =
            "dataset.nlp.text-classification-jsonl/v1";
    public static final String DATASET_POINT_CLOUD_PLY_PCD = "dataset.pointcloud.ply-pcd/v1";
    public static final String DATASET_ROBOT_CONFIG = "dataset.robot.config-xml-yaml/v1";
    public static final String DATASET_ROBOT_LEROBOT = "dataset.robot.lerobot/v1";
    public static final String DATASET_MULTIMODAL_DIRECTORY = "dataset.multimodal.directory/v1";
    public static final String DATASET_MULTIMODAL_MANIFEST = "dataset.multimodal.manifest/v1";

    private ArtifactSpecIds() {
    }
}
