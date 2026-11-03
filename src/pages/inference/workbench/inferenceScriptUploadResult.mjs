/**
 * Only a response carrying a persisted script version may close the upload
 * dialog and refresh/select the reusable script list.
 */
export function requireSavedInferenceScriptVersion(response) {
  if (response?.success === false) {
    throw new Error(response.errorMessage || '推理脚本上传失败');
  }
  const scriptVersionId =
    typeof response?.data?.scriptVersionId === 'string'
      ? response.data.scriptVersionId.trim()
      : '';
  if (!scriptVersionId) {
    throw new Error('推理脚本上传成功，但未返回可复用的版本编号');
  }
  return scriptVersionId;
}
