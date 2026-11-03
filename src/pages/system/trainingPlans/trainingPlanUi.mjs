export const MAX_TRAINING_PLAN_YAML_BYTES = 256 * 1024;

export function validateTrainingPlanYamlFile(file) {
  if (!file) return '请选择 YAML 文件';
  if (!/\.ya?ml$/i.test(String(file.name || ''))) {
    return '只支持 .yaml 或 .yml 文件';
  }
  if (!Number.isFinite(file.size) || file.size <= 0) {
    return 'YAML 文件不能为空';
  }
  if (file.size > MAX_TRAINING_PLAN_YAML_BYTES) {
    return 'YAML 文件不能超过 256 KiB';
  }
  return undefined;
}

export function canPublishTrainingPlan(file, preview) {
  return Boolean(
    file &&
      preview?.publishable &&
      preview?.definition &&
      /^[a-f0-9]{64}$/i.test(String(preview?.sha256 || '')),
  );
}

export function getTrainingPlanRequestError(error, fallback) {
  const data = error?.response?.data;
  const status = error?.response?.status ?? error?.status;
  if (status === 401) return '登录已失效，请重新登录';
  if (status === 403) return '仅超级管理员可以执行此操作';
  return (
    data?.errorMessage ||
    data?.message ||
    error?.message ||
    fallback ||
    '请求失败'
  );
}
