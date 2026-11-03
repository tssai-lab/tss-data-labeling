/** 读取回执必须区分空内容与错误；错误对象不能当成文件正文展示。 */
function requireSuccessfulRead(raw: unknown): void {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const row = raw as Record<string, unknown>;
    if (
      row.success === false ||
      row.errorCode ||
      (row.code !== undefined &&
        ![0, 200, '0', '200'].includes(row.code as number | string))
    ) {
      throw new Error('模型接口返回失败回执');
    }
  }
}

export function modelReadData(raw: unknown): unknown {
  requireSuccessfulRead(raw);
  const data = raw && typeof raw === 'object' && 'data' in raw ? raw.data : raw;
  requireSuccessfulRead(data);
  return data;
}

export function modelFiles(raw: unknown): API.ModelCodeFile[] {
  const data = modelReadData(raw);
  const files = Array.isArray(data)
    ? data
    : data && typeof data === 'object' && 'files' in data
      ? data.files
      : undefined;
  if (
    !Array.isArray(files) ||
    files.some(
      (file) => !file || typeof file.path !== 'string' || !file.path.trim(),
    )
  ) {
    throw new Error('模型文件列表回执不完整，请重试');
  }
  return files;
}

export function modelPreview(raw: unknown, path: string): API.ModelCodePreview {
  const data = modelReadData(raw);
  if (typeof data === 'string') return { path, content: data };
  if (
    !data ||
    typeof data !== 'object' ||
    !('content' in data) ||
    typeof data.content !== 'string'
  ) {
    throw new Error('模型文件内容回执不完整，请重试');
  }
  if ('path' in data && data.path && data.path !== path)
    throw new Error('模型文件回执路径不匹配');
  return { ...data, path, content: data.content };
}
