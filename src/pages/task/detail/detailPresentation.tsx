/** 详情展示与本地对比选择辅助；不负责 API 请求、调度或业务数据写入。 */
import { Space, Tooltip, Typography } from 'antd';
import React from 'react';
import { formatDurationBetween } from '@/utils/formatDateTime';
import {
  getTrainingStatusTagColor,
  getTrainingStatusText,
  isTrainingTerminal,
} from '@/utils/trainingStatusDisplay';
import { readHyperParamSummary } from './trainingDetailPresentation.mjs';
export const COMPARE_POOL_KEY = 'comparePoolIds';

export function loadComparePool(): string[] {
  try {
    const raw = localStorage.getItem(COMPARE_POOL_KEY);
    const arr = raw ? (JSON.parse(raw) as any[]) : [];
    return Array.isArray(arr) ? arr.map(String) : [];
  } catch {
    return [];
  }
}

export function saveComparePool(ids: string[]) {
  const uniq = Array.from(new Set(ids.map(String))).slice(0, 30);
  localStorage.setItem(COMPARE_POOL_KEY, JSON.stringify(uniq));
  return uniq;
}

export type TaskDetailInfo = API.TaskItem & {
  completeTime?: string;
  duration?: string;
  metrics?: Record<string, any>;
  files?: { name: string; desc: string; objectName?: string }[];
  hyperParams?: Record<string, any>;
  codeVersionId?: string;
  trainingProfile?: string;
  producedModelVersionId?: string;
  modelArtifactPath?: string;
  modelArtifactSizeBytes?: number;
  modelPublishStatus?: string;
  modelPublishError?: string;
  runSpec?: {
    resources?: {
      profileId?: string;
      gpuNodeName?: string;
      gpuHostIndex?: string;
      gpuUuid?: string;
      gpuModel?: string;
      gpuTotalMemoryMiB?: number;
      gpuMemoryLimitMiB?: number;
    };
  };
  trainingOutput?: {
    artifacts?: Array<{
      format?: string;
      objectName?: string;
      path?: string;
      role?: string;
      sha256?: string;
      sizeBytes?: number;
    }>;
  };
};

export function normalizeHyperParams(
  hp: unknown,
): Record<string, unknown> | null {
  if (hp == null) return null;
  if (typeof hp === 'string') {
    const trimmed = hp.trim();
    if (!trimmed) return null;
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      return null;
    }
    return null;
  }
  if (typeof hp === 'object' && !Array.isArray(hp)) {
    return hp as Record<string, unknown>;
  }
  return null;
}

export function isHyperParamValuePresent(value: unknown): boolean {
  if (value == null) return false;
  if (typeof value === 'string') return value.trim().length > 0;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === 'object') return Object.keys(value as object).length > 0;
  return true;
}

export function hasMeaningfulHyperParams(hp: unknown): boolean {
  const obj = normalizeHyperParams(hp);
  if (!obj) return false;
  return Object.values(obj).some(isHyperParamValuePresent);
}

export function renderHyperParamsCell(hp: unknown) {
  if (!hasMeaningfulHyperParams(hp)) {
    return '-';
  }
  const obj = normalizeHyperParams(hp);
  if (!obj) return '-';
  const { epochs, batch, lr } = readHyperParamSummary(obj);
  const hasSummary = [epochs, batch, lr].some(isHyperParamValuePresent);
  if (hasSummary) {
    const txt = `epochs=${epochs ?? '-'}，batch=${batch ?? '-'}，lr=${lr ?? '-'}`;
    return (
      <Tooltip title={JSON.stringify(obj, null, 2)}>
        <span>{txt}</span>
      </Tooltip>
    );
  }
  const json = JSON.stringify(obj);
  const preview = json.length > 80 ? `${json.slice(0, 80)}…` : json;
  return (
    <Tooltip title={JSON.stringify(obj, null, 2)}>
      <span>{preview}</span>
    </Tooltip>
  );
}

export function renderHyperParamsDetail(hp: unknown) {
  if (!hasMeaningfulHyperParams(hp)) {
    return '-';
  }
  const obj = normalizeHyperParams(hp);
  if (!obj) return '-';
  const { epochs, batch, lr } = readHyperParamSummary(obj);
  const hasSummary = [epochs, batch, lr].some(isHyperParamValuePresent);
  const jsonText = JSON.stringify(obj, null, 2);
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {hasSummary && (
        <Typography.Text type="secondary">
          {`epochs=${epochs ?? '-'}，batch=${batch ?? '-'}，lr=${lr ?? '-'}`}
        </Typography.Text>
      )}
      <Typography.Paragraph
        copyable
        style={{
          margin: 0,
          fontFamily: 'monospace',
          fontSize: 12,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {jsonText}
      </Typography.Paragraph>
    </Space>
  );
}

export function saveContinueTrainingPrefill(
  record: API.TrainingExperimentVersion,
) {
  localStorage.setItem(
    'taskCreatePrefill',
    JSON.stringify({
      fromVersionId: record.id,
      experimentId: record.experimentId,
      /** 继续训练应加载此版本的结果模型，而非输入基础权重 */
      producedModelVersionId: record.producedModelVersionId,
      baseModelVersionId: record.producedModelVersionId,
      modelVersionId: record.producedModelVersionId,
      datasetVersionId: record.datasetVersionId,
      codeVersionId: record.codeVersionId,
      hyperParams: JSON.stringify(record.hyperParams ?? {}, null, 2),
      remark: record.remark
        ? `基于 v${record.versionNo}：${record.remark}`
        : `基于 v${record.versionNo} 继续训练`,
      versionNo: record.versionNo,
    }),
  );
}

export function statusText(status?: string) {
  return getTrainingStatusText(status);
}

export function statusColor(status?: string) {
  return getTrainingStatusTagColor(status);
}

export function isExperimentId(value?: string) {
  return !!value && /^exp-/i.test(value);
}

export function mapVersionToTaskDetail(
  data: API.TrainingExperimentVersion,
): TaskDetailInfo {
  return {
    ...data,
    name: data.name || `训练 · 第 ${data.versionNo ?? '?'} 版`,
    createTime: data.createTime || data.createdAt || '',
    progress: data.progress ?? 0,
    runId: data.runId || (data as any).run_id,
  };
}

export function resolveTaskCreatedAt(task: TaskDetailInfo): string | undefined {
  return task.createTime || (task as { createdAt?: string }).createdAt;
}

export function resolveTaskFinishedAt(
  task: TaskDetailInfo,
): string | undefined {
  if (task.completeTime?.trim()) return task.completeTime;
  if (task.finishedAt?.trim()) return task.finishedAt;
  if (isTrainingTerminal(task.status)) {
    return (task as { updatedAt?: string }).updatedAt;
  }
  return undefined;
}

export function resolveTaskDurationText(task: TaskDetailInfo): string {
  if (task.duration?.trim()) return task.duration;
  const start = task.startedAt?.trim() || resolveTaskCreatedAt(task);
  const end = resolveTaskFinishedAt(task);
  return formatDurationBetween(start, end);
}

export const CONSISTENCY_PROFILE = 'image_text_consistency_fusion_logreg';

export const CONSISTENCY_SPLITS = ['train', 'val', 'test'] as const;

export const CONSISTENCY_METRIC_KEYS = [
  'accuracy',
  'precision',
  'recall',
  'f1',
  'roc_auc',
] as const;

export const CONSISTENCY_ARTIFACT_FILES = [
  'fusion_model.pkl',
  'fusion_model.zip',
  'metrics.json',
  'val_predictions.csv',
  'test_predictions.csv',
] as const;

export function isConsistencyProfileTask(metrics?: Record<string, any>) {
  return metrics?.trainingProfile === CONSISTENCY_PROFILE;
}

export function formatMetricValue(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value.toFixed(4);
  }
  return value != null && value !== '' ? String(value) : '-';
}

export function buildConsistencyMetricsRows(metrics?: Record<string, any>) {
  if (!metrics) return [];
  return CONSISTENCY_SPLITS.map((split) => {
    const label =
      split === 'train' ? '训练集' : split === 'val' ? '验证集' : '测试集';
    const row: Record<string, string | number> = {
      key: split,
      split: label,
      rows: metrics[`${split}_rows`] ?? '-',
      positive: metrics[`${split}_positive`] ?? '-',
      negative: metrics[`${split}_negative`] ?? '-',
    };
    CONSISTENCY_METRIC_KEYS.forEach((metric) => {
      row[metric] = formatMetricValue(metrics[`${split}_${metric}`]);
    });
    return row;
  });
}

export function buildConsistencyArtifactItems(
  outputPath?: string,
  logPath?: string,
) {
  const items: {
    name: string;
    desc: string;
    objectName?: string;
  }[] = [];
  if (outputPath) {
    const base = minioPathToObjectName(outputPath);
    CONSISTENCY_ARTIFACT_FILES.forEach((fileName) => {
      if (!base) return;
      items.push({
        name: fileName,
        desc: `minio://${base}/${fileName}`,
        objectName: `${base}/${fileName}`,
      });
    });
  }
  if (logPath) {
    const logObj = logPath.replace(/^minio:\/\//, '').replace(/\/$/, '');
    items.push({ name: 'train.log', desc: logPath, objectName: logObj });
  }
  return items;
}

export function minioPathToObjectName(path?: string): string | undefined {
  if (!path) return undefined;
  // Worker stores artifacts under the full key training-results/<id>/artifacts/<file>
  // in the default MinIO bucket, so only strip the minio:// scheme (not path segments).
  const normalized = path.replace(/^minio:\/\//, '').replace(/\/$/, '');
  const parts = normalized.split('/');
  // Be tolerant of minio://<bucket>/training-results/... style paths.
  if (parts.length > 1 && parts[1] === 'training-results') {
    return parts.slice(1).join('/');
  }
  return normalized;
}

export function isLikelyDirectoryPath(path?: string) {
  if (!path) return false;
  const normalized = path.replace(/^minio:\/\//, '');
  if (normalized.endsWith('/')) return true;
  const basename = normalized.split('/').pop() || '';
  return !basename.includes('.');
}

export async function errorMessageFromDownloadError(error: any) {
  const data = error?.response?.data;
  if (data instanceof Blob) {
    try {
      const text = await data.text();
      const json = JSON.parse(text);
      return json?.errorMessage || json?.message || text;
    } catch {
      return '文件不存在或下载失败';
    }
  }
  return (
    error?.response?.data?.errorMessage ||
    error?.response?.data?.message ||
    error?.message ||
    '文件不存在或下载失败'
  );
}

export function resolveDetailLoadError(error: any): string {
  const status = error?.response?.status;
  const bizMessage =
    error?.info?.errorMessage || error?.info?.message || error?.message || '';
  const isPermissionDenied =
    status === 401 ||
    status === 403 ||
    /no permission|permission denied|无权|没有权限|无权限/i.test(
      String(bizMessage),
    );
  if (isPermissionDenied) {
    return '该训练任务由其他用户创建或已不存在，您没有权限查看详情。如需访问，请联系任务创建者或管理员。';
  }
  return bizMessage || '训练任务详情加载失败，请检查后端服务';
}
