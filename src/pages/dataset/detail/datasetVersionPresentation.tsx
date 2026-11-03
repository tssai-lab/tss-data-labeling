/** 从页面提取的纯展示/版本规则；不负责请求与写入。 */
import { resolveDatasetVersionId } from '@/services/dataset';
import { suggestNextDatasetVersion } from '@/utils/datasetVersion';
export type DraftVersionContext = {
  assetId?: string;
  versions?: API.DatasetVersionDetail[];
  latestDraftVersionId?: string | null;
  importJobId?: string | null;
  importStatus?: string | null;
  editSessionId?: string | null;
};

export function hasReadyDatasetVersions(
  versions?: API.DatasetVersionDetail[],
): boolean {
  return (versions ?? []).some((v) => v.status === 'READY');
}

export function resolveDetailVersionId(
  version?: API.DatasetVersionDetail | null,
  assetId?: string,
): string | undefined {
  return resolveDatasetVersionId(version, assetId) ?? version?.id;
}

export function isImportDraftVersion(
  version?: API.DatasetVersionDetail | null,
  context?: DraftVersionContext,
): boolean {
  if (version?.status !== 'DRAFT') return false;
  if (version.parentVersionId) return false;

  const vid = resolveDetailVersionId(version, context?.assetId);
  const versions = context?.versions ?? [];
  const hasReady = hasReadyDatasetVersions(versions);

  if (hasReady) {
    const isLatestDraft = !!vid && vid === context?.latestDraftVersionId;
    const importing =
      !!context?.importJobId &&
      isLatestDraft &&
      ['PENDING', 'RUNNING', 'FAILED', 'PARTIAL'].includes(
        context?.importStatus ?? '',
      );
    return importing;
  }

  return true;
}

export function isWorkspaceDraftVersion(
  version?: API.DatasetVersionDetail | null,
  context?: DraftVersionContext,
): boolean {
  if (version?.status !== 'DRAFT') return false;
  if (version.parentVersionId) return true;

  const vid = resolveDetailVersionId(version, context?.assetId);
  if (
    context?.editSessionId &&
    vid &&
    vid === context.editSessionId &&
    hasReadyDatasetVersions(context.versions)
  ) {
    return true;
  }

  if (isImportDraftVersion(version, context)) return false;
  return hasReadyDatasetVersions(context?.versions);
}

export function buildDraftContext(
  datasetInfo?:
    | (API.DatasetDetail & {
        latestDraftVersionId?: string | null;
        importJobId?: string | null;
        importStatus?: string | null;
        editSessionId?: string | null;
      })
    | null,
): DraftVersionContext | undefined {
  if (!datasetInfo) return undefined;
  return {
    assetId: datasetInfo.id,
    versions: datasetInfo.versions,
    latestDraftVersionId: datasetInfo.latestDraftVersionId,
    importJobId: datasetInfo.importJobId,
    importStatus: datasetInfo.importStatus,
    editSessionId: datasetInfo.editSessionId,
  };
}

export function resolveActiveDraftId(
  datasetInfo?:
    | (API.DatasetDetail & {
        latestDraftVersionId?: string | null;
        editSessionId?: string | null;
        workspaceId?: string | null;
      })
    | null,
  draftContext?: DraftVersionContext,
): string | undefined {
  if (!datasetInfo) return undefined;
  const row = datasetInfo.versions.find((item) =>
    isWorkspaceDraftVersion(item, draftContext),
  );
  if (row) {
    return resolveDatasetVersionId(row, datasetInfo.id) ?? row.id;
  }
  // latestDraftVersionId 可能是导入草稿；仅当它对应工作区草稿行时使用
  const latest = datasetInfo.latestDraftVersionId || undefined;
  if (
    latest &&
    isWorkspaceDraftVersion(
      datasetInfo.versions.find(
        (item) =>
          (resolveDatasetVersionId(item, datasetInfo.id) ?? item.id) === latest,
      ),
      draftContext,
    )
  ) {
    return latest;
  }
  return undefined;
}

export function normalizeDatasetVersionInput(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return trimmed;
  if (/^v?\d+\.\d+\.\d+$/i.test(trimmed)) {
    return `v${trimmed.replace(/^v/i, '')}`;
  }
  if (/^v?\d+$/i.test(trimmed)) {
    return `v${trimmed.replace(/^v/i, '')}`;
  }
  return trimmed;
}

export function buildVersionLabelCandidates(
  preferred: string,
  existingVisible: string[],
  extraAttempts = 8,
): string[] {
  const preferredNorm = normalizeDatasetVersionInput(preferred);
  const occupied = new Set(
    existingVisible.map((item) => item.trim().toLowerCase()),
  );
  const out: string[] = [];
  const push = (label: string) => {
    const key = label.toLowerCase();
    if (
      !label ||
      occupied.has(key) ||
      out.some((x) => x.toLowerCase() === key)
    ) {
      return;
    }
    out.push(label);
  };
  push(preferredNorm);
  const semver = preferredNorm.match(/^v(\d+)\.(\d+)\.(\d+)$/i);
  const legacy = preferredNorm.match(/^v(\d+)$/i);
  if (semver) {
    const major = Number(semver[1]);
    const minor = Number(semver[2]);
    let patch = Number(semver[3]);
    for (let i = 0; i < extraAttempts; i += 1) {
      patch += 1;
      push(`v${major}.${minor}.${patch}`);
    }
  } else if (legacy) {
    let n = Number(legacy[1]);
    for (let i = 0; i < extraAttempts; i += 1) {
      n += 1;
      push(`v${n}`);
    }
  } else {
    push(suggestNextDatasetVersion(existingVisible));
  }
  return out;
}

export const DATASET_TYPE_LABEL: Record<string, string> = {
  CV: 'CV',
  NLP: 'NLP',
  POINT_CLOUD: '点云',
  MULTIMODAL: '多模态',
  ROBOT: '机器人',
  LEROBOT: 'LeRobot',
  OTHER: '其他（暂未归类）',
};

export const DATASET_TYPE_COLOR: Record<string, string> = {
  CV: 'blue',
  NLP: 'green',
  POINT_CLOUD: 'purple',
  MULTIMODAL: 'magenta',
  ROBOT: 'default',
  LEROBOT: 'blue',
  OTHER: 'default',
};
