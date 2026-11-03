/** 文件响应解析及 V2 错误描述。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { type V2CodeErrorBody, type V2CodeFileContent } from './v2Types';


export function extractV2FileText(payload: V2CodeFileContent | string): string {
  if (typeof payload === 'string') return payload;
  const response = payload as Record<string, unknown> | null;
  if (!response || Array.isArray(response) || response.errorCode || response.success === false
    || (response.code !== undefined && response.code !== 200)) throw new Error('代码文件响应异常');
  const content = response.content ?? response.text;
  if (typeof content !== 'string') throw new Error('代码文件内容响应格式异常');
  return content;
}

export async function errorMessageFromV2(error: any): Promise<string> {
  const data = error?.response?.data;
  if (data instanceof Blob) {
    return errorMessageFromV2Blob(error);
  }
  if (data && typeof data === 'object') {
    const body = data as V2CodeErrorBody;
    const reason =
      body.details && typeof body.details === 'object'
        ? (body.details as Record<string, unknown>).reasonCode
        : undefined;
    return (
      body.errorMessage ||
      (typeof reason === 'string' ? reason : undefined) ||
      body.errorCode ||
      error?.message ||
      '请求失败'
    );
  }
  return (
    error?.info?.errorMessage ||
    error?.data?.errorMessage ||
    error?.message ||
    '请求失败'
  );
}

export async function errorMessageFromV2Blob(error: any): Promise<string> {
  const data = error?.response?.data;
  if (data instanceof Blob) {
    try {
      const text = await data.text();
      const json = JSON.parse(text) as V2CodeErrorBody;
      return json?.errorMessage || json?.errorCode || text || '下载失败';
    } catch {
      return '下载失败或文件不存在';
    }
  }
  return (
    error?.info?.errorMessage ||
    error?.data?.errorMessage ||
    error?.message ||
    '请求失败'
  );
}
