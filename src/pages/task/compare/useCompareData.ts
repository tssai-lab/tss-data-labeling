/** 对比数据读取：任务详情、指标聚合和并发隔离；不控制图表或提交训练。 */

import { message } from 'antd';
import React, { useCallback, useRef, useState } from 'react';
import { MLFLOW_METRIC_KEYS } from '@/services/mlflow';
import { fetchMlflowMetricsBulk, fetchTaskDetail } from '@/services/platform';
import type { TaskMetricsData, TaskWithRunId } from './comparisonModel';
export function useCompareData(
  selectedRowKeys: React.Key[],
  taskList: API.TaskItem[],
) {
  const compareLoadSequence = useRef(0);

  const [metricsLoading, setMetricsLoading] = useState(false);

  const [metricsData, setMetricsData] = useState<TaskMetricsData[]>([]);

  const loadCompareData = useCallback(
    async (overrideIds?: string[]) => {
      const sequence = ++compareLoadSequence.current;
      const isCurrent = () => sequence === compareLoadSequence.current;
      const ids = [
        ...new Set((overrideIds ?? (selectedRowKeys as string[])).map(String)),
      ];
      if (ids.length < 2) {
        message.warning('合同要求至少选择 2 个训练任务进行同屏对比');
        return;
      }
      setMetricsLoading(true);
      try {
        const idSet = new Set(ids);
        const selectedTasks = taskList.filter((t) => idSet.has(String(t.id)));
        const details: TaskWithRunId[] = [];
        let detailFailures = 0;
        for (const id of ids) {
          try {
            const res = await fetchTaskDetail(id, { skipErrorHandler: true });
            if (!isCurrent()) return;
            const responseCode = (res as { code?: number } | undefined)?.code;
            const data = res?.data as
              | (TaskWithRunId & { run_id?: unknown })
              | undefined;
            // 读取失败或畸形响应不能作为“任务没有运行记录”的证据。
            if (
              res?.success === false ||
              (responseCode !== undefined && responseCode !== 200) ||
              !data ||
              typeof data !== 'object' ||
              Array.isArray(data)
            ) {
              throw new Error('任务详情响应无效');
            }
            const rawRunId =
              data.runId == null || data.runId === ''
                ? data.run_id
                : data.runId;
            const hasTaskId = typeof data.id === 'string' && !!data.id.trim();
            if (
              (rawRunId != null && typeof rawRunId !== 'string') ||
              (data.id != null && !hasTaskId && data.id !== '') ||
              (!hasTaskId && !(typeof rawRunId === 'string' && rawRunId.trim()))
            ) {
              throw new Error('任务详情缺少有效标识');
            }
            // 保留旧 run_id 兼容，但不修改请求层返回的共享对象。
            const d: TaskWithRunId = {
              ...data,
              runId:
                typeof rawRunId === 'string'
                  ? rawRunId.trim() || undefined
                  : undefined,
            };
            if (!d.id) d.id = id;
            if (!d.name) {
              d.name =
                selectedTasks.find((t) => String(t.id) === id)?.name || id;
            }
            // 实验编号和版本编号可能指向同一个版本，不能凑成两项对比。
            if (!details.some((item) => String(item.id) === String(d.id))) {
              details.push(d);
            }
          } catch {
            detailFailures += 1;
          }
        }
        const byId = new Map(selectedTasks.map((t) => [String(t.id), t]));
        if (!isCurrent()) return;
        const withRunId = details.filter((d) => d.runId);
        if (withRunId.length === 0) {
          setMetricsData([]);
          if (detailFailures > 0) {
            message.error(
              `${detailFailures} 个任务详情加载失败，请重试；不能据此判断没有指标`,
            );
          } else {
            message.warning(
              '所选任务尚无运行记录（Run ID），暂时无法读取训练指标',
            );
          }
          return;
        }
        const withoutRunId = details.length - withRunId.length;
        if (withoutRunId > 0) {
          message.info(
            `${withoutRunId} 个任务尚无运行记录（Run ID），已跳过；共 ${withRunId.length} 个任务拉取指标`,
          );
        }

        const results: TaskMetricsData[] = [];
        let failed = 0;
        for (const t of withRunId) {
          const meta = byId.get(String(t.id)) || t;
          try {
            const runId = t.runId;
            if (!runId) continue;
            const metrics = await fetchMlflowMetricsBulk(
              runId,
              MLFLOW_METRIC_KEYS as unknown as string[],
            );
            if (!isCurrent()) return;
            results.push({
              taskId: t.id,
              taskName: t.name || meta.name,
              modelName: meta.modelName || t.modelName || '-',
              datasetName: meta.datasetName || t.datasetName || '-',
              modelId: meta.modelId || t.modelId,
              datasetId: meta.datasetId || t.datasetId,
              modelVersionId: meta.modelVersionId || t.modelVersionId,
              datasetVersionId: meta.datasetVersionId || t.datasetVersionId,
              experimentId:
                (t as API.TaskItem).experimentId ||
                (meta as API.TaskItem).experimentId,
              versionNo:
                (t as API.TaskItem).versionNo ??
                (meta as API.TaskItem).versionNo,
              createTime:
                (t as API.TaskItem).createTime ||
                (meta as API.TaskItem).createTime,
              producedModelVersionId:
                (t as API.TaskItem).producedModelVersionId ||
                (meta as API.TaskItem).producedModelVersionId,
              runId,
              metrics,
            });
          } catch {
            failed += 1;
          }
        }
        if (!isCurrent()) return;
        const hasComparableMetric = MLFLOW_METRIC_KEYS.some(
          (key) =>
            results.filter((task) => task.metrics[key]?.length).length >= 2,
        );
        const readFailures = [
          detailFailures > 0 ? `${detailFailures} 个任务详情加载失败` : '',
          failed > 0 ? `${failed} 个任务的训练指标加载失败` : '',
        ]
          .filter(Boolean)
          .join('；');
        if (results.length < 2 || !hasComparableMetric) {
          setMetricsData([]);
          message.error(
            readFailures
              ? `${readFailures}，当前结果不足以形成有效对比，请重试`
              : results.length < 2
                ? '有效训练指标不足 2 个任务，无法形成合同要求的对比'
                : '所选任务没有共同核心指标，无法进行有效对比',
          );
        } else {
          setMetricsData(results);
        }
        if (results.length >= 2 && hasComparableMetric && readFailures) {
          const skipped = [
            detailFailures > 0 ? `${detailFailures} 个任务详情加载失败` : '',
            failed > 0 ? `${failed} 个指标拉取失败` : '',
          ]
            .filter(Boolean)
            .join('；');
          message.warning(
            `已加载 ${results.length} 个任务；另有 ${skipped}，已跳过`,
          );
        }
      } catch {
        if (!isCurrent()) return;
        setMetricsData([]);
        message.error('加载对比数据失败');
      } finally {
        if (isCurrent()) setMetricsLoading(false);
      }
    },
    [selectedRowKeys, taskList],
  );
  return {
    compareLoadSequence,
    metricsLoading,
    setMetricsLoading,
    metricsData,
    setMetricsData,
    loadCompareData,
  };
}
