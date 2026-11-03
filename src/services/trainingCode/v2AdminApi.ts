/** 管理员 V2 API 请求。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import { request } from '@umijs/max';
import {
  type V2AdminCodeAsset,
  type V2AdminCodeAssetPage,
  type V2AdminCodeReviewTaskDetail,
  type V2AdminCodeReviewTaskPage,
  type V2CodeFileContent,
  type V2CodeFileMetadata,
  type V2CodeFileUpsertRequest,
  type V2CodeRiskAssessmentDetail,
  type V2CodeValidationResult,
  type V2CodeVersion,
  type V2CodeWorkspace,
  type V2CodeWorkspacePublishRequest,
} from './v2Types';


/** GET /api/v2/admin/code-review-tasks */
export async function listAdminCodeReviewTasks(
  params?: {
    approvalStatus?: string;
    riskLevel?: string;
    ownerUserId?: number;
    keyword?: string;
    submittedFrom?: string;
    submittedTo?: string;
    sortBy?: string;
    sortDirection?: string;
    page?: number;
    pageSize?: number;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2AdminCodeReviewTaskPage>('/v2/admin/code-review-tasks', {
    method: 'GET',
    params: {
      approvalStatus: params?.approvalStatus ?? 'PENDING',
      page: params?.page ?? 0,
      pageSize: params?.pageSize ?? 20,
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/v2/admin/code-review-tasks/{versionId} */
export async function getAdminCodeReviewTaskDetail(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2AdminCodeReviewTaskDetail>(
    `/v2/admin/code-review-tasks/${encodeURIComponent(versionId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-review-tasks/{versionId}/tree */
export async function getAdminCodeReviewTaskTree(
  versionId: string,
  prefix?: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/admin/code-review-tasks/${encodeURIComponent(versionId)}/tree`,
    {
      method: 'GET',
      params: prefix ? { prefix } : undefined,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-review-tasks/{versionId}/files/content */
export async function getAdminCodeReviewTaskFileContent(
  versionId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent | string>(
    `/v2/admin/code-review-tasks/${encodeURIComponent(versionId)}/files/content`,
    {
      method: 'GET',
      params: { path },
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-assets — page 从 0 开始 */
export async function listAdminCodeAssets(
  params?: {
    page?: number;
    pageSize?: number;
    keyword?: string;
    ownerUserId?: string;
    trainingProfile?: string;
    sortBy?: 'UPDATED_AT' | 'CREATED_AT' | 'NAME' | 'OWNER_USER_ID' | string;
    sortDirection?: 'ASC' | 'DESC';
  },
  options?: { [key: string]: unknown },
) {
  return request<V2AdminCodeAssetPage>('/v2/admin/code-assets', {
    method: 'GET',
    params: {
      page: params?.page ?? 0,
      pageSize: params?.pageSize ?? 20,
      sortBy: params?.sortBy ?? 'UPDATED_AT',
      sortDirection: params?.sortDirection ?? 'DESC',
      ...(params?.keyword ? { keyword: params.keyword } : {}),
      ...(params?.ownerUserId ? { ownerUserId: params.ownerUserId } : {}),
      ...(params?.trainingProfile
        ? { trainingProfile: params.trainingProfile }
        : {}),
    },
    ...(options || {}),
  });
}

/** GET /api/v2/admin/code-assets/{assetId} */
export async function getAdminCodeAsset(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2AdminCodeAsset>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}`,
    { method: 'GET', ...(options || {}) },
  );
}

/** PATCH /api/v2/admin/code-assets/{assetId} */
export async function patchAdminCodeAsset(
  assetId: string,
  body: {
    assetRevision: number;
    name?: string;
    trainingProfile?: string;
    purpose?: string;
    runtime?: string;
    entryScript?: string;
    trainingType?: string;
    remark?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2AdminCodeAsset>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: body,
      ...(options || {}),
    },
  );
}

/** DELETE /api/v2/admin/code-assets/{assetId} */
export async function deleteAdminCodeAsset(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}`,
    { method: 'DELETE', ...(options || {}) },
  );
}

/** GET /api/v2/admin/code-assets/{assetId}/versions */
export async function listAdminCodeAssetVersions(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion[]>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}/versions`,
    { method: 'GET', ...(options || {}) },
  );
}

/** GET /api/v2/admin/code-assets/{assetId}/workspaces */
export async function listAdminCodeAssetWorkspaces(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace[]>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}/workspaces`,
    { method: 'GET', ...(options || {}) },
  );
}

/** POST /api/v2/admin/code-assets/{assetId}/workspaces */
export async function openAdminCodeAssetWorkspace(
  assetId: string,
  body?: { baseVersionId?: string },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace>(
    `/v2/admin/code-assets/${encodeURIComponent(assetId)}/workspaces`,
    { method: 'POST', data: body || {}, ...(options || {}) },
  );
}

/** GET /api/v2/admin/code-workspaces/{workspaceId}/tree */
export async function getAdminCodeWorkspaceTree(
  workspaceId: string,
  prefix?: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/tree`,
    {
      method: 'GET',
      params: prefix ? { prefix } : undefined,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-workspaces/{workspaceId}/files/content */
export async function getAdminCodeWorkspaceFileContent(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<{
    path?: string;
    content?: string;
    text?: string;
    encoding?: string;
    [key: string]: unknown;
  }>(`/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files/content`, {
    method: 'GET',
    params: { path },
    ...(options || {}),
  });
}

/** GET /api/v2/admin/code-workspaces/{workspaceId} */
export async function getAdminCodeWorkspace(
  workspaceId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}`,
    { method: 'GET', ...(options || {}) },
  );
}

/** GET /api/v2/admin/code-workspaces/{workspaceId}/files/metadata */
export async function getAdminCodeWorkspaceFileMetadata(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileMetadata>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files/metadata`,
    {
      method: 'GET',
      params: { path },
      ...(options || {}),
    },
  );
}

/** PUT /api/v2/admin/code-workspaces/{workspaceId}/files */
export async function upsertAdminCodeWorkspaceFile(
  workspaceId: string,
  path: string,
  body: V2CodeFileUpsertRequest,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files`,
    {
      method: 'PUT',
      params: { path },
      data: body,
      ...(options || {}),
    },
  );
}

/** DELETE /api/v2/admin/code-workspaces/{workspaceId}/files */
export async function deleteAdminCodeWorkspaceFile(
  workspaceId: string,
  path: string,
  body: {
    expectedWorkspaceRevision: number;
    expectedContentHash?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<{ workspaceId?: string; workspaceRevision?: number }>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files`,
    {
      method: 'DELETE',
      params: { path },
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-workspaces/{workspaceId}/publish */
export async function publishAdminCodeWorkspace(
  workspaceId: string,
  body: V2CodeWorkspacePublishRequest,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/publish`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-workspaces/{workspaceId}/abandon */
export async function abandonAdminCodeWorkspace(
  workspaceId: string,
  body: { expectedWorkspaceRevision: number },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/abandon`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-workspaces/{workspaceId}/files/move */
export async function moveAdminCodeWorkspaceFile(
  workspaceId: string,
  body: {
    sourcePath: string;
    targetPath: string;
    expectedWorkspaceRevision: number;
    expectedContentHash?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files/move`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-workspaces/{workspaceId}/validate */
export async function validateAdminCodeWorkspace(
  workspaceId: string,
  body: { expectedWorkspaceRevision: number },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeValidationResult>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/validate`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-workspaces/{workspaceId}/files/download */
export async function downloadAdminCodeWorkspaceFileBlob(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/admin/code-workspaces/${encodeURIComponent(workspaceId)}/files/download`,
    {
      method: 'GET',
      params: { path },
      responseType: 'blob',
      skipErrorHandler: true,
      timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-versions/{versionId} */
export async function getAdminCodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}`,
    { method: 'GET', ...(options || {}) },
  );
}

/** GET /api/v2/admin/code-versions/{versionId}/tree */
export async function getAdminCodeVersionTree(
  versionId: string,
  prefix?: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/tree`,
    {
      method: 'GET',
      params: prefix ? { prefix } : undefined,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-versions/{versionId}/files/content */
export async function getAdminCodeVersionFileContent(
  versionId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<{
    path?: string;
    content?: string;
    text?: string;
    encoding?: string;
    [key: string]: unknown;
  }>(`/v2/admin/code-versions/${encodeURIComponent(versionId)}/files/content`, {
    method: 'GET',
    params: { path },
    ...(options || {}),
  });
}

/** GET /api/v2/admin/code-versions/{versionId}/files/download */
export async function downloadAdminCodeVersionFileBlob(
  versionId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/files/download`,
    {
      method: 'GET',
      params: { path },
      responseType: 'blob',
      skipErrorHandler: true,
      timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-versions/{versionId}/validate */
export async function validateAdminCodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeValidationResult>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/validate`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-versions/{versionId}/deprecate */
export async function deprecateAdminCodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/deprecate`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}

/** POST /api/v2/admin/code-versions/{versionId}/archive */
export async function archiveAdminCodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/archive`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-versions/{versionId}/download — 完整 ZIP */
export async function downloadAdminCodeVersionZip(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/admin/code-versions/${encodeURIComponent(versionId)}/download`,
    {
      method: 'GET',
      responseType: 'blob',
      skipErrorHandler: true,
      timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/admin/code-review-tasks/{versionId}/findings */
export async function listAdminCodeReviewFindings(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<
    Array<{
      id?: string;
      riskAssessmentId?: string;
      ruleId?: string;
      severity?: string;
      category?: string;
      filePath?: string;
      lineStart?: number;
      lineEnd?: number;
      description?: string;
    }>
  >(`/v2/admin/code-review-tasks/${encodeURIComponent(versionId)}/findings`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** POST /api/v2/admin/code-review-tasks/{versionId}/rescan */
export async function rescanAdminCodeReviewTask(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeRiskAssessmentDetail | Record<string, unknown>>(
    `/v2/admin/code-review-tasks/${encodeURIComponent(versionId)}/rescan`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}
