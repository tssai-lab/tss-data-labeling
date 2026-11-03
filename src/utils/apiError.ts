/** 从 Umi request / 后端 ApiResponse / V2ErrorResponse 抛出的错误上取可读文案 */

const WORKSPACE_ERROR_HINTS: Record<string, string> = {
  WORKSPACE_BASE_CONFLICT:
    '当前资产已有活动工作区，且基线与所选版本不同。请先「发布为新版本」或「放弃工作区」后，再基于所选正式版重新创建。',
  BASE_VERSION_STALE:
    '工作区创建时的当前版本指针已变化（BASE_VERSION_STALE）。请放弃当前工作区后，基于最新正式版或目标历史版重新创建再发布。',
  BASE_VERSION_NOT_READY:
    '所选基线版本不是 READY，无法作为工作区基线。请选择已就绪的正式版本。',
  INVALID_BASE_VERSION_ID:
    '基线版本 ID 无效或为空，请从版本列表重新选择后再创建工作区。',
  DATASET_VERSION_LABEL_CONFLICT:
    '版本标签已被占用（含软删历史）。请更换标签（如 v3、v1.0.3）后重试。',
  DATASET_NOT_PUBLISHABLE:
    '当前工作区尚未达到可发布条件。请根据页面阻断项补齐样本/上传/导入后再发布。',
  WORKSPACE_BUSY: '工作区正在上传或导入中，请等待完成或取消后再操作。',
  WORKSPACE_REVISION_CONFLICT:
    '工作区已被他人或其它标签页更新（修订冲突）。请刷新页面后重试。',
  DATASET_NOT_FOUND: '数据集或基线版本不存在、已删除，或无权访问。',
  INVALID_VERSION_LABEL: '版本标签不合法（需 1～64 个非空白字符）。',
  MODEL_ARTIFACT_INVALID:
    '模型制品校验失败（长度、SHA-256 或 ZIP 结构异常）。该版本可能已降为 DRAFT，请重新上传或联系管理员。',
  MODEL_STORAGE_UNAVAILABLE:
    '模型存储暂不可用，请稍后重试；当前版本状态不会被修改。',
  CODE_APPROVAL_FORBIDDEN: '缺少代码审批或管理员权限。',
  CODE_ASSET_NOT_FOUND: '代码版本不存在、已删除，或无权访问。',
  CODE_ASSET_CONFLICT: '代码资产状态冲突，请刷新后重试。',
  CODE_VALIDATION_FAILED: '代码校验或审批证据校验失败。',
  CODE_STORAGE_UNAVAILABLE: '代码制品存储暂不可用，请稍后重试。',
  APPROVAL_EXPECTATION_REQUIRED:
    '审批请求缺少风险/校验证据（expected* 字段）。请等待风险扫描 COMPLETED 后再审核。',
  APPROVAL_EXPECTATION_INVALID: '审批证据格式无效，请刷新页面后重试。',
  APPROVAL_EVIDENCE_STALE:
    '审批证据已变化（可能刚完成重扫或制品升级）。请刷新待审列表后重新操作。',
  APPROVAL_EVIDENCE_MISSING: '缺少审批证据，无法完成审核。',
  RISK_EVIDENCE_STALE: '风险证据已过期，请刷新后重试或触发重扫。',
};

function pickErrorCode(err: any, data: any): string | undefined {
  const raw =
    data?.errorCode ||
    data?.code ||
    err?.info?.errorCode ||
    err?.info?.code ||
    data?.details?.reasonCode ||
    err?.info?.details?.reasonCode;
  if (typeof raw !== 'string') return undefined;
  const trimmed = raw.trim();
  return trimmed || undefined;
}

function pickBizMessage(err: any, data: any): string | undefined {
  const msg =
    err?.info?.errorMessage ||
    data?.errorMessage ||
    data?.message ||
    err?.info?.message;
  if (typeof msg !== 'string') return undefined;
  const trimmed = msg.trim();
  return trimmed || undefined;
}

/** 用户可见文案里不要带回资产/版本 ID（越权 404 时后端常把 ID 拼进 message） */
const RESOURCE_ID_IN_TEXT =
  /\b(?:model|dataset|code)[-_](?:asset|ver|version)[-_][a-zA-Z0-9]+\b/gi;

export function stripResourceIdsFromUserText(text: string): string {
  return text
    .replace(RESOURCE_ID_IN_TEXT, '')
    .replace(/\s*[:：]\s*/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function mapNotFoundOrForbiddenMessage(raw: string): string | undefined {
  if (/not found or no permission/i.test(raw)) {
    return '未找到该资源，或不属于当前账号。';
  }
  return undefined;
}

/** 将 MANIFEST / 工作区 userError.details 格式化为短文案 */
export function formatUserErrorDetails(details: unknown): string | undefined {
  if (details == null) return undefined;
  if (typeof details === 'string') {
    const t = details.trim();
    return t || undefined;
  }
  if (typeof details !== 'object') return String(details);

  const obj = details as Record<string, unknown>;
  const parts: string[] = [];
  if (obj.field != null && String(obj.field).trim()) {
    parts.push(`字段：${String(obj.field)}`);
  }
  if (obj.reason != null && String(obj.reason).trim()) {
    parts.push(`原因：${String(obj.reason)}`);
  }
  if (obj.path != null && String(obj.path).trim()) {
    parts.push(`路径：${String(obj.path)}`);
  }
  if (obj.externalId != null && String(obj.externalId).trim()) {
    parts.push(`样本：${String(obj.externalId)}`);
  }
  if (obj.sampleIndex != null && String(obj.sampleIndex).trim() !== '') {
    parts.push(`样本序号：${String(obj.sampleIndex)}`);
  }
  if (obj.line != null || obj.column != null) {
    parts.push(`位置：行 ${obj.line ?? '-'} 列 ${obj.column ?? '-'}`);
  }
  if (obj.activeBaseVersionId != null || obj.requestedBaseVersionId != null) {
    parts.push(
      `活动基线：${obj.activeBaseVersionId ?? '-'}；请求基线：${obj.requestedBaseVersionId ?? '-'}`,
    );
  }
  if (parts.length) return parts.join('；');

  try {
    return JSON.stringify(details);
  } catch {
    return undefined;
  }
}

export function getApiErrorMessage(err: any, fallback = '请求失败'): string {
  const status = err?.response?.status;
  const data = err?.response?.data;
  const errorCode = pickErrorCode(err, data);
  const reasonCodeRaw =
    data?.details?.reasonCode ??
    err?.info?.details?.reasonCode ??
    err?.reasonCode;
  const reasonCode =
    typeof reasonCodeRaw === 'string' && reasonCodeRaw.trim()
      ? reasonCodeRaw.trim()
      : undefined;
  const bizMessage = pickBizMessage(err, data);
  const detailsText = formatUserErrorDetails(
    data?.details ?? err?.info?.details,
  );

  // 优先用更细的 reasonCode（如 CODE_ASSET_CONFLICT + WORKSPACE_BASE_CONFLICT）
  const hintCode =
    (reasonCode && WORKSPACE_ERROR_HINTS[reasonCode]
      ? reasonCode
      : undefined) ||
    (errorCode && WORKSPACE_ERROR_HINTS[errorCode] ? errorCode : undefined);

  if (hintCode && WORKSPACE_ERROR_HINTS[hintCode]) {
    const hint = WORKSPACE_ERROR_HINTS[hintCode];
    if (detailsText && !hint.includes(detailsText)) {
      return `${hint}（${detailsText}）`;
    }
    return hint;
  }

  // 前端主动抛出的基线冲突（无 HTTP response）
  if (
    typeof err?.message === 'string' &&
    err.message.includes('已有基于其他版本的编辑工作区')
  ) {
    return err.message;
  }

  if (bizMessage) {
    const mapped = mapNotFoundOrForbiddenMessage(bizMessage);
    if (mapped) return mapped;
    const safeMessage = stripResourceIdsFromUserText(bizMessage);
    if (detailsText && !safeMessage.includes(detailsText)) {
      return `${safeMessage}（${stripResourceIdsFromUserText(detailsText)}）`;
    }
    return safeMessage;
  }

  if (status === 404) {
    const baseURL = (err?.config?.baseURL as string | undefined) || '';
    const url = (err?.config?.url as string | undefined) || '';
    const fullPath =
      url.startsWith('http') || url.startsWith('/api')
        ? url
        : `${baseURL.replace(/\/$/, '')}${url.startsWith('/') ? url : `/${url}`}`;
    const springPath = data?.path as string | undefined;
    const isDatasetPreview404 = (springPath || fullPath || url || '').includes(
      '/dataset/preview',
    );

    if (isDatasetPreview404) {
      return (
        `当前后端未提供数据集预览接口 (404)：${springPath || fullPath || url}。` +
        '前端路径与版本 ID 无误；需在模块二服务中部署 module2-api-doc §13（/api/dataset/preview/files、/content、/image）。' +
        '请让后端升级你正在使用的实例（例如 :8002），或把代理指向已含预览功能的模块二版本。'
      );
    }

    return (
      `接口或资源不存在 (404)：${springPath || fullPath || url || '未知路径'}。` +
      '请确认请求路径、版本 ID 及后端是否已发布对应接口。'
    );
  }

  if (status === 502 || status === 503 || status === 504) {
    return `后端服务不可用 (${status})，请稍后重试或检查代理目标地址。`;
  }

  return err?.message || fallback;
}
