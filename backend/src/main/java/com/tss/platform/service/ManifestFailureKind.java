package com.tss.platform.service;

/** 稳定的导入失败类别，独立于可修改、可翻译的错误文案。 */
public enum ManifestFailureKind {
    INVALID_MANIFEST,
    DUPLICATE_SAMPLE,
    ANNOTATION_TARGET_NOT_FOUND,
    ANNOTATION_TARGET_AMBIGUOUS,
    UNSUPPORTED_SAMPLE_FILE,
    INVALID_SAMPLE_DIRECTORY
}
