/** 版本与草稿的只读预览。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { request } from '@umijs/max';
import {
  extractV2FileText,
  fetchAllV2CodeTreeFiles,
  getV2CodeConsumerManifest,
  getV2CodeRiskAssessment,
  getV2CodeVersion,
  getV2CodeVersionFileContent,
  getV2CodeVersionTree,
  getV2CodeWorkspaceFileContent,
  getV2CodeWorkspaceTree,
  isInternalGeneratedCodeAssetName,
  listV2CodeWorkspaces,
  mapV2CodeVersionToLegacy,
} from '../codeV2';
import { isOpenWorkspace } from './common';
import { enrichCodeVersionDisplayFields, fetchCodeAssetMeta } from './display';
import { type CodeVersionDetail, type CodeVersionPreviewBundle } from './types';


/** 训练代码版本详情（优先 V2） */
export async function getCodeVersionDetail(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  try {
    const detail = await getV2CodeVersion(codeVersionId, options);
    const assetId = detail.assetId || detail.codeAssetId;
    const [manifest, riskAssessment, assetMeta] = await Promise.all([
      getV2CodeConsumerManifest(codeVersionId, {
        ...(options || {}),
        skipErrorHandler: true,
      }).catch(() => undefined),
      getV2CodeRiskAssessment(codeVersionId, {
        ...(options || {}),
        skipErrorHandler: true,
      }).catch(() => undefined),
      fetchCodeAssetMeta(assetId, options),
    ]);
    const mapped = mapV2CodeVersionToLegacy(detail) as CodeVersionDetail;
    const trainingProfile =
      mapped.trainingProfile?.trim() ||
      manifest?.trainingProfile?.trim() ||
      assetMeta?.trainingProfile ||
      '';
    const codeName =
      mapped.codeName ||
      (assetMeta?.name && !isInternalGeneratedCodeAssetName(assetMeta.name)
        ? assetMeta.name
        : undefined) ||
      assetMeta?.name;
    return {
      success: true,
      data: {
        ...mapped,
        codeName,
        codeAssetName: codeName || mapped.codeAssetName,
        trainingProfile,
        entryScript: mapped.entryScript || manifest?.entryScript,
        validationStatus: mapped.validationStatus,
        riskLevel:
          mapped.riskLevel ||
          riskAssessment?.riskLevel ||
          manifest?.riskLevel,
        reviewDisposition:
          mapped.reviewDisposition ||
          riskAssessment?.disposition ||
          undefined,
        riskAssessmentId:
          mapped.riskAssessmentId ||
          riskAssessment?.id ||
          manifest?.riskAssessmentId,
        riskPolicyVersion:
          mapped.riskPolicyVersion ||
          riskAssessment?.riskPolicyVersion ||
          manifest?.riskPolicyVersion,
        runtime: manifest?.runtime,
        purpose: manifest?.purpose,
        trainingType: manifest?.trainingType,
        consumerManifest: manifest
          ? {
              validationRunId: manifest.validationRunId,
              validationPolicyVersion: manifest.validationPolicyVersion,
              approvalRecordId: manifest.approvalRecordId,
              approvalSource: manifest.approvalSource,
              riskAssessmentId: manifest.riskAssessmentId,
              riskLevel: manifest.riskLevel,
              riskPolicyVersion: manifest.riskPolicyVersion,
            }
          : undefined,
        riskAssessment: riskAssessment
          ? {
              id: riskAssessment.id,
              validationRunId: riskAssessment.validationRunId,
              artifactSha256: riskAssessment.artifactSha256,
              riskPolicyVersion: riskAssessment.riskPolicyVersion,
              status: riskAssessment.status,
              riskLevel: riskAssessment.riskLevel,
              disposition: riskAssessment.disposition,
              findingCount: riskAssessment.findingCount,
              reasonCode: riskAssessment.reasonCode,
              findings: riskAssessment.findings,
            }
          : undefined,
      },
    };
  } catch {
    const legacyRes = await request<{
      success: boolean;
      data: CodeVersionDetail;
      errorMessage?: string;
    }>(`/code/version/${encodeURIComponent(codeVersionId)}`, {
      method: 'GET',
      ...(options || {}),
    });
    if (legacyRes?.data) {
      return {
        ...legacyRes,
        data: await enrichCodeVersionDisplayFields(legacyRes.data, {
          ...(options || {}),
          enrichRisk: true,
        }),
      };
    }
    return legacyRes;
  }
}

function mapV2TreeFileEntries(
  files: Array<{
    path: string;
    fileName: string;
    sizeBytes?: number;
    languageId?: string;
  }>,
): API.ModelCodeFile[] {
  return files.map((item) => ({
    path: item.path,
    fileName: item.fileName,
    sizeBytes: item.sizeBytes,
    languageId: item.languageId,
  })) as API.ModelCodeFile[];
}

async function fetchV2CodeFilesFromTree(
  fetchTree: (prefix?: string) => Promise<unknown>,
  options?: { [key: string]: any },
): Promise<API.ModelCodeFile[]> {
  const files = await fetchAllV2CodeTreeFiles(fetchTree, options);
  return mapV2TreeFileEntries(files);
}

function codePreviewErrorMessage(error: any, fallback: string): string {
  const status = error?.response?.status;
  const data = error?.response?.data;
  if (status === 404) {
    return (
      (typeof data?.errorMessage === 'string' && data.errorMessage) ||
      '当前后端未部署训练代码目录/预览接口（需 GET /api/v2/code-versions/{id}/tree 与 /files/content）'
    );
  }
  return (
    data?.errorMessage ||
    error?.info?.errorMessage ||
    error?.data?.errorMessage ||
    error?.message ||
    fallback
  );
}

/**
 * 列出训练代码 zip 内可预览文件。
 * 文档约定走 V2：GET /api/v2/code-versions/{id}/tree
 * 现网 Legacy 无 /api/code/code-files，勿回退。
 */
export async function listCodeVersionFiles(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  const data = await fetchV2CodeFilesFromTree(
    (prefix) => getV2CodeVersionTree(codeVersionId, prefix, options),
    options,
  );
  return { success: true, data };
}

/**
 * 预览训练代码 zip 内单个文件。
 * 文档约定走 V2：GET /api/v2/code-versions/{id}/files/content?path=
 * 现网 Legacy 无 /api/code/previewCode，勿回退。
 */
export async function previewCodeVersionFile(
  codeVersionId: string,
  path: string,
  options?: { [key: string]: any },
) {
  const payload = await getV2CodeVersionFileContent(
    codeVersionId,
    path,
    options,
  );
  const content = extractV2FileText(payload);
  const languageId =
    typeof payload === 'object' && payload && 'languageId' in payload
      ? String((payload as { languageId?: string }).languageId || '')
      : undefined;
  return {
    success: true,
    data: {
      content,
      path,
      fileName: path.split('/').pop() || path,
      languageId,
    } as API.ModelCodePreview,
  };
}

/** 加载训练代码默认预览（首个可预览文件） */
export async function fetchCodeVersionCodePreview(
  codeVersionId: string,
  options?: { [key: string]: any },
) {
  let codeFiles: API.ModelCodeFile[] = [];
  let codeContent: string | undefined;
  let codeFileName: string | undefined;
  let codeFilePath: string | undefined;
  let loadError: string | undefined;

  try {
    const codeFilesRes = await listCodeVersionFiles(codeVersionId, options);
    codeFiles = codeFilesRes?.data ?? [];
    if (codeFiles.length > 0 && codeFiles[0].path) {
      const previewRes = await previewCodeVersionFile(
        codeVersionId,
        codeFiles[0].path,
        options,
      );
      if (previewRes?.data?.content) {
        codeContent = previewRes.data.content;
        codeFileName =
          previewRes.data.fileName || codeFiles[0].fileName || codeFiles[0].path;
        codeFilePath = previewRes.data.path || codeFiles[0].path;
      }
    }
  } catch (error: any) {
    codeFiles = [];
    loadError = codePreviewErrorMessage(error, '代码文件列表加载失败');
  }

  return {
    data: {
      codeFiles,
      codeContent,
      codeFileName,
      codeFilePath,
      loadError,
    } as CodeVersionPreviewBundle,
  };
}

/**
 * 优先读取打开中的工作区草稿树；无草稿时回退到不可变版本树。
 * 用于详情页在新建/删除/重命名后能看到工作区变更。
 *
 * @param preferVersionSnapshot 为 true 时跳过工作区，直接读版本快照
 * （发布新版本后应使用，避免仍读到未关闭的草稿）
 */
export async function fetchCodeEditablePreview(
  params: {
    codeVersionId: string;
    codeAssetId?: string;
    preferVersionSnapshot?: boolean;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  const assetId = params.codeAssetId?.trim();
  if (assetId && !params.preferVersionSnapshot) {
    let workspaceListLoaded = false;
    try {
      const listed = await listV2CodeWorkspaces(assetId, opts);
      workspaceListLoaded = true;
      if (!Array.isArray(listed)) throw new Error('工作区列表响应异常，请重试');
      const openWs = Array.isArray(listed)
        ? listed.find((ws) => isOpenWorkspace(ws) && ws.id)
        : undefined;
      if (openWs?.id) {
        const workspaceId = openWs.id;
        const codeFiles = await fetchV2CodeFilesFromTree(
          (prefix) => getV2CodeWorkspaceTree(workspaceId, prefix, opts),
          opts,
        );
        let codeContent: string | undefined;
        let codeFileName: string | undefined;
        let codeFilePath: string | undefined;
        let loadError: string | undefined;
        if (codeFiles[0]?.path) {
          try {
            const payload = await getV2CodeWorkspaceFileContent(
              openWs.id,
              codeFiles[0].path,
              opts,
            );
            const content = extractV2FileText(payload);
            if (content !== undefined) {
              codeContent = content;
              codeFilePath = codeFiles[0].path;
              codeFileName =
                codeFiles[0].fileName ||
                codeFiles[0].path.split('/').pop() ||
                codeFiles[0].path;
            }
          } catch (error) {
            loadError = codePreviewErrorMessage(error, '读取工作区文件失败');
          }
        }
        return {
          data: {
            codeFiles,
            codeContent,
            codeFileName,
            codeFilePath,
            loadError,
            workspaceId: openWs.id,
            fromWorkspace: true,
          } as CodeVersionPreviewBundle & {
            workspaceId?: string;
            fromWorkspace?: boolean;
          },
        };
      }
    } catch (error) {
      // 仅旧后端根本没有工作区端点时兼容版本预览；草稿读取失败不能换成旧代码。
      if (workspaceListLoaded || !isLegacyEndpointUnavailable(error)) throw error;
    }
  }
  const versionPreview = await fetchCodeVersionCodePreview(
    params.codeVersionId,
    opts,
  );
  return {
    data: {
      ...versionPreview.data,
      fromWorkspace: false,
    },
  };
}

/** 预览：优先工作区草稿内容，否则版本快照 */
export async function previewCodeEditableFile(
  params: {
    codeVersionId: string;
    codeAssetId?: string;
    path: string;
    preferVersionSnapshot?: boolean;
  },
  options?: { [key: string]: any },
) {
  const opts = { skipErrorHandler: true, ...(options || {}) };
  const assetId = params.codeAssetId?.trim();
  if (assetId && !params.preferVersionSnapshot) {
    let workspaceListLoaded = false;
    try {
      const listed = await listV2CodeWorkspaces(assetId, opts);
      workspaceListLoaded = true;
      if (!Array.isArray(listed)) throw new Error('工作区列表响应异常，请重试');
      const openWs = Array.isArray(listed)
        ? listed.find((ws) => isOpenWorkspace(ws) && ws.id)
        : undefined;
      if (openWs?.id) {
        const payload = await getV2CodeWorkspaceFileContent(
          openWs.id,
          params.path,
          opts,
        );
        const content = extractV2FileText(payload);
        return {
          success: true,
          data: {
            content,
            path: params.path,
            fileName: params.path.split('/').pop() || params.path,
            fromWorkspace: true,
            workspaceId: openWs.id,
          },
        };
      }
    } catch (error) {
      if (workspaceListLoaded || !isLegacyEndpointUnavailable(error)) throw error;
    }
  }
  const res = await previewCodeVersionFile(
    params.codeVersionId,
    params.path,
    opts,
  );
  return {
    ...res,
    data: res?.data
      ? { ...res.data, fromWorkspace: false }
      : res?.data,
  };
}
