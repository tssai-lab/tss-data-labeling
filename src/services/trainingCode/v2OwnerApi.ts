/** 本人代码资产、版本及工作区 V2 API 请求。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { FILE_DOWNLOAD_REQUEST_TIMEOUT } from '@/constants/request';
import { request } from '@umijs/max';
import {
  type V2CodeApprovalRequest,
  type V2CodeAsset,
  type V2CodeConsumerManifest,
  type V2CodeFileContent,
  type V2CodeFileMetadata,
  type V2CodeFileUpsertRequest,
  type V2CodeRiskAssessmentDetail,
  type V2CodeValidationResult,
  type V2CodeVersion,
  type V2CodeWorkspace,
  type V2CodeWorkspacePublishRequest,
} from './v2Types';


/** GET /api/v2/code-assets */
export async function listV2CodeAssets(options?: { [key: string]: unknown }) {
  return request<V2CodeAsset[]>('/v2/code-assets', {
    method: 'GET',
    ...(options || {}),
  });
}

/** POST /api/v2/code-assets */
export async function createV2CodeAsset(
  body: {
    name: string;
    trainingProfile?: string;
    purpose?: string;
    runtime?: string;
    entryScript?: string;
    remark?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeAsset>('/v2/code-assets', {
    method: 'POST',
    data: body,
    ...(options || {}),
  });
}

/** POST /api/v2/code-assets/import — multipart metadata + file */
export async function importV2CodeAssetZip(
  params: {
    file: File;
    metadata: {
      name: string;
      version?: string;
      trainingProfile?: string;
      remark?: string;
    };
  },
  options?: { [key: string]: unknown },
) {
  const formData = new FormData();
  formData.append('file', params.file);
  formData.append(
    'metadata',
    new Blob([JSON.stringify(params.metadata)], { type: 'application/json' }),
  );
  return request<V2CodeVersion>(
    '/v2/code-assets/import',
    {
      method: 'POST',
      data: formData,
      headers: { 'Content-Type': undefined as unknown as string },
      timeout: 5 * 60 * 1000,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-assets/{assetId} */
export async function getV2CodeAsset(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeAsset>(
    `/v2/code-assets/${encodeURIComponent(assetId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** DELETE /api/v2/code-assets/{assetId}?expectedAssetRevision=... */
export async function deleteV2CodeAsset(
  assetId: string,
  expectedAssetRevision: number,
  options?: { [key: string]: unknown },
) {
  return request<void>(
    `/v2/code-assets/${encodeURIComponent(assetId)}`,
    {
      method: 'DELETE',
      params: { expectedAssetRevision },
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-assets/{assetId}/workspaces */
export async function listV2CodeWorkspaces(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace[]>(
    `/v2/code-assets/${encodeURIComponent(assetId)}/workspaces`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-assets/{assetId}/workspaces */
export async function openV2CodeWorkspace(
  assetId: string,
  body?: { baseVersionId?: string },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace>(
    `/v2/code-assets/${encodeURIComponent(assetId)}/workspaces`,
    {
      method: 'POST',
      data: body || {},
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-workspaces/{workspaceId} */
export async function getV2CodeWorkspace(
  workspaceId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-workspaces/{workspaceId}/files/metadata?path=... */
export async function getV2CodeWorkspaceFileMetadata(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileMetadata>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files/metadata`,
    {
      method: 'GET',
      params: { path },
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-workspaces/{workspaceId}/files/content?path=... */
export async function getV2CodeWorkspaceFileContent(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent | string>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files/content`,
    {
      method: 'GET',
      params: { path },
      ...(options || {}),
    },
  );
}

/** PUT /api/v2/code-workspaces/{workspaceId}/files?path=... */
export async function upsertV2CodeWorkspaceFile(
  workspaceId: string,
  path: string,
  body: V2CodeFileUpsertRequest,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files`,
    {
      method: 'PUT',
      params: { path },
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-workspaces/{workspaceId}/validate */
export async function validateV2CodeWorkspace(
  workspaceId: string,
  body: { expectedWorkspaceRevision: number },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeValidationResult>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/validate`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-workspaces/{workspaceId}/files/download */
export async function downloadV2CodeWorkspaceFileBlob(
  workspaceId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files/download`,
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

/** POST /api/v2/code-workspaces/{workspaceId}/publish */
export async function publishV2CodeWorkspace(
  workspaceId: string,
  body: V2CodeWorkspacePublishRequest,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/publish`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId} */
export async function getV2CodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/code-versions/${encodeURIComponent(versionId)}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId}/tree */
export async function getV2CodeVersionTree(
  versionId: string,
  prefix?: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/tree`,
    {
      method: 'GET',
      params: prefix ? { prefix } : undefined,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId}/files/content?path=... */
export async function getV2CodeVersionFileContent(
  versionId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent | string>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/files/content`,
    {
      method: 'GET',
      params: { path },
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId}/files/download */
export async function downloadV2CodeVersionFileBlob(
  versionId: string,
  path: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/files/download`,
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

/** GET /api/v2/code-versions/{versionId}/download — 完整 ZIP 流 */
export async function downloadV2CodeVersionZip(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/download`,
    {
      method: 'GET',
      responseType: 'blob',
      skipErrorHandler: true,
      timeout: FILE_DOWNLOAD_REQUEST_TIMEOUT,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-versions/{versionId}/validate */
export async function validateV2CodeVersion(
  versionId: string,
  body?: Record<string, unknown>,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeValidationResult>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/validate`,
    {
      method: 'POST',
      data: body || {},
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId}/consumer-manifest */
export async function getV2CodeConsumerManifest(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeConsumerManifest>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/consumer-manifest`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-versions/{versionId}/risk-assessment */
export async function getV2CodeRiskAssessment(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeRiskAssessmentDetail>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/risk-assessment`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-versions/{versionId}/approval */
export async function approveV2CodeVersion(
  versionId: string,
  body: V2CodeApprovalRequest,
  options?: { [key: string]: unknown },
) {
  return request<{
    versionId?: string;
    approvalStatus?: string;
    decisionSource?: string;
    [key: string]: unknown;
  }>(`/v2/code-versions/${encodeURIComponent(versionId)}/approval`, {
    method: 'POST',
    data: body,
    ...(options || {}),
  });
}

/** PATCH /api/v2/code-assets/{assetId} */
export async function patchV2CodeAsset(
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
  return request<V2CodeAsset>(
    `/v2/code-assets/${encodeURIComponent(assetId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: body,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-assets/{assetId}/versions */
export async function listV2CodeAssetVersions(
  assetId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion[]>(
    `/v2/code-assets/${encodeURIComponent(assetId)}/versions`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

/** DELETE /api/v2/code-workspaces/{workspaceId}/files?path=... */
export async function deleteV2CodeWorkspaceFile(
  workspaceId: string,
  path: string,
  body: {
    expectedWorkspaceRevision: number;
    expectedContentHash?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace | void>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files`,
    {
      method: 'DELETE',
      params: { path },
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-workspaces/{workspaceId}/files/move */
export async function moveV2CodeWorkspaceFile(
  workspaceId: string,
  body: {
    sourcePath: string;
    targetPath: string;
    expectedWorkspaceRevision: number;
    expectedContentHash?: string;
  },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeFileContent | V2CodeWorkspace>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/files/move`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-workspaces/{workspaceId}/abandon */
export async function abandonV2CodeWorkspace(
  workspaceId: string,
  body: { expectedWorkspaceRevision: number },
  options?: { [key: string]: unknown },
) {
  return request<V2CodeWorkspace | void>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/abandon`,
    {
      method: 'POST',
      data: body,
      ...(options || {}),
    },
  );
}

/** GET /api/v2/code-workspaces/{workspaceId}/tree */
export async function getV2CodeWorkspaceTree(
  workspaceId: string,
  prefix?: string,
  options?: { [key: string]: unknown },
) {
  return request<unknown>(
    `/v2/code-workspaces/${encodeURIComponent(workspaceId)}/tree`,
    {
      method: 'GET',
      params: prefix ? { prefix } : undefined,
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-versions/{versionId}/deprecate */
export async function deprecateV2CodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/deprecate`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-versions/{versionId}/archive */
export async function archiveV2CodeVersion(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<V2CodeVersion>(
    `/v2/code-versions/${encodeURIComponent(versionId)}/archive`,
    {
      method: 'POST',
      data: {},
      ...(options || {}),
    },
  );
}

/** POST /api/v2/code-versions/{versionId}/artifact-upgrade */
export async function upgradeV2CodeArtifact(
  versionId: string,
  options?: { [key: string]: unknown },
) {
  return request<{
    versionId?: string;
    artifactSha256?: string;
    sizeBytes?: number;
    approvalStatus?: string;
    upgraded?: boolean;
    validation?: V2CodeValidationResult;
  }>(`/v2/code-versions/${encodeURIComponent(versionId)}/artifact-upgrade`, {
    method: 'POST',
    data: {},
    ...(options || {}),
  });
}
