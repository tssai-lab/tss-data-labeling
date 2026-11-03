import { MoreOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useParams, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Dropdown,
  Form,
  Input,
  Modal,
  message,
  Progress,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import InferenceLogPanel from '@/components/inference/InferenceLogPanel';
import TrainingMetricsPanel from '@/components/TrainingMetricsPanel';
import TrainingStatusBanner from '@/components/TrainingStatusBanner';
import {
  fetchTaskDetail,
  getExperimentVersion,
  publishTaskModel,
  publishTrainingModel,
  updateExperimentHyperParams,
} from '@/services/platform';
import {
  fetchTrainingPlans,
  type TrainingPlan,
} from '@/services/trainingPlans';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import {
  getCodeVersionDisplayLabel,
  getDatasetVersionDisplayLabel,
  getModelVersionDisplayLabel,
  preloadCodeVersionDisplayNames,
  preloadDatasetVersionDisplayNames,
  preloadTaskVersionDisplayNames,
} from '@/utils/taskDisplayNames';
import {
  isActiveTaskStatus,
  TASK_POST_FINISH_POLL_TIMES,
  TASK_STATUS_POLL_INTERVAL_MS,
} from '@/utils/trainingMetrics';
import { isTrainingTerminal } from '@/utils/trainingStatusDisplay';
import {
  buildConsistencyMetricsRows,
  CONSISTENCY_METRIC_KEYS,
  CONSISTENCY_PROFILE,
  hasMeaningfulHyperParams,
  isConsistencyProfileTask,
  isExperimentId,
  loadComparePool,
  mapVersionToTaskDetail,
  renderHyperParamsCell,
  renderHyperParamsDetail,
  resolveDetailLoadError,
  resolveTaskCreatedAt,
  resolveTaskDurationText,
  resolveTaskFinishedAt,
  saveComparePool,
  saveContinueTrainingPrefill,
  statusColor,
  statusText,
  type TaskDetailInfo,
} from './detailPresentation';
import { TrainingArtifactsList } from './TrainingArtifactsList';
import { resolveTrainingPlanDisplayName } from './trainingDetailPresentation.mjs';
import { useExperimentVersions } from './useExperimentVersions';

const TaskDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const versionNoParam = searchParams.get('versionNo');
  const listBackPath = useMemo(() => {
    const fromCurrent = searchParams.get('fromCurrent');
    const fromPageSize = searchParams.get('fromPageSize');
    const qs = new URLSearchParams();
    if (fromCurrent) qs.set('current', fromCurrent);
    if (fromPageSize) qs.set('pageSize', fromPageSize);
    const query = qs.toString();
    return query ? `/task/list?${query}` : '/task/list';
  }, [searchParams]);
  const detailQuerySuffix = useMemo(() => {
    const fromCurrent = searchParams.get('fromCurrent');
    const fromPageSize = searchParams.get('fromPageSize');
    const qs = new URLSearchParams();
    if (fromCurrent) qs.set('fromCurrent', fromCurrent);
    if (fromPageSize) qs.set('fromPageSize', fromPageSize);
    const query = qs.toString();
    return query ? `?${query}` : '';
  }, [searchParams]);
  const [taskInfo, setTaskInfo] = useState<TaskDetailInfo | null>(null);
  const [loadError, setLoadError] = useState('');
  const [loading, setLoading] = useState(true);
  const [runIdInput, setRunIdInput] = useState('');
  const [manualRunId, setManualRunId] = useState('');
  const [taskLastUpdatedAt, setTaskLastUpdatedAt] = useState('');
  const [remarkModalOpen, setRemarkModalOpen] = useState(false);
  const [remarkModalLoading, setRemarkModalLoading] = useState(false);
  const [publishingModel, setPublishingModel] = useState(false);
  const [remarkBase, setRemarkBase] =
    useState<API.TrainingExperimentVersion | null>(null);
  const [remarkForm] = Form.useForm();
  /** 同一实验下多版本对比：勾选版本记录 id */
  const [compareVersionKeys, setCompareVersionKeys] = useState<React.Key[]>([]);
  const [displayNamesReady, setDisplayNamesReady] = useState(0);
  const [versionHistoryPage, setVersionHistoryPage] = useState(1);
  const [versionHistoryPageSize, setVersionHistoryPageSize] = useState(10);
  const [trainingPlans, setTrainingPlans] = useState<TrainingPlan[]>([]);
  const [logDrawerOpen, setLogDrawerOpen] = useState(false);

  const renderCodeVersionCell = useCallback(
    (codeVersionId?: string) => {
      const id = codeVersionId?.trim();
      if (!id) return '-';
      const label = getCodeVersionDisplayLabel(id);
      return (
        <Tooltip title={label !== id ? id : undefined}>
          <span style={{ wordBreak: 'break-all', whiteSpace: 'normal' }}>
            {label}
          </span>
        </Tooltip>
      );
    },
    [displayNamesReady],
  );

  const runId = taskInfo?.runId || manualRunId;
  const experimentId = taskInfo?.experimentId;
  const {
    versions,
    setVersions,
    error: versionsError,
    loading: versionsLoading,
    refreshVersions,
  } = useExperimentVersions(experimentId);
  const trainingPlanDisplayName = resolveTrainingPlanDisplayName(
    taskInfo?.trainingProfile,
    trainingPlans,
  );
  const selectedResources = taskInfo?.runSpec?.resources;

  useEffect(() => {
    fetchTrainingPlans({ skipErrorHandler: true })
      .then((response) =>
        setTrainingPlans(Array.isArray(response?.data) ? response.data : []),
      )
      .catch(() => setTrainingPlans([]));
  }, []);

  const loadTaskDetail = useCallback(
    async (showLoading = false) => {
      if (!id) return;
      if (showLoading) {
        setLoading(true);
      }
      try {
        const parsedVersionNo = versionNoParam ? Number(versionNoParam) : NaN;
        let data: TaskDetailInfo | null = null;

        if (
          isExperimentId(id) &&
          Number.isFinite(parsedVersionNo) &&
          parsedVersionNo > 0
        ) {
          const res = await getExperimentVersion(id, parsedVersionNo, {
            skipErrorHandler: true,
          });
          if (res?.data) {
            data = mapVersionToTaskDetail(res.data);
          }
        } else {
          const res = await fetchTaskDetail(id, { skipErrorHandler: true });
          if (res?.data) {
            data = res.data as TaskDetailInfo;
            data.runId = data.runId || (res.data as any).run_id;
          }
        }

        if (data) {
          setTaskInfo(data);
          setVersions((current) =>
            current.map((version) =>
              version.id === data?.id
                ? {
                    ...version,
                    status: data.status,
                    progress: data.progress,
                    metrics: data.metrics,
                  }
                : version,
            ),
          );
          setLoadError('');
          await preloadTaskVersionDisplayNames(
            data.modelVersionId || (data as TaskDetailInfo).baseModelVersionId,
            data.datasetVersionId,
            (data as TaskDetailInfo).codeVersionId,
            { skipErrorHandler: true },
          );
          if ((data as TaskDetailInfo).producedModelVersionId) {
            await preloadTaskVersionDisplayNames(
              (data as TaskDetailInfo).producedModelVersionId,
              undefined,
              undefined,
              { skipErrorHandler: true },
            );
          }
          setDisplayNamesReady((t) => t + 1);
        } else {
          setTaskInfo(null);
          setLoadError('未找到训练任务，请确认任务 ID 是否正确');
        }
        setTaskLastUpdatedAt(new Date().toLocaleTimeString());
      } catch (error: any) {
        setTaskInfo(null);
        setLoadError(resolveDetailLoadError(error));
        setTaskLastUpdatedAt(new Date().toLocaleTimeString());
      } finally {
        if (showLoading) {
          setLoading(false);
        }
      }
    },
    [id, versionNoParam],
  );

  useEffect(() => {
    loadTaskDetail(true);
  }, [loadTaskDetail]);

  useEffect(() => {
    if (loading || !experimentId) return;
    if (window.location.hash === '#version-history') {
      window.requestAnimationFrame(() => {
        document
          .getElementById('version-history')
          ?.scrollIntoView({ behavior: 'smooth' });
      });
    }
  }, [loading, experimentId]);

  useEffect(() => {
    const modelPublishing = ['PENDING', 'PUBLISHING'].includes(
      taskInfo?.modelPublishStatus || '',
    );
    if (!id) return;

    // 训练中 / 发布中：持续刷新
    if (isActiveTaskStatus(taskInfo?.status) || modelPublishing) {
      const timer = window.setInterval(() => {
        loadTaskDetail(false);
      }, TASK_STATUS_POLL_INTERVAL_MS);
      return () => window.clearInterval(timer);
    }

    // 刚结束时后端常晚一点才回写 runId，再补拉几轮避免曲线空白
    if (isTrainingTerminal(taskInfo?.status) && !taskInfo?.runId) {
      let left = TASK_POST_FINISH_POLL_TIMES;
      const timer = window.setInterval(() => {
        left -= 1;
        loadTaskDetail(false);
        if (left <= 0) {
          window.clearInterval(timer);
        }
      }, TASK_STATUS_POLL_INTERVAL_MS);
      return () => window.clearInterval(timer);
    }

    return undefined;
  }, [
    id,
    loadTaskDetail,
    taskInfo?.modelPublishStatus,
    taskInfo?.runId,
    taskInfo?.status,
  ]);

  useEffect(() => {
    setVersionHistoryPage(1);
  }, [experimentId]);

  // 版本表只解析当前页展示名，避免一次对全部版本打详情
  useEffect(() => {
    if (!versions.length) return;
    let cancelled = false;
    const start = (versionHistoryPage - 1) * versionHistoryPageSize;
    const pageRows = versions.slice(start, start + versionHistoryPageSize);
    void (async () => {
      await preloadDatasetVersionDisplayNames(
        pageRows
          .map((item) => item.datasetVersionId)
          .filter(Boolean) as string[],
        { skipErrorHandler: true },
      );
      await preloadCodeVersionDisplayNames(
        pageRows.map((item) => item.codeVersionId).filter(Boolean) as string[],
        { skipErrorHandler: true },
      );
      if (!cancelled) {
        setDisplayNamesReady((t) => t + 1);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [versions, versionHistoryPage, versionHistoryPageSize]);

  const versionHistoryPagination = {
    current: versionHistoryPage,
    pageSize: versionHistoryPageSize,
    total: versions.length,
    showSizeChanger: true,
    pageSizeOptions: [10, 20, 50],
    showTotal: (total: number) => `共 ${total} 个版本`,
    onChange: (page: number, pageSize: number) => {
      setVersionHistoryPage(page);
      setVersionHistoryPageSize(pageSize);
    },
  };

  const handleContinueSameExperiment = async (
    record?: API.TrainingExperimentVersion,
  ) => {
    if (!experimentId) {
      message.warning('缺少训练编号');
      return;
    }
    let base =
      record ||
      versions.find((v) => v.id === taskInfo?.id) ||
      versions[versions.length - 1];
    if (!base) {
      message.warning('暂无可用版本配置');
      return;
    }

    // 继续训练需要结果模型：没有则先尝试发布
    if (!base.producedModelVersionId && base.status === 'success') {
      const hide = message.loading('正在发布该版本结果模型…', 0);
      try {
        const res = await publishTaskModel(base.id, {
          skipErrorHandler: true,
        });
        hide();
        if (res?.data?.producedModelVersionId) {
          base = { ...base, ...res.data };
          const baseId = base.id;
          setVersions((prev) =>
            prev.map((v) => (v.id === baseId ? { ...v, ...res.data } : v)),
          );
          if (taskInfo?.id === base.id) {
            setTaskInfo((prev) => (prev ? { ...prev, ...res.data } : prev));
          }
        } else {
          message.warning(
            res?.errorMessage ||
              '该版本尚无结果模型，进入创建页后请手动选择或先发布结果模型',
          );
        }
      } catch (error: any) {
        hide();
        message.warning(
          error?.message || '自动发布结果模型失败，进入创建页后请手动选择权重',
        );
      }
    } else if (!base.producedModelVersionId) {
      message.warning(
        '该版本尚无结果模型（需训练成功后发布），进入创建页后请手动选择权重',
      );
    }

    saveContinueTrainingPrefill(base);
    history.push(
      `/task/create?experimentId=${encodeURIComponent(experimentId)}&fromVersionId=${encodeURIComponent(base.id)}`,
    );
  };

  const handleTraceVersion = (versionRecordId: string) => {
    const target = versions.find((v) => v.id === versionRecordId);
    if (!target) return;
    history.push(
      `/task/detail/${encodeURIComponent(target.id)}${detailQuerySuffix}`,
    );
  };

  const handleJumpToLatestVersion = () => {
    if (!experimentId) return;
    const latest = versions[versions.length - 1];
    if (latest) {
      history.push(
        `/task/detail/${encodeURIComponent(latest.id)}${detailQuerySuffix}`,
      );
      return;
    }
    history.push(
      `/task/detail/${encodeURIComponent(experimentId)}${detailQuerySuffix}`,
    );
  };

  const handlePublishModel = async () => {
    if (!taskInfo?.id) return;
    setPublishingModel(true);
    try {
      await publishTrainingModel(taskInfo.id, { skipErrorHandler: true });
      message.success('模型已进入发布队列');
      await loadTaskDetail(false);
    } catch (error: any) {
      message.error(error?.errorMessage || error?.message || '发布模型失败');
    } finally {
      setPublishingModel(false);
    }
  };

  const handleUseProducedModel = () => {
    if (!taskInfo?.producedModelVersionId) return;
    const query = new URLSearchParams({
      modelVersionId: taskInfo.producedModelVersionId,
      trainingId: taskInfo.id,
    });
    if (taskInfo.datasetVersionId) {
      query.set('datasetVersionId', taskInfo.datasetVersionId);
    }
    if (taskInfo.trainingProfile) {
      query.set('trainingProfile', taskInfo.trainingProfile);
    }
    history.push(`/inference/workbench?${query.toString()}`);
  };

  const openUpdateRemark = (record: API.TrainingExperimentVersion) => {
    setRemarkBase(record);
    remarkForm.setFieldsValue({
      remark: record.remark || '',
    });
    setRemarkModalOpen(true);
  };

  const submitRemarkModal = async () => {
    const expId = experimentId || remarkBase?.experimentId;
    if (!expId || !remarkBase) {
      message.warning('缺少训练编号，无法操作');
      return;
    }
    try {
      const values = await remarkForm.validateFields();
      setRemarkModalLoading(true);
      await updateExperimentHyperParams(
        expId,
        remarkBase.versionNo,
        {
          hyperParams: remarkBase.hyperParams ?? {},
          remark: values.remark,
        },
        { skipErrorHandler: true },
      );
      message.success('已更新备注');
      setRemarkModalOpen(false);
      setRemarkBase(null);
      remarkForm.resetFields();
      await refreshVersions(expId);
      await loadTaskDetail(false);
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.errorMessage || e?.message || '操作失败，请重试');
    } finally {
      setRemarkModalLoading(false);
    }
  };

  useEffect(() => {
    if (!versions.length) {
      setCompareVersionKeys([]);
      return;
    }
    if (versions.length >= 2) {
      const first = versions[0];
      const last = versions[versions.length - 1];
      if (first && last) {
        setCompareVersionKeys([first.id, last.id]);
      }
      return;
    }
    const one = versions[0];
    if (one) {
      setCompareVersionKeys([one.id]);
    }
  }, [versions, taskInfo?.id]);

  if (loading) {
    return (
      <PageContainer
        title="训练结果详情"
        onBack={() => history.push(listBackPath)}
      >
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (!taskInfo) {
    return (
      <PageContainer
        title="训练结果详情"
        onBack={() => history.push(listBackPath)}
      >
        <Alert
          type="error"
          showIcon
          message={loadError || '训练任务不存在或加载失败'}
          action={
            <Space>
              <Button size="small" onClick={() => loadTaskDetail(true)}>
                重试
              </Button>
              <Button size="small" onClick={() => history.push(listBackPath)}>
                返回列表
              </Button>
            </Space>
          }
        />
      </PageContainer>
    );
  }

  const latestVersion = versions.length
    ? versions[versions.length - 1]
    : undefined;
  const viewingVersionNo = (taskInfo as any).versionNo as number | undefined;
  const isTracingHistorical =
    !!latestVersion && !!taskInfo.id && latestVersion.id !== taskInfo.id;
  const tracedVersionRecord =
    versions.find((v) => v.id === taskInfo.id) ||
    versions.find((v) => v.versionNo === viewingVersionNo);

  const compareVersionColumns: ColumnsType<API.TrainingExperimentVersion> = [
    {
      title: '版本',
      dataIndex: 'versionNo',
      width: 100,
      render: (v, record) => (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          {`v${v}`}
          {record.id === taskInfo.id ? <Tag color="blue">当前</Tag> : null}
        </Space>
      ),
    },
    {
      title: '训练代码版本',
      dataIndex: 'codeVersionId',
      render: (v: any) =>
        renderCodeVersionCell(v != null ? String(v) : undefined),
    },
    {
      title: '数据集版本',
      dataIndex: 'datasetVersionId',
      ellipsis: true,
      render: (v: any) => (
        <Tooltip title={v || ''}>
          <span>
            {getDatasetVersionDisplayLabel(v ? String(v) : undefined)}
          </span>
        </Tooltip>
      ),
    },
    {
      title: '超参数',
      dataIndex: 'hyperParams',
      ellipsis: true,
      render: (hp: any) => renderHyperParamsCell(hp),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      width: 140,
      ellipsis: true,
      render: (v: any) => v || '-',
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (v: string) => formatDisplayDateTime(v),
    },
  ];

  const handleCompareVersions = () => {
    if (compareVersionKeys.length < 2) {
      message.warning('请至少选择 2 个版本进行对比');
      return;
    }
    const qs = new URLSearchParams();
    if (experimentId) qs.set('experimentId', String(experimentId));
    qs.set('ids', (compareVersionKeys as string[]).join(','));
    history.push(`/task/compare?${qs.toString()}`);
  };

  return (
    <PageContainer
      title="训练结果详情"
      subTitle="查看训练配置、版本、指标、日志和产出文件"
      onBack={() => history.push(listBackPath)}
      extra={
        <Space wrap>
          {experimentId && versions.length > 0 && (
            <Space size={8}>
              <Typography.Text type="secondary">追溯版本</Typography.Text>
              <Select
                style={{ minWidth: 160 }}
                value={taskInfo.id}
                options={versions.map((v) => ({
                  value: v.id,
                  label: `v${v.versionNo}${v.id === latestVersion?.id ? '（最新）' : ''}`,
                }))}
                onChange={handleTraceVersion}
              />
            </Space>
          )}
          {experimentId && (
            <Button
              type="primary"
              onClick={() => handleContinueSameExperiment(tracedVersionRecord)}
            >
              基于此版本继续训练
            </Button>
          )}
          {isTracingHistorical && (
            <Button onClick={handleJumpToLatestVersion}>回到最新版本</Button>
          )}
          <Button onClick={() => setLogDrawerOpen(true)}>查看训练日志</Button>
          <Button onClick={() => history.push(listBackPath)}>返回列表</Button>
        </Space>
      }
    >
      {loadError && (
        <Alert
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          message={loadError}
          onClose={() => setLoadError('')}
        />
      )}

      {isTracingHistorical && viewingVersionNo != null && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`正在追溯历史版本 v${viewingVersionNo}`}
          description={
            <>
              下方展示的是该次训练提交时的配置快照（基础模型权重、训练代码、数据集版本、超参数），不会被修改。
              {latestVersion ? (
                <> 该实验最新版本为 v{latestVersion.versionNo}。</>
              ) : null}
            </>
          }
        />
      )}

      {experimentId &&
        versions.length > 1 &&
        !isTracingHistorical &&
        viewingVersionNo != null && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`当前为最新版本 v${viewingVersionNo}`}
            description="可在右上角「追溯版本」下拉框切换到历史版本，查看各次训练的配置对应关系。"
          />
        )}

      <TrainingStatusBanner
        status={taskInfo.status}
        progress={taskInfo.progress}
        errorMessage={(taskInfo as TaskDetailInfo).errorMessage}
        taskName={taskInfo.name}
        lastUpdatedAt={taskLastUpdatedAt}
        pollIntervalMs={TASK_STATUS_POLL_INTERVAL_MS}
        metrics={taskInfo.metrics as Record<string, unknown> | undefined}
      />

      <Card title="任务信息" style={{ marginBottom: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="任务名称">
            <strong>{taskInfo.name}</strong>
          </Descriptions.Item>
          <Descriptions.Item label="训练编号">
            <Space>
              <span style={{ fontFamily: 'monospace' }}>
                {experimentId || '-'}
              </span>
              {experimentId && (
                <Button
                  type="link"
                  size="small"
                  onClick={() => {
                    navigator.clipboard?.writeText(experimentId);
                    message.success('已复制训练编号');
                  }}
                >
                  复制
                </Button>
              )}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="版本号">
            <Space>
              <Tag color="blue">v{viewingVersionNo ?? '-'}</Tag>
              {isTracingHistorical && <Tag>历史追溯</Tag>}
              {latestVersion?.id === taskInfo.id && (
                <Tag color="green">最新</Tag>
              )}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="基础模型权重">
            <Tooltip
              title={
                (taskInfo as TaskDetailInfo).baseModelVersionId ||
                taskInfo.modelVersionId ||
                ''
              }
            >
              {getModelVersionDisplayLabel(
                (taskInfo as TaskDetailInfo).baseModelVersionId ||
                  taskInfo.modelVersionId,
              )}
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label="结果模型">
            {(taskInfo as TaskDetailInfo).producedModelVersionId ? (
              <Tooltip
                title={(taskInfo as TaskDetailInfo).producedModelVersionId}
              >
                {getModelVersionDisplayLabel(
                  (taskInfo as TaskDetailInfo).producedModelVersionId,
                )}
              </Tooltip>
            ) : (
              <Space wrap>
                <Typography.Text type="secondary">
                  {(taskInfo as TaskDetailInfo).modelPublishStatus
                    ? `未就绪（${(taskInfo as TaskDetailInfo).modelPublishStatus}）`
                    : '未发布'}
                </Typography.Text>
                {taskInfo.status === 'success' && (
                  <Button
                    type="link"
                    size="small"
                    style={{ padding: 0 }}
                    onClick={async () => {
                      try {
                        const res = await publishTaskModel(taskInfo.id, {
                          skipErrorHandler: true,
                        });
                        if (
                          !res?.success &&
                          !res?.data?.producedModelVersionId
                        ) {
                          message.error(
                            res?.errorMessage || '发布结果模型失败',
                          );
                          return;
                        }
                        message.success('结果模型已发布');
                        await loadTaskDetail(false);
                      } catch (error: any) {
                        message.error(error?.message || '发布结果模型失败');
                      }
                    }}
                  >
                    发布结果模型
                  </Button>
                )}
              </Space>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="数据集版本">
            <Tooltip title={taskInfo.datasetVersionId || ''}>
              {getDatasetVersionDisplayLabel(taskInfo.datasetVersionId)}
            </Tooltip>
          </Descriptions.Item>
          <Descriptions.Item label="训练代码版本" span={2}>
            {renderCodeVersionCell((taskInfo as TaskDetailInfo).codeVersionId)}
          </Descriptions.Item>
          {(taskInfo as TaskDetailInfo).trainingProfile && (
            <Descriptions.Item label="训练方案" span={2}>
              {trainingPlanDisplayName}
              <Typography.Text
                type="secondary"
                style={{ marginLeft: 8, fontSize: 12 }}
              >
                （内部 ID：
                <code>{(taskInfo as TaskDetailInfo).trainingProfile}</code>）
              </Typography.Text>
            </Descriptions.Item>
          )}
          {selectedResources?.profileId && (
            <Descriptions.Item label="资源规格">
              {selectedResources.profileId}
            </Descriptions.Item>
          )}
          {selectedResources?.gpuUuid && (
            <Descriptions.Item label="指定物理 GPU" span={2}>
              {selectedResources.gpuModel || 'NVIDIA GPU'} ·{' '}
              {selectedResources.gpuNodeName || '-'} · GPU{' '}
              {selectedResources.gpuHostIndex ?? '-'} ·{' '}
              <Typography.Text copyable>
                {selectedResources.gpuUuid}
              </Typography.Text>
            </Descriptions.Item>
          )}
          {selectedResources?.gpuMemoryLimitMiB && (
            <Descriptions.Item label="GPU 显存软预算">
              {selectedResources.gpuMemoryLimitMiB} MiB
            </Descriptions.Item>
          )}
          {hasMeaningfulHyperParams(
            (taskInfo as TaskDetailInfo).hyperParams,
          ) && (
            <Descriptions.Item label="超参数" span={2}>
              {renderHyperParamsDetail(
                (taskInfo as TaskDetailInfo).hyperParams,
              )}
            </Descriptions.Item>
          )}
          <Descriptions.Item label="创建时间">
            {formatDisplayDateTime(resolveTaskCreatedAt(taskInfo))}
          </Descriptions.Item>
          <Descriptions.Item label="完成时间">
            {formatDisplayDateTime(resolveTaskFinishedAt(taskInfo))}
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Space size={8}>
              <Tag color={statusColor(taskInfo.status)}>
                {statusText(taskInfo.status)}
              </Tag>
              {isActiveTaskStatus(taskInfo.status) && (
                <Tag color="processing">
                  自动刷新 · {TASK_STATUS_POLL_INTERVAL_MS / 1000}s
                </Tag>
              )}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="训练进度" span={2}>
            {typeof taskInfo.progress === 'number' ? (
              <div style={{ maxWidth: 420 }}>
                <Progress
                  percent={taskInfo.progress}
                  status={
                    taskInfo.status === 'failed'
                      ? 'exception'
                      : taskInfo.status === 'success'
                        ? 'success'
                        : isActiveTaskStatus(taskInfo.status)
                          ? 'active'
                          : 'normal'
                  }
                />
              </div>
            ) : (
              '-'
            )}
          </Descriptions.Item>
          <Descriptions.Item label="总耗时">
            {resolveTaskDurationText(taskInfo)}
          </Descriptions.Item>
          {taskLastUpdatedAt && (
            <Descriptions.Item label="状态更新时间">
              {taskLastUpdatedAt}
            </Descriptions.Item>
          )}
          {runId && (
            <Descriptions.Item label="运行记录编号（高级）" span={2}>
              <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
                {runId}
              </span>
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>

      {taskInfo.status === 'success' && taskInfo.trainingProfile && (
        <Card title="训练模型发布" style={{ marginBottom: 16 }}>
          <Descriptions column={2} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="发布状态">
              <Tag
                color={
                  taskInfo.modelPublishStatus === 'PUBLISHED'
                    ? 'success'
                    : taskInfo.modelPublishStatus === 'FAILED'
                      ? 'error'
                      : taskInfo.modelPublishStatus
                        ? 'processing'
                        : 'default'
                }
              >
                {taskInfo.modelPublishStatus === 'PUBLISHED'
                  ? '已发布'
                  : taskInfo.modelPublishStatus === 'PUBLISHING'
                    ? '正在发布'
                    : taskInfo.modelPublishStatus === 'PENDING'
                      ? '等待发布'
                      : taskInfo.modelPublishStatus === 'FAILED'
                        ? '发布失败'
                        : '尚未发布'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="推理模型版本">
              <Typography.Text copyable={!!taskInfo.producedModelVersionId}>
                {taskInfo.producedModelVersionId || '-'}
              </Typography.Text>
            </Descriptions.Item>
            {taskInfo.modelPublishedAt && (
              <Descriptions.Item label="发布时间">
                {taskInfo.modelPublishedAt}
              </Descriptions.Item>
            )}
            {taskInfo.modelArtifactSha256 && (
              <Descriptions.Item label="模型摘要">
                <Tooltip title={taskInfo.modelArtifactSha256}>
                  <Typography.Text code>
                    {taskInfo.modelArtifactSha256.slice(0, 16)}…
                  </Typography.Text>
                </Tooltip>
              </Descriptions.Item>
            )}
          </Descriptions>

          {taskInfo.modelPublishError && (
            <Alert
              type="error"
              showIcon
              message="模型发布失败"
              description={taskInfo.modelPublishError}
              style={{ marginBottom: 12 }}
            />
          )}

          <Space wrap>
            {taskInfo.modelPublishStatus === 'PUBLISHED' &&
              taskInfo.producedModelVersionId && (
                <Button type="primary" onClick={handleUseProducedModel}>
                  使用此模型推理
                </Button>
              )}
            {(!taskInfo.modelPublishStatus ||
              taskInfo.modelPublishStatus === 'FAILED') && (
              <Button loading={publishingModel} onClick={handlePublishModel}>
                {taskInfo.modelPublishStatus === 'FAILED'
                  ? '重新发布模型'
                  : '发布为模型'}
              </Button>
            )}
            {['PENDING', 'PUBLISHING'].includes(
              taskInfo.modelPublishStatus || '',
            ) && (
              <Typography.Text type="secondary">
                页面会自动刷新发布状态
              </Typography.Text>
            )}
          </Space>

          {isConsistencyProfileTask(taskInfo.metrics) && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 12 }}
              message="推理输入说明"
              description="该融合模型使用特征 JSONL 或对应数据集推理，不能直接输入原始图片；进入推理页后请选择兼容的融合模型推理脚本。"
            />
          )}
        </Card>
      )}

      <Card
        id="version-history"
        title="版本历史"
        extra={
          experimentId ? (
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>
              共 {versions.length} 个版本
            </span>
          ) : null
        }
        style={{ marginBottom: 16 }}
      >
        {versionsError && (
          <Alert
            type="error"
            showIcon
            message="版本历史读取失败"
            description={versionsError}
            action={
              <Button
                loading={versionsLoading}
                onClick={() => void refreshVersions()}
              >
                重试读取
              </Button>
            }
          />
        )}
        {!experimentId && (
          <Alert
            type="warning"
            showIcon
            message="当前记录缺少训练编号，无法加载版本历史"
          />
        )}
        {experimentId && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="版本与超参数说明"
            description="选择「追溯到此版本」可查看该次训练的配置快照。超参数为只读记录；需基于某一历史版本调整配置再训时，请使用「基于此版本继续训练」。"
          />
        )}
        <Table
          rowKey="id"
          size="small"
          pagination={versionHistoryPagination}
          dataSource={versions}
          scroll={{ x: 1080 }}
          rowClassName={(record) =>
            record.id === taskInfo.id ? 'ant-table-row-selected' : ''
          }
          locale={{
            emptyText: experimentId ? '暂无版本记录' : '缺少训练编号',
          }}
          columns={[
            {
              title: '版本',
              dataIndex: 'versionNo',
              width: 90,
              render: (v, record) => (
                <Space size={4}>
                  {`v${v}`}
                  {record.id === taskInfo.id ? (
                    <Tag color="blue">当前</Tag>
                  ) : null}
                </Space>
              ),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (s: string) => (
                <Tag color={statusColor(s)}>{statusText(s)}</Tag>
              ),
            },
            {
              title: '训练代码版本',
              dataIndex: 'codeVersionId',
              width: 160,
              ellipsis: true,
              render: (v: any) =>
                renderCodeVersionCell(v != null ? String(v) : undefined),
            },
            {
              title: '数据集版本',
              dataIndex: 'datasetVersionId',
              width: 160,
              ellipsis: true,
              render: (v: any) => (
                <Tooltip title={v || ''}>
                  <span>
                    {getDatasetVersionDisplayLabel(v ? String(v) : undefined)}
                  </span>
                </Tooltip>
              ),
            },
            {
              title: '超参数',
              dataIndex: 'hyperParams',
              width: 140,
              ellipsis: true,
              render: (hp: any) => renderHyperParamsCell(hp),
            },
            {
              title: '备注',
              dataIndex: 'remark',
              width: 120,
              ellipsis: true,
              render: (v: any) => v || '-',
            },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 160,
              render: (v: string) => formatDisplayDateTime(v),
            },
            {
              title: '操作',
              key: 'action',
              width: 168,
              fixed: 'right',
              render: (_: any, record: API.TrainingExperimentVersion) => (
                <Space size={0} wrap={false}>
                  <Button
                    type="link"
                    size="small"
                    style={{ paddingInline: 4 }}
                    onClick={() => handleTraceVersion(record.id)}
                  >
                    {record.id === taskInfo.id ? '当前追溯' : '追溯'}
                  </Button>
                  <Dropdown
                    trigger={['click']}
                    menu={{
                      items: [
                        {
                          key: 'continue',
                          label: '基于此版本继续训练',
                          onClick: () => handleContinueSameExperiment(record),
                        },
                        {
                          key: 'remark',
                          label: '修改备注',
                          onClick: () => openUpdateRemark(record),
                        },
                        {
                          key: 'pool',
                          label: '加入对比池',
                          onClick: () => {
                            const next = saveComparePool([
                              record.id,
                              ...loadComparePool(),
                            ]);
                            message.success(
                              `已加入对比池（共 ${next.length} 条）`,
                            );
                          },
                        },
                      ],
                    }}
                  >
                    <Button
                      type="link"
                      size="small"
                      style={{ paddingInline: 4 }}
                      aria-label="更多操作"
                    >
                      <MoreOutlined />
                    </Button>
                  </Dropdown>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={`修改 v${remarkBase?.versionNo ?? ''} 备注`}
        open={remarkModalOpen}
        onCancel={() => {
          setRemarkModalOpen(false);
          setRemarkBase(null);
          remarkForm.resetFields();
        }}
        onOk={submitRemarkModal}
        confirmLoading={remarkModalLoading}
        okText="保存备注"
        cancelText="取消"
        destroyOnClose
      >
        <div style={{ marginBottom: 12, color: '#8c8c8c', fontSize: 12 }}>
          仅更新备注说明，不会修改该版本已记录的超参数配置。
        </div>
        <Form form={remarkForm} layout="vertical">
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={3} placeholder="版本说明" />
          </Form.Item>
        </Form>
      </Modal>

      <Card
        title="同一训练 · 多版本对比"
        extra={
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>
            与上方“版本历史”使用同一组记录
          </span>
        }
        style={{ marginBottom: 16 }}
      >
        <div style={{ color: '#8c8c8c', fontSize: 12, marginBottom: 12 }}>
          勾选至少 2 个版本，前往“模型性能对比”查看曲线和指标差异。
        </div>
        <Table<API.TrainingExperimentVersion>
          size="small"
          rowKey="id"
          columns={compareVersionColumns}
          dataSource={versions}
          pagination={versionHistoryPagination}
          scroll={{ x: 900 }}
          locale={{ emptyText: '暂无版本记录' }}
          rowSelection={{
            type: 'checkbox',
            selectedRowKeys: compareVersionKeys,
            onChange: setCompareVersionKeys,
          }}
        />
        <Space style={{ marginTop: 16 }}>
          <Button
            type="primary"
            onClick={handleCompareVersions}
            disabled={compareVersionKeys.length < 2}
          >
            对比选中版本
          </Button>
          <Button onClick={() => history.push('/task/compare')}>
            打开对比页（自选）
          </Button>
        </Space>
      </Card>

      {isConsistencyProfileTask(taskInfo.metrics) && (
        <Card
          title="图文一致性训练指标"
          extra={
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>
              训练方案：图文一致性基线训练（{CONSISTENCY_PROFILE}）
            </span>
          }
          style={{ marginBottom: 16 }}
        >
          <Table
            size="small"
            pagination={false}
            rowKey="key"
            dataSource={buildConsistencyMetricsRows(taskInfo.metrics)}
            columns={[
              { title: '数据集', dataIndex: 'split', width: 80 },
              {
                title: '样本数',
                dataIndex: 'rows',
                width: 80,
                render: (_: unknown, record: any) => (
                  <Tooltip
                    title={`正样本 ${record.positive} / 负样本 ${record.negative}`}
                  >
                    <span>{record.rows}</span>
                  </Tooltip>
                ),
              },
              ...CONSISTENCY_METRIC_KEYS.map((metric) => ({
                title: metric,
                dataIndex: metric,
                render: (v: string) => (
                  <span style={{ fontFamily: 'monospace' }}>{v}</span>
                ),
              })),
            ]}
          />
        </Card>
      )}

      <Card
        title="训练指标"
        extra={
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>
            无数据时可使用高级方式指定运行记录
          </span>
        }
        style={{ marginBottom: 16 }}
      >
        <TrainingMetricsPanel
          runId={runId}
          taskStatus={taskInfo.status}
          progress={taskInfo.progress}
          backendMetrics={taskInfo.metrics}
          runIdInput={runIdInput}
          onRunIdInputChange={setRunIdInput}
          onManualRunId={(rid) => {
            if (!rid) {
              message.warning('请输入 Run ID');
              return;
            }
            setManualRunId(rid);
          }}
        />
      </Card>

      <Card title="训练产物" style={{ marginBottom: 16 }}>
        <TrainingArtifactsList
          taskId={taskInfo.id}
          taskStatus={taskInfo.status}
          outputPath={taskInfo.outputPath}
          logPath={taskInfo.logPath}
          files={taskInfo.files}
          trainingOutput={taskInfo.trainingOutput}
          consistencyProfile={isConsistencyProfileTask(taskInfo.metrics)}
          producedModelVersionId={
            (taskInfo as TaskDetailInfo).producedModelVersionId
          }
          modelArtifactPath={(taskInfo as TaskDetailInfo).modelArtifactPath}
          modelArtifactSizeBytes={
            (taskInfo as TaskDetailInfo).modelArtifactSizeBytes
          }
          modelPublishStatus={(taskInfo as TaskDetailInfo).modelPublishStatus}
          onPublished={() => loadTaskDetail(false)}
        />
      </Card>
      <Drawer
        title={`训练日志${taskInfo.name ? ` · ${taskInfo.name}` : ''}`}
        open={logDrawerOpen}
        width={860}
        destroyOnClose
        onClose={() => setLogDrawerOpen(false)}
        extra={
          <Tag color={statusColor(taskInfo.status)}>
            {statusText(taskInfo.status)}
          </Tag>
        }
      >
        <InferenceLogPanel
          logPath={taskInfo.logPath}
          status={taskInfo.status}
          title="训练日志"
          description="日志文件保存在对象存储中，不依赖 Pod 或 Job 是否仍存在；训练进行中会自动刷新，任务结束后仍可滚动查看和复制。"
        />
      </Drawer>
    </PageContainer>
  );
};

export default TaskDetail;
