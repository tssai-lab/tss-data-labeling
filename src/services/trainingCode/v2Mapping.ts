/** V2/legacy 展示结构适配。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import {
  type V2AdminCodeAsset,
  type V2AdminCodeAssetPage,
  type V2AdminCodeReviewTask,
  type V2AdminCodeReviewTaskDetail,
  type V2AdminCodeReviewTaskPage,
  type V2CodeApprovalRequest,
  type V2CodeApprovalStatus,
  type V2CodeVersion,
} from './v2Types';


/** 归一化审批状态；决策动词也映射到状态枚举 */
export function normalizeV2ApprovalStatus(
  status?: string | null,
): V2CodeApprovalStatus | undefined {
  const value = String(status || '')
    .trim()
    .toUpperCase();
  if (!value) return undefined;
  if (value === 'APPROVE') return 'APPROVED';
  if (value === 'REJECT') return 'REJECTED';
  if (value === 'REVOKE') return 'REVOKED';
  if (
    value === 'PENDING' ||
    value === 'APPROVED' ||
    value === 'REJECTED' ||
    value === 'REVOKED'
  ) {
    return value;
  }
  return undefined;
}

/** 后端自动生成的内部资产名，如 code-version-061ba3fad1774d8e8634b6cb303fed8c */
export function isInternalGeneratedCodeAssetName(name?: string): boolean {
  const value = name?.trim();
  if (!value) return false;
  return /^code-(?:version|asset)-[a-f0-9-]{8,}$/i.test(value);
}

function pickUserCodeName(detail: V2CodeVersion): string | undefined {
  const candidates = [detail.codeName, detail.name, detail.codeAssetName];
  for (const candidate of candidates) {
    const value = candidate?.trim();
    if (value && !isInternalGeneratedCodeAssetName(value)) {
      return value;
    }
  }
  return undefined;
}

export function mapV2CodeVersionToLegacy(detail: V2CodeVersion) {
  const versionId =
    detail.versionId || detail.codeVersionId || detail.id || '';
  const userCodeName = pickUserCodeName(detail);
  const fallbackAssetName =
    detail.codeAssetName || detail.assetName || detail.name || '';
  return {
    codeVersionId: versionId,
    codeAssetId: detail.assetId || detail.codeAssetId || '',
    codeName: userCodeName,
    codeAssetName: userCodeName || fallbackAssetName,
    version: detail.versionLabel || detail.version || '',
    fileName: detail.fileName || '',
    trainingProfile: detail.trainingProfile || '',
    approvalStatus: normalizeV2ApprovalStatus(detail.approvalStatus) || '',
    status: detail.status || '',
    sizeBytes: detail.sizeBytes,
    remark: detail.remark,
    createdAt: detail.createdAt || detail.publishedAt,
    submittedAt: detail.createdAt || detail.publishedAt,
    artifactSha256: detail.artifactSha256,
    entryScript: detail.entryScript,
    validationStatus: detail.validationStatus,
    riskAssessmentId: detail.riskAssessmentId,
    riskStatus: detail.riskStatus,
    riskLevel: detail.riskLevel,
    reviewDisposition: detail.reviewDisposition,
    riskPolicyVersion: detail.riskPolicyVersion,
    validationPolicyVersion: detail.validationPolicyVersion,
  };
}

function firstNonEmptyName(...values: Array<string | undefined>): string {
  for (const value of values) {
    const text = value?.trim();
    if (text && !isInternalGeneratedCodeAssetName(text)) return text;
  }
  for (const value of values) {
    const text = value?.trim();
    if (text) return text;
  }
  return '';
}

function pickReviewTaskVersionId(task: V2AdminCodeReviewTask): string {
  return (
    task.versionId?.trim() ||
    task.codeVersionId?.trim() ||
    task.id?.trim() ||
    ''
  );
}

/** 归一化审核队列分页（兼容 {items} / {data:{items}} / 数组） */
export function normalizeAdminReviewTaskPage(payload?: unknown, strict = false): {
  items: V2AdminCodeReviewTask[];
  totalElements: number;
} {
  const visit = (raw: unknown, depth = 0): V2AdminCodeReviewTaskPage | null => {
    if (Array.isArray(raw)) {
      return { items: raw as V2AdminCodeReviewTask[], totalElements: raw.length };
    }
    if (!raw || typeof raw !== 'object' || depth > 3) return null;
    const obj = raw as Record<string, unknown>;
    if (strict && (obj.errorCode || obj.success === false || (obj.code !== undefined && obj.code !== 200))) {
      throw new Error('管理员待审核列表响应异常');
    }
    if (Array.isArray(obj.items)) {
      return {
        items: obj.items as V2AdminCodeReviewTask[],
        totalElements: Number(obj.totalElements ?? obj.items.length) || obj.items.length,
      };
    }
    if (obj.data != null) return visit(obj.data, depth + 1);
    return null;
  };
  const page = visit(payload);
  if (strict && !page) throw new Error('管理员待审核列表响应格式异常');
  const items = page?.items ?? [];
  return {
    items,
    totalElements: page?.totalElements ?? items.length,
  };
}

export function mapAdminReviewTaskToListItem(
  task: V2AdminCodeReviewTask,
): import('../code').CodeVersionListItem {
  const displayName = firstNonEmptyName(
    task.codeName,
    task.codeAssetName,
    task.name,
    task.assetName,
  );
  return {
    codeVersionId: pickReviewTaskVersionId(task),
    codeAssetId: task.assetId || '',
    codeName: displayName || undefined,
    codeAssetName: displayName,
    version: task.version || '',
    fileName: task.fileName || '',
    trainingProfile: task.trainingProfile || '',
    approvalStatus: task.approvalStatus || 'PENDING',
    status: task.lifecycleStatus || 'READY',
    validationStatus: task.validationStatus,
    riskLevel: task.riskLevel,
    riskStatus: task.riskStatus,
    reviewDisposition: task.reviewDisposition,
    submittedAt: task.submittedAt,
    ownerUserId: task.ownerUserId,
  };
}

/** 管理员审核详情 → 详情页 DTO */
export function mapAdminReviewTaskDetailToCodeVersionDetail(
  detail: V2AdminCodeReviewTaskDetail,
): import('../code').CodeVersionDetail {
  const displayName = firstNonEmptyName(
    detail.codeName,
    detail.codeAssetName,
    detail.name,
    detail.assetName,
  );
  return {
    codeVersionId: pickReviewTaskVersionId(detail),
    codeAssetId: detail.assetId || '',
    codeName: displayName || undefined,
    codeAssetName: displayName,
    version: detail.version || '',
    fileName: detail.fileName || '',
    trainingProfile: detail.trainingProfile || '',
    approvalStatus: detail.approvalStatus || 'PENDING',
    status: detail.lifecycleStatus || 'READY',
    validationStatus: detail.validationStatus,
    validationPolicyVersion: detail.validationPolicyVersion,
    artifactSha256: detail.artifactSha256,
    riskAssessmentId: detail.riskAssessmentId,
    riskStatus: detail.riskStatus,
    riskLevel: detail.riskLevel,
    reviewDisposition: detail.reviewDisposition,
    riskPolicyVersion: detail.riskPolicyVersion,
    submittedAt: detail.submittedAt,
    sizeBytes: detail.sizeBytes,
    ownerUserId: detail.ownerUserId,
    entryScript: detail.entryScript,
    runtime: detail.runtime,
    purpose: detail.purpose,
    trainingType: detail.trainingType,
    riskAssessment: detail.riskAssessment
      ? {
          id: detail.riskAssessment.id,
          validationRunId: detail.riskAssessment.validationRunId,
          artifactSha256: detail.riskAssessment.artifactSha256,
          riskPolicyVersion: detail.riskAssessment.riskPolicyVersion,
          status: detail.riskAssessment.status,
          riskLevel: detail.riskAssessment.riskLevel,
          disposition: detail.riskAssessment.disposition,
          findingCount: detail.riskAssessment.findingCount,
        }
      : undefined,
  };
}

/** 从管理员审核详情构建 V2 审批请求体（文档 §18.7） */
export function buildV2ApprovalRequest(
  detail: V2AdminCodeReviewTaskDetail,
  decision: V2CodeApprovalRequest['decision'],
  reason?: string,
): V2CodeApprovalRequest {
  const risk = detail.riskAssessment;
  const body: V2CodeApprovalRequest = { decision };
  if (reason?.trim()) {
    body.reason = reason.trim();
  }
  if (decision === 'APPROVE' || decision === 'REJECT') {
    body.expectedValidationRunId = risk?.validationRunId;
    body.expectedRiskAssessmentId = risk?.id;
    body.expectedArtifactSha256 = detail.artifactSha256 || risk?.artifactSha256;
    body.expectedPolicyVersion =
      risk?.riskPolicyVersion || detail.riskPolicyVersion;
  }
  return body;
}

export function hasV2ApprovalEvidence(detail: V2AdminCodeReviewTaskDetail) {
  const risk = detail.riskAssessment;
  return Boolean(
    risk?.validationRunId &&
      risk?.id &&
      (detail.artifactSha256 || risk.artifactSha256) &&
      (risk.riskPolicyVersion || detail.riskPolicyVersion),
  );
}

/** 归一化管理员代码资产分页（后端字段 items/totalElements） */
export function normalizeAdminCodeAssetPage(
  payload?: V2AdminCodeAssetPage | null,
  strict = false,
): {
  items: V2AdminCodeAsset[];
  total: number;
  page: number;
  pageSize: number;
} {
  const visit = (
    raw: unknown,
    depth = 0,
  ): V2AdminCodeAssetPage | null => {
    if (Array.isArray(raw)) {
      return { items: raw as V2AdminCodeAsset[], totalElements: raw.length };
    }
    if (!raw || typeof raw !== 'object' || depth > 3) return null;
    const obj = raw as Record<string, unknown>;
    if (strict && (obj.errorCode || obj.success === false || (obj.code !== undefined && obj.code !== 200))) {
      throw new Error('管理员代码资产列表响应异常');
    }
    if (Array.isArray(obj.items)) {
      return obj as V2AdminCodeAssetPage;
    }
    if (obj.data != null) return visit(obj.data, depth + 1);
    return null;
  };
  const page = visit(payload);
  if (strict && !page) throw new Error('管理员代码资产列表响应格式异常');
  const items = Array.isArray(page?.items) ? page.items : [];
  if (strict && (items.some(item => !item || typeof (item.assetId || item.id) !== 'string' || !(item.assetId || item.id)?.trim())
    || (page?.totalElements != null && (!Number.isSafeInteger(page.totalElements) || page.totalElements < items.length)))) {
    throw new Error('管理员代码资产列表标识或条数异常');
  }
  return {
    items,
    total: page?.totalElements ?? items.length,
    page: page?.page ?? 0,
    pageSize: page?.pageSize ?? items.length,
  };
}
