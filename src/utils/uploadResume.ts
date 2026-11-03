/** localStorage：模型分片上传续传 */
export const LS_MODEL_UPLOAD_FP = 'tss_onlyme_model_upload_fingerprint';
export const LS_MODEL_UPLOAD_ID = 'tss_onlyme_model_upload_id';

/** localStorage：数据集分片上传续传（单文件 zip 等） */
export const LS_DATASET_UPLOAD_FP = 'tss_onlyme_dataset_upload_fingerprint';
export const LS_DATASET_UPLOAD_ID = 'tss_onlyme_dataset_upload_id';

/**
 * 与 backend-api.md 一致：同文件 + 同名元数据应生成稳定指纹，便于 init 复用 uploadId。
 * 不要使用 Date.now()，否则每次 init 指纹变化无法续传。
 */
export function buildModelFileFingerprint(
  file: File,
  modelName: string,
  version: string,
  type: string,
): string {
  return [file.name, String(file.size), modelName, version, type].join('|');
}

export function buildDatasetFileFingerprint(
  file: File,
  datasetName: string,
  version: string,
  type: string,
  /** 变更标注格式须换指纹，否则会复用旧 upload 会话（仍按旧 annotationFormat 合并校验） */
  annotationFormat?: string,
  cvTaskType?: string,
): string {
  return [
    file.name,
    String(file.size),
    datasetName,
    version || 'v1',
    type,
    annotationFormat || '',
    cvTaskType || '',
  ].join('|');
}
