/** 页面内部的类型、展示及计算规则；不承载页面状态或写操作。 */

import { Tooltip } from 'antd';
import * as echarts from 'echarts';
import React from 'react';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { enrichTaskItemsWithDisplayNames } from '@/utils/taskDisplayNames';
import { METRIC_LABELS as SHARED_METRIC_LABELS } from '@/utils/trainingMetrics';

export const COMPARE_POOL_KEY = 'comparePoolIds';

/** 对比页优先解析 URL/对比池中的任务，否则最多解析前 20 条，避免一次打满详情 */
export async function enrichFocusedTaskDisplayNames(
  list: API.TaskItem[],
  focusIds: string[],
  options?: { [key: string]: unknown },
) {
  if (!list.length) return list;
  const focus = new Set(focusIds.filter(Boolean).map(String));
  const targets = focus.size
    ? list.filter((item) => focus.has(String(item.id)))
    : list.slice(0, 20);
  if (!targets.length) return list;
  const enriched = await enrichTaskItemsWithDisplayNames(targets, options);
  const byId = new Map(enriched.map((item) => [String(item.id), item]));
  return list.map((item) => byId.get(String(item.id)) ?? item);
}

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

export const METRIC_LABELS: Record<string, string> = {
  ...SHARED_METRIC_LABELS,
  train_loss: '训练损失',
  val_accuracy: '验证准确率',
  val_mAP50: '验证 mAP50',
  val_mAP50_95: '验证 mAP50-95',
};

/** 任务项（带 runId） */
export type TaskWithRunId = API.TaskItem & { runId?: string };

/** 每个任务的指标数据 */
export type TaskMetricsData = {
  taskId: string;
  taskName: string;
  modelName: string;
  datasetName: string;
  modelId?: string;
  datasetId?: string;
  modelVersionId?: string;
  datasetVersionId?: string;
  experimentId?: string;
  versionNo?: number;
  createTime?: string;
  producedModelVersionId?: string;
  runId: string;
  metrics: Record<string, { step: number; value: number }[]>;
};

export type ComparableGroup = {
  /** experiment：同一训练不同版本；modelDataset：同模型+同数据集 */
  kind: 'experiment' | 'modelDataset';
  slug: string;
  title: string;
  modelName: string;
  datasetName: string;
  experimentId?: string;
  tasks: TaskMetricsData[];
};

export function lastPoint(series?: { step: number; value: number }[]) {
  if (!series?.length) return null;
  const sorted = [...series].sort((a, b) => a.step - b.step);
  return sorted[sorted.length - 1] ?? null;
}

export function formatNum(v: number | null | undefined, digits = 4) {
  if (v == null || Number.isNaN(v)) return '-';
  return Number(v).toFixed(digits);
}

/** 版本展示：同名任务靠版本号区分 */
export function formatVersionNo(versionNo?: number) {
  return versionNo != null ? `第 ${versionNo} 版` : '-';
}

/** 截断 ID，完整值放 title */
export function shortId(id?: string, keep = 10) {
  if (!id) return '-';
  return id.length > keep ? `${id.slice(0, keep)}…` : id;
}

/** 曲线图例：名称 + 版本 + 短任务 ID，避免多条同名线无法辨认 */
export function formatTaskSeriesName(
  task: Pick<TaskMetricsData, 'taskName' | 'versionNo' | 'taskId'>,
) {
  const parts = [task.taskName || '未命名'];
  if (task.versionNo != null) parts.push(`v${task.versionNo}`);
  parts.push(shortId(task.taskId, 8));
  return parts.join(' · ');
}

/** 性能提升图横轴短标签：优先版本 + 短 ID，名称过长时压缩 */
export function formatImprovementAxisLabel(
  task: Pick<TaskMetricsData, 'taskName' | 'versionNo' | 'taskId'>,
) {
  const ver = task.versionNo != null ? `v${task.versionNo}` : null;
  const id = shortId(task.taskId, 8);
  const name = (task.taskName || '未命名').slice(0, 12);
  return [ver, id, name].filter(Boolean).join('\n');
}

/** ECharts 的 HTML 提示不会自动转义业务名称，必须按纯文本处理。 */
export function escapeTooltipText(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 性能提升图 tooltip：数值优先，身份信息次要 */
export function formatImprovementTooltipHtml(
  task: TaskMetricsData,
  valueLines: string[],
) {
  const identity = [
    task.versionNo != null ? `第 ${task.versionNo} 版` : null,
    shortId(task.taskId, 10),
    task.taskName || '未命名',
  ]
    .filter(Boolean)
    .join(' · ');
  const detail = [
    task.experimentId ? `训练 ${shortId(task.experimentId, 14)}` : null,
    task.createTime ? formatDisplayDateTime(task.createTime) : null,
  ]
    .filter(Boolean)
    .join(' · ');
  return [
    ...valueLines.map(
      (line) =>
        `<div style="font-size:13px;font-weight:600">${escapeTooltipText(line)}</div>`,
    ),
    `<div style="margin-top:6px;opacity:.85">${escapeTooltipText(identity)}</div>`,
    detail
      ? `<div style="margin-top:2px;font-size:11px;opacity:.7">${escapeTooltipText(detail)}</div>`
      : null,
  ]
    .filter(Boolean)
    .join('');
}

/** 表格身份列：一行主名称 + 一行次要标识，悬停看完整信息 */
export function TaskIdentityCell(props: {
  taskName?: string;
  versionNo?: number;
  experimentId?: string;
  taskId?: string;
  createTime?: string;
  modelName?: string;
  datasetName?: string;
}) {
  const {
    taskName,
    versionNo,
    experimentId,
    taskId,
    createTime,
    modelName,
    datasetName,
  } = props;
  const metaParts = [
    versionNo != null ? `v${versionNo}` : null,
    taskId ? shortId(taskId, 8) : null,
    createTime ? formatDisplayDateTime(createTime) : null,
  ].filter(Boolean);
  const tipLines = [
    taskName ? `名称：${taskName}` : null,
    versionNo != null ? `训练版本：第 ${versionNo} 版` : null,
    experimentId ? `训练编号：${experimentId}` : null,
    taskId ? `任务 ID：${taskId}` : null,
    createTime ? `创建时间：${formatDisplayDateTime(createTime)}` : null,
    modelName && modelName !== '-' ? `模型：${modelName}` : null,
    datasetName && datasetName !== '-' ? `数据集：${datasetName}` : null,
  ].filter(Boolean) as string[];
  const body = (
    <div style={{ lineHeight: 1.35 }}>
      <div style={{ fontWeight: 500 }}>{taskName || '未命名'}</div>
      {metaParts.length > 0 && (
        <div style={{ color: '#8c8c8c', fontSize: 12 }}>
          {metaParts.join(' · ')}
        </div>
      )}
    </div>
  );
  if (!tipLines.length) return body;
  return (
    <Tooltip
      title={
        <div style={{ whiteSpace: 'pre-line' }}>{tipLines.join('\n')}</div>
      }
    >
      {body}
    </Tooltip>
  );
}

export function isLowerBetter(metricKey: string) {
  return metricKey.toLowerCase().includes('loss');
}

/** 相对首个模型版本的提升率；loss 下降视为提升，其余指标上升视为提升。 */
export function relativeImprovement(
  value: number,
  baseline: number,
  lowerIsBetter: boolean,
) {
  if (Math.abs(baseline) < 1e-9) {
    return Math.abs(value - baseline) < 1e-9 ? 0 : null;
  }
  const delta = lowerIsBetter ? baseline - value : value - baseline;
  return (delta / Math.abs(baseline)) * 100;
}

/** 复用或重建 echarts 实例（DOM 换了 / 已 dispose 则重建） */
export function ensureEcharts(
  store: React.MutableRefObject<Record<string, echarts.ECharts | null>>,
  key: string,
  el: HTMLDivElement,
): echarts.ECharts {
  const prev = store.current[key];
  if (prev && !prev.isDisposed() && prev.getDom() === el) {
    return prev;
  }
  if (prev && !prev.isDisposed()) {
    prev.dispose();
  }
  const chart = echarts.init(el);
  store.current[key] = chart;
  return chart;
}

export const TASK_COLORS = [
  '#5470c6',
  '#91cc75',
  '#fac858',
  '#ee6666',
  '#73c0de',
  '#3ba272',
  '#fc8452',
  '#9a60b4',
];

/** 只使用稳定资产标识分组；名称可能重复，不能作为“同一模型”的证据。 */
export function modelDatasetGroupKey(r: TaskMetricsData): string | null {
  const modelKey = (r.modelId || r.modelVersionId || '').trim();
  const datasetKey = (r.datasetId || r.datasetVersionId || '').trim();
  return modelKey && datasetKey ? `${modelKey}\x1E${datasetKey}` : null;
}

/** ref / echarts 实例用的短键，避免特殊字符问题 */
export function safeSlug(raw: string): string {
  return raw.replace(/\s+/g, '_').replace(/[^\w\u4e00-\u9fa5_-]/g, '_');
}

export function taskIdSetKey(tasks: TaskMetricsData[]): string {
  return [...tasks.map((t) => String(t.taskId))].sort().join(',');
}

export function sortTasksForCurve(tasks: TaskMetricsData[]): TaskMetricsData[] {
  return [...tasks].sort((a, b) => {
    const va = a.versionNo;
    const vb = b.versionNo;
    if (va != null && vb != null && va !== vb) return va - vb;
    const ta = Date.parse(a.createTime || '');
    const tb = Date.parse(b.createTime || '');
    if (Number.isFinite(ta) && Number.isFinite(tb) && ta !== tb) return ta - tb;
    return String(a.taskName).localeCompare(String(b.taskName));
  });
}

/** 性能提升分组：同一 experimentId，或具有稳定资产标识的同模型+同数据集。 */
export function buildImprovementGroups(
  data: TaskMetricsData[],
): ComparableGroup[] {
  const groups: ComparableGroup[] = [];
  const expMemberSets = new Set<string>();

  const byExp = new Map<string, TaskMetricsData[]>();
  for (const r of data) {
    const expId = (r.experimentId || '').trim();
    if (!expId) continue;
    if (!byExp.has(expId)) byExp.set(expId, []);
    byExp.get(expId)?.push(r);
  }
  for (const [expId, list] of byExp) {
    if (list.length < 2) continue;
    const tasks = sortTasksForCurve(list);
    expMemberSets.add(taskIdSetKey(tasks));
    const head = tasks[0];
    groups.push({
      kind: 'experiment',
      slug: safeSlug(`exp_${expId}`),
      title: `同一训练 · ${expId}`,
      modelName: head?.modelName || '-',
      datasetName: head?.datasetName || '-',
      experimentId: expId,
      tasks,
    });
  }

  const byMd = new Map<string, TaskMetricsData[]>();
  for (const r of data) {
    const k = modelDatasetGroupKey(r);
    if (!k) continue;
    if (!byMd.has(k)) byMd.set(k, []);
    byMd.get(k)?.push(r);
  }
  for (const [, list] of byMd) {
    if (list.length < 2) continue;
    const tasks = sortTasksForCurve(list);
    // 与某个「同一训练」组完全重合则跳过，避免重复展示
    if (expMemberSets.has(taskIdSetKey(tasks))) continue;
    const head = tasks[0];
    if (!head) continue;
    groups.push({
      kind: 'modelDataset',
      slug: safeSlug(`${head.modelName}|||${head.datasetName}`),
      title: `同模型同数据集 · ${head.modelName} / ${head.datasetName}`,
      modelName: head.modelName,
      datasetName: head.datasetName,
      tasks,
    });
  }

  return groups;
}

/** 任务列表接口：兼容 { data: TaskItem[] } 与 { data: { data, total } } */
export function normalizeTaskListResponse(res: any): API.TaskItem[] {
  if (
    !res ||
    res.success === false ||
    res.errorCode ||
    (res.code !== undefined && res.code !== 200)
  )
    throw new Error('任务目录响应异常');
  const d = res?.data;
  const list = Array.isArray(d) ? d : d?.data;
  if (
    !Array.isArray(list) ||
    list.some((row) => !row || typeof row.id !== 'string' || !row.id.trim())
  )
    throw new Error('任务目录响应格式异常');
  return list;
}

/** 详情返回的训练实验版本 → 对比页任务行（列表里可能只有每个实验最新一条，需补全历史版本） */
export function experimentVersionToTaskRow(
  d: any,
  hint?: API.TaskItem,
): API.TaskItem {
  return {
    id: d.id,
    name: d.name || `训练 · 第 ${d.versionNo ?? '?'} 版`,
    createTime: d.createTime || d.createdAt || '',
    status: d.status || 'pending',
    progress: typeof d.progress === 'number' ? d.progress : 0,
    modelVersionId: d.modelVersionId || hint?.modelVersionId,
    datasetVersionId: d.datasetVersionId || hint?.datasetVersionId,
    modelName:
      d.modelName && !/^(model-ver-|dataset-ver-)/i.test(d.modelName)
        ? d.modelName
        : hint?.modelName,
    datasetName:
      d.datasetName && !/^(model-ver-|dataset-ver-)/i.test(d.datasetName)
        ? d.datasetName
        : hint?.datasetName,
    experimentId: d.experimentId,
    versionNo: d.versionNo,
    producedModelVersionId:
      d.producedModelVersionId || hint?.producedModelVersionId,
  };
}
