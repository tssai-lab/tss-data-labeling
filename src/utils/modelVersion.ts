import {
  parseAssetVersion,
  suggestNextDatasetVersion,
  validateDatasetVersionUnique,
  validateVersionGreaterThanLatest,
} from './datasetVersion';

export const MODEL_VERSION_FORMAT_HINT = '格式：v1 或 vX.Y.Z（如 v1.0.0）';

export function validateModelVersionFormat(version: string): string | null {
  const trimmed = version?.trim();
  if (!trimmed) return '请输入版本号';
  if (!parseAssetVersion(trimmed)) {
    return MODEL_VERSION_FORMAT_HINT;
  }
  return null;
}

/** 上传模型新版本时的版本号校验 */
export function modelNewVersionFormRules(existingVersions: string[] = []) {
  return [
    { required: true, message: '请输入版本号' },
    {
      validator: (_: unknown, value: string) => {
        const formatError = validateModelVersionFormat(value);
        if (formatError) return Promise.reject(new Error(formatError));
        const greaterError = validateVersionGreaterThanLatest(
          value,
          existingVersions,
        );
        if (greaterError) return Promise.reject(new Error(greaterError));
        const uniqueError = validateDatasetVersionUnique(
          value,
          existingVersions,
        );
        if (uniqueError) return Promise.reject(new Error(uniqueError));
        return Promise.resolve();
      },
    },
  ];
}

export const suggestNextModelVersion = suggestNextDatasetVersion;
