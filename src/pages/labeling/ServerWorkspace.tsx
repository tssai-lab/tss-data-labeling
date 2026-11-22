import { PlusOutlined } from '@ant-design/icons';
import { history, Link, useLocation, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  type Project,
  post,
} from '@/services/labeling';
import { type LiveExecution, ownerApi } from '@/services/labelingOwner';
import {
  availableProjectTypes,
  type ProjectTaskType,
  type RegisteredTaskPlugin,
  taskTypeFilterKey,
} from '@/services/labelingTemplates.mjs';
import {
  collectPages,
  type InputResource,
  type NativeProject,
  type NativeSample,
} from '@/services/nativeLabeling';
import {
  normalizeWorkspaceProject,
  projectPanel,
} from '@/services/projectCompatibility.mjs';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import styles from './index.less';
import NativeAi, { type DemoResult } from './NativeAi';
import NativeDataPreparation from './NativeDataPreparation';
import NativeOutcome from './NativeOutcome';
import NativePublish, { type ReworkScope } from './NativePublish';
import NativeReview from './NativeReview';
import NativeSpecSettings from './NativeSpecSettings';
import ProjectBreadcrumbs from './ProjectBreadcrumbs';
import ProjectManagement from './ProjectManagement';
import StatusBadge from './StatusBadge';
import WorkspaceLayout from './WorkspaceLayout';

type PrelabelOutput = {
  sampleId: string;
  status: string;
  result: {
    result?: { from_name?: string; value?: Record<string, unknown> }[];
  } | null;
  confidence: number | null;
  errorMessage?: string;
  errorCode?: string;
  reviewable: boolean;
};

type PrelabelReceipt = {
  executionId: string;
  specVersionId: string;
  sampleIds: string[];
  config: { modelId: string; instructions: string };
};

const receiptKey = (projectId: string) => `labeling-prelabel:${projectId}`;

const finishedExecutions = new Set([
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
]);

function candidateLabel(output: PrelabelOutput) {
  const names = (output.result?.result || []).flatMap(({ value }) =>
    Object.entries(value || {}).flatMap(([key, entries]) => {
      if (
        !(key === 'choices' || key.endsWith('labels')) ||
        !Array.isArray(entries)
      )
        return [];
      return entries
        .filter((entry): entry is string => typeof entry === 'string')
        .map((label) => {
          if (
            key === 'timeserieslabels' &&
            typeof value?.start === 'number' &&
            typeof value.end === 'number'
          )
            return `${label}（${value.start}–${value.end}）`;
          return label;
        });
    }),
  );
  return [...new Set(names)].join('、') || '未生成标注区域';
}

const statusName: Record<string, string> = {
  DRAFT: '草稿',
  OPEN: '进行中',
  PAUSED: '已暂停',
  ARCHIVED: '已归档',
  COMPLETED: '已完成',
};
const taskName: Record<string, string> = {
  TIME_SERIES: '时序标注',
  TIMESERIES_ANNOTATION: '时序标注',
  COMPUTER_VISION: '图像标注',
  IMAGE_CLASSIFICATION: '图像标注',
};
export default function ServerWorkspace() {
  const { projectId } = useParams<{ projectId?: string }>(),
    location = useLocation();
  const { message } = App.useApp();
  const [projects, setProjects] = useState<Project[]>([]),
    [project, setProject] = useState<NativeProject>(),
    [samples, setSamples] = useState<NativeSample[]>([]),
    [resources, setResources] = useState<InputResource[]>([]),
    [reviews, setReviews] = useState<any[]>([]),
    [submissions, setSubmissions] = useState<any[]>([]),
    [snapshots, setSnapshots] = useState<any[]>([]);
  const [aiSession, setAiSession] = useState<{
    projectId: string;
    samples: NativeSample[];
    version: NativeProject['specVersions'][number];
    config: { modelId: string; instructions: string };
    execution: LiveExecution;
    results: DemoResult[];
  }>();
  const [projectNameQuery, setProjectNameQuery] = useState('');
  const [projectDescriptionQuery, setProjectDescriptionQuery] = useState('');
  const [projectStatus, setProjectStatus] = useState('ALL');
  const [projectTaskType, setProjectTaskType] = useState('ALL');
  const [createdAtRange, setCreatedAtRange] = useState<
    [Dayjs | null, Dayjs | null] | null
  >(null);
  const [taskTypes, setTaskTypes] = useState<ProjectTaskType[]>([]);
  const [reload, setReload] = useState(0),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [creating, setCreating] = useState(false),
    [name, setName] = useState(''),
    [description, setDescription] = useState(''),
    [type, setType] = useState(''),
    [tab, setTab] = useState('data'),
    [specId, setSpecId] = useState(''),
    [suggestion, setSuggestion] = useState(''),
    [rework, setRework] = useState<ReworkScope>();
  const [publishSelection, setPublishSelection] = useState<string[]>([]);
  const [publishMode, setPublishMode] = useState<'HUMAN' | 'AI'>('HUMAN');
  const guard = useRef<() => boolean>(() => true),
    saving = useRef(false),
    internalNavigation = useRef(false);
  const navigate = (path: string) => {
    internalNavigation.current = true;
    history.push(path);
    internalNavigation.current = false;
  };
  const refresh = useCallback(() => setReload((value) => value + 1), []);
  useEffect(() => {
    const query = new URLSearchParams(location.search);
    setTab(projectPanel(location.pathname, query));
  }, [location.pathname, location.search]);
  useEffect(() => {
    const unblock = history.block((transition) => {
      if (internalNavigation.current || (!saving.current && guard.current())) {
        unblock();
        transition.retry();
      }
    });
    return unblock;
  }, [projectId, project?.taskType, location.pathname, location.search]);
  useEffect(() => {
    setAiSession(undefined);
    setPublishMode('HUMAN');
    setSpecId('');
    setSuggestion('');
    setRework(undefined);
    setPublishSelection([]);
  }, [projectId]);
  useEffect(() => {
    const abort = new AbortController();
    setError('');
    setLoading(true);
    void (async () => {
      if (!projectId) {
        const [values, types] = await Promise.all([
          collectPages<Project>('/projects?scope=owned', abort.signal),
          labelingJson<RegisteredTaskPlugin[]>('/task-templates', {
            signal: abort.signal,
          }),
        ]);
        if (!abort.signal.aborted) {
          setProjects(values);
          setTaskTypes(availableProjectTypes(types));
          setProject(undefined);
        }
        return;
      }
      const detail = await labelingJson<NativeProject>(
        `/projects/${projectId}`,
        { signal: abort.signal },
      );
      const values = await Promise.all([
        collectPages<NativeSample>(
          `/projects/${projectId}/input-samples`,
          abort.signal,
        ),
        collectPages<InputResource>(
          `/projects/${projectId}/input-resources`,
          abort.signal,
        ),
        collectPages<any>(
          `/projects/${projectId}/sample-reviews`,
          abort.signal,
        ),
        collectPages<any>(`/projects/${projectId}/submissions`, abort.signal),
        collectPages<any>(
          `/projects/${projectId}/final-snapshots`,
          abort.signal,
        ),
      ]);
      if (!abort.signal.aborted) {
        setProject(normalizeWorkspaceProject(detail));
        setSamples(values[0]);
        setResources(values[1]);
        setReviews(values[2]);
        setSubmissions(values[3]);
        setSnapshots(values[4]);
      }
    })()
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [projectId, reload]);
  const run = async (action: () => Promise<void>) => {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      saving.current = false;
      setBusy(false);
    }
  };
  useEffect(() => {
    if (
      !projectId ||
      project?.projectId !== projectId ||
      !samples.length ||
      aiSession
    )
      return;
    const abort = new AbortController();
    const restoringProjectId = projectId;
    async function restore() {
      try {
        const stored = sessionStorage.getItem(receiptKey(restoringProjectId));
        if (!stored) return;
        const receipt: PrelabelReceipt = JSON.parse(stored);
        const version = project?.specVersions.find(
          (item) => item.specVersionId === receipt.specVersionId,
        );
        if (!version) return;
        const selected = new Set(receipt.sampleIds);
        const chosen = samples.filter(({ sampleId }) => selected.has(sampleId));
        const execution = await labelingJson<LiveExecution>(
          `/ai-executions/${encodeURIComponent(receipt.executionId)}`,
          { signal: abort.signal },
        );
        if (
          abort.signal.aborted ||
          execution.projectId !== projectId ||
          execution.specVersionId !== version.specVersionId
        )
          return;
        setAiSession(
          (previous) =>
            previous || {
              projectId: restoringProjectId,
              samples: chosen,
              version,
              config: receipt.config,
              execution,
              results: [],
            },
        );
      } catch (e) {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      }
    }
    void restore();
    return () => abort.abort();
  }, [projectId, project, samples, aiSession]);

  const activeExecutionId =
    aiSession && aiSession.projectId === projectId
      ? aiSession.execution.executionId
      : undefined;
  const activeSamples = aiSession?.samples;
  useEffect(() => {
    if (!activeExecutionId || !activeSamples) return;
    const abort = new AbortController();
    const samplesById = new Map(
      activeSamples.map((sample) => [sample.sampleId, sample]),
    );
    const path = `/ai-executions/${encodeURIComponent(activeExecutionId)}`;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const execution = await labelingJson<LiveExecution>(path, {
          signal: abort.signal,
        });
        const records = await collectPages<PrelabelOutput>(
          `${path}/results`,
          abort.signal,
        );
        if (abort.signal.aborted) return;
        const terminal = finishedExecutions.has(execution.status);
        const saved = terminal
          ? await collectPages<{
              sampleId: string;
              specVersionId: string;
              executionId: string | null;
              status: string;
              reason?: string;
            }>(
              `/projects/${encodeURIComponent(execution.projectId)}/sample-reviews`,
              abort.signal,
            )
          : [];
        if (abort.signal.aborted) return;
        const accepted = new Map(
          saved
            .filter(
              (row) =>
                row.specVersionId === execution.specVersionId &&
                row.executionId === execution.executionId &&
                row.status === 'ACCEPTED',
            )
            .map((row) => [row.sampleId, row]),
        );
        const results = records.flatMap<DemoResult>((output) => {
          const sample = samplesById.get(output.sampleId);
          if (!sample || output.status === 'PENDING') return [];
          const succeeded = output.status === 'SUCCEEDED';
          const rationale = output.result?.result?.find(
            (entry) => entry.from_name === 'energy_rationale',
          )?.value?.text;
          return [
            {
              sample,
              label: succeeded ? candidateLabel(output) : '—',
              rationale: Array.isArray(rationale)
                ? rationale
                    .filter((line) => typeof line === 'string')
                    .join('\n')
                : undefined,
              confidence: output.confidence,
              status: succeeded
                ? accepted.has(output.sampleId)
                  ? '已确认'
                  : '待核查'
                : '执行失败',
              reviewable: output.reviewable,
              reason: output.errorMessage || output.errorCode,
            },
          ];
        });
        setAiSession((session) =>
          session && session.execution.executionId === activeExecutionId
            ? { ...session, execution, results }
            : session,
        );
        if (!terminal) timer = setTimeout(poll, 2500);
      } catch (e) {
        if (!abort.signal.aborted) {
          setError(describeLabelingError(e));
          timer = setTimeout(poll, 5000);
        }
      }
    }
    void poll();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [activeExecutionId, activeSamples]);

  const current = project?.projectId === projectId ? project : undefined;
  const filteredProjects = projects.filter((value) => {
    const nameQuery = projectNameQuery.trim().toLocaleLowerCase();
    const descriptionQuery = projectDescriptionQuery.trim().toLocaleLowerCase();
    const createdAt = value.createdAt ? dayjs(value.createdAt) : null;
    const [startDate, endDate] = createdAtRange || [];
    return (
      value.name.toLocaleLowerCase().includes(nameQuery) &&
      (value.description || '')
        .toLocaleLowerCase()
        .includes(descriptionQuery) &&
      (projectTaskType === 'ALL' ||
        taskTypeFilterKey(value.taskType) === projectTaskType) &&
      (!startDate ||
        Boolean(
          createdAt?.isValid() && !createdAt.isBefore(startDate, 'day'),
        )) &&
      (!endDate ||
        Boolean(createdAt?.isValid() && !createdAt.isAfter(endDate, 'day'))) &&
      (projectStatus === 'ALL' || value.status === projectStatus)
    );
  });
  const version =
    current?.specVersions.find((value) => value.specVersionId === specId) ||
    current?.specVersions[0];
  return (
    <WorkspaceLayout
      workbench
      project={current}
      beforeAccountAction={() => !saving.current && guard.current()}
    >
      <Space
        direction="vertical"
        size="middle"
        className={styles.managerContent}
        style={{ width: '100%' }}
      >
        {!projectId ? (
          <div className={styles.heading}>
            <div>
              <ProjectBreadcrumbs current="我的项目" />
              <h1>数据标注工作空间</h1>
              <p className={styles.headingHint}>
                创建图像或时序项目，再配置标注模板和数据；发布任务后核查并导出成果。
              </p>
            </div>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={loading}
              onClick={() => {
                setName('');
                setDescription('');
                setType('');
                setCreating(true);
              }}
            >
              创建项目
            </Button>
          </div>
        ) : (
          <div className={styles.managerToolbar}>
            <Space wrap>
              <Link to="/labeling">我的项目</Link>
              {current && (
                <>
                  <span>/</span>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {current.name}
                  </Typography.Title>
                  <StatusBadge status={current.status}>
                    {statusName[current.status]}
                  </StatusBadge>
                </>
              )}
            </Space>
          </div>
        )}
        {error && <Alert type="error" showIcon message={error} />}
        {loading && !current ? (
          <Spin tip="正在读取项目">
            <div style={{ minHeight: 180 }} />
          </Spin>
        ) : !projectId ? (
          <>
            <Card
              title="我创建的项目"
              extra={
                <Button loading={loading} onClick={refresh}>
                  刷新
                </Button>
              }
            >
              <div className={styles.projectListTools}>
                <Input
                  allowClear
                  aria-label="按项目名称搜索"
                  placeholder="按项目名称搜索"
                  value={projectNameQuery}
                  onChange={(event) => setProjectNameQuery(event.target.value)}
                />
                <Input
                  allowClear
                  aria-label="按项目说明搜索"
                  placeholder="按项目说明搜索"
                  value={projectDescriptionQuery}
                  onChange={(event) =>
                    setProjectDescriptionQuery(event.target.value)
                  }
                />
                <Select
                  aria-label="按任务类型筛选"
                  value={projectTaskType}
                  onChange={setProjectTaskType}
                  options={[
                    { value: 'ALL', label: '全部任务类型' },
                    ...Array.from(
                      new Set(
                        projects.map((value) =>
                          taskTypeFilterKey(value.taskType),
                        ),
                      ),
                    ).map((value) => ({
                      value,
                      label:
                        value === 'COMPUTER_VISION'
                          ? '图像标注'
                          : value === 'TIME_SERIES'
                            ? '时序标注'
                            : taskName[value] || value,
                    })),
                  ]}
                />
                <DatePicker.RangePicker
                  aria-label="按创建时间筛选"
                  value={createdAtRange}
                  onChange={(value) => setCreatedAtRange(value)}
                  format="YYYY-MM-DD"
                  placeholder={['开始日期', '结束日期']}
                />
                <Select
                  aria-label="按项目状态筛选"
                  value={projectStatus}
                  onChange={setProjectStatus}
                  options={[
                    { value: 'ALL', label: '全部状态' },
                    ...['DRAFT', 'OPEN', 'PAUSED', 'ARCHIVED'].map((value) => ({
                      value,
                      label: statusName[value],
                    })),
                  ]}
                />
              </div>
              <Typography.Text
                type="secondary"
                className={styles.projectListCount}
              >
                已加载 {projects.length} 个项目 · 当前匹配{' '}
                {filteredProjects.length} 个
              </Typography.Text>
              <Table
                rowKey="projectId"
                loading={loading}
                dataSource={filteredProjects}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                locale={{
                  emptyText: (
                    <Empty
                      description={
                        projects.length
                          ? '没有符合条件的项目'
                          : '还没有项目，创建一个项目开始标注'
                      }
                    />
                  ),
                }}
                scroll={{ x: 1250 }}
                columns={[
                  {
                    title: '项目名称',
                    width: '22%',
                    render: (_, value) => (
                      <Link to={`/labeling/projects/${value.projectId}`}>
                        {value.name}
                      </Link>
                    ),
                  },
                  {
                    title: '说明',
                    dataIndex: 'description',
                    ellipsis: true,
                    width: '18%',
                    render: (value: string) => value || '—',
                  },
                  {
                    title: '任务类型',
                    width: '16%',
                    render: (_, value) =>
                      taskName[value.taskType] || value.taskType,
                  },
                  {
                    title: '数据集',
                    width: 190,
                    render: (_, value) => {
                      const sampleCount = value.sampleCount;
                      const resourceCount = value.resourceCount;
                      const importing = (value.activeImportCount ?? 0) > 0;
                      const countsKnown =
                        typeof sampleCount === 'number' &&
                        Number.isFinite(sampleCount) &&
                        sampleCount >= 0 &&
                        typeof resourceCount === 'number' &&
                        Number.isFinite(resourceCount) &&
                        resourceCount >= 0;
                      const datasetImported = value.datasetImported;
                      const imported =
                        datasetImported === true ||
                        (datasetImported === undefined &&
                          countsKnown &&
                          (sampleCount > 0 || resourceCount > 0));
                      const importStatus = importing
                        ? '导入处理中'
                        : datasetImported === false
                          ? '未导入'
                          : imported
                            ? countsKnown
                              ? '已导入'
                              : '已导入，数量暂不可统计'
                            : countsKnown
                              ? '未导入'
                              : '导入状态暂不可用';
                      return (
                        <Space direction="vertical" size={2}>
                          <Tag
                            color={importing ? 'processing' : undefined}
                            className={
                              imported ? styles.datasetImportedTag : undefined
                            }
                          >
                            {importStatus}
                          </Tag>
                          {imported && countsKnown && (
                            <Typography.Text type="secondary">
                              {sampleCount} 个样本 · {resourceCount} 个关联资源
                            </Typography.Text>
                          )}
                        </Space>
                      );
                    },
                  },
                  {
                    title: '状态',
                    width: 100,
                    render: (_, value) => (
                      <StatusBadge status={value.status}>
                        {statusName[value.status] || value.status}
                      </StatusBadge>
                    ),
                  },
                  {
                    title: '创建时间',
                    dataIndex: 'createdAt',
                    width: 180,
                    render: formatDisplayDateTime,
                  },
                  {
                    title: '项目操作',
                    width: 100,
                    render: (_, value) => (
                      <ProjectManagement
                        project={value}
                        onChanged={refresh}
                        onDeleted={refresh}
                      />
                    ),
                  },
                ]}
              />
            </Card>
            <Modal
              title="创建标注项目"
              open={creating}
              onCancel={() => setCreating(false)}
              okText="创建"
              confirmLoading={busy}
              okButtonProps={{
                disabled:
                  !name.trim() ||
                  !taskTypes.some(
                    (plugin) => plugin.taskType === type && plugin.enabled,
                  ),
              }}
              onOk={() =>
                void run(async () => {
                  if (!name.trim()) throw Error('请输入项目名称');
                  const selectedPlugin = taskTypes.find(
                    (plugin) => plugin.taskType === type && plugin.enabled,
                  );
                  if (!selectedPlugin)
                    throw Error('请选择可用的图像标注或时序标注');
                  const created = await post<NativeProject>(
                    '/projects',
                    {
                      name: name.trim(),
                      description: description.trim(),
                      taskType: selectedPlugin.taskType,
                      templateId: selectedPlugin.templateId,
                      templateVersion: selectedPlugin.templateVersion,
                    },
                    operationKey(),
                  );
                  setCreating(false);
                  setName('');
                  setDescription('');
                  navigate(`/labeling/projects/${created.projectId}`);
                })
              }
            >
              <Form layout="vertical" requiredMark style={{ width: '100%' }}>
                {error && <Alert type="error" message={error} />}
                {!taskTypes.some((plugin) => plugin.enabled) && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="暂时没有可用的任务类型"
                    description={
                      taskTypes.length ? (
                        <div>
                          {taskTypes.map((plugin) => (
                            <div key={plugin.key}>
                              {plugin.name}：{plugin.unavailableReason}
                            </div>
                          ))}
                        </div>
                      ) : (
                        '未取得任务类型，请刷新重试；若仍不可用，请检查标注后端是否正常运行。'
                      )
                    }
                    action={
                      <Button size="small" loading={loading} onClick={refresh}>
                        刷新任务类型
                      </Button>
                    }
                  />
                )}
                <Form.Item label="项目名称" required>
                  <Input
                    aria-label="项目名称"
                    placeholder="请输入项目名称"
                    value={name}
                    maxLength={120}
                    onChange={(e) => setName(e.target.value)}
                  />
                </Form.Item>
                <Form.Item label="项目说明">
                  <Input.TextArea
                    aria-label="项目说明"
                    placeholder="填写项目目标、数据内容或标注要求（可选）"
                    value={description}
                    maxLength={4000}
                    showCount
                    autoSize={{ minRows: 3, maxRows: 6 }}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                </Form.Item>
                <Form.Item
                  label="任务类型"
                  required
                  extra={
                    taskTypes.find((value) => value.taskType === type)
                      ?.description ||
                    '只需选择数据类型，具体标注方式在创建后的“标注设置”中配置。'
                  }
                >
                  <Select
                    aria-label="任务类别"
                    style={{ width: '100%' }}
                    value={type || undefined}
                    placeholder="请选择任务类型"
                    onChange={setType}
                    options={taskTypes.map((value) => ({
                      value: value.taskType,
                      label: `${value.name}${value.enabled ? '' : '（暂不可用）'}`,
                      disabled: !value.enabled,
                      title: value.unavailableReason,
                    }))}
                  />
                </Form.Item>
              </Form>
            </Modal>
          </>
        ) : current ? (
          <>
            {current.status === 'COMPLETED' && (
              <Alert
                type="success"
                message="项目已完成。可查看历史结果和导出成果。"
              />
            )}
            <Tabs
              className={styles.projectFlowTabs}
              activeKey={tab}
              onChange={(key) => {
                if (guard.current())
                  history.push(`/labeling/projects/${projectId}?panel=${key}`);
              }}
              items={[
                {
                  key: 'data',
                  label: (
                    <span className={styles.projectStepLabel}>
                      <span className={styles.projectStepNumber}>01</span>
                      <span>数据管理</span>
                      <span className={styles.projectStepCount}>
                        {samples.length}
                      </span>
                    </span>
                  ),
                },
                {
                  key: 'settings',
                  label: (
                    <span className={styles.projectStepLabel}>
                      <span className={styles.projectStepNumber}>02</span>
                      <span>标注设置</span>
                    </span>
                  ),
                },
                {
                  key: 'publish',
                  label: (
                    <span className={styles.projectStepLabel}>
                      <span className={styles.projectStepNumber}>03</span>
                      <span>发布任务</span>
                    </span>
                  ),
                },
                ...(publishMode === 'AI'
                  ? [{ key: 'ai', label: 'AI 执行' }]
                  : []),
                {
                  key: 'review',
                  label: (
                    <span className={styles.projectStepLabel}>
                      <span className={styles.projectStepNumber}>04</span>
                      <span>核查与重标</span>
                    </span>
                  ),
                },
                {
                  key: 'outcome',
                  label: (
                    <span className={styles.projectStepLabel}>
                      <span className={styles.projectStepNumber}>05</span>
                      <span>成果与导出</span>
                    </span>
                  ),
                },
              ]}
            />
            {tab === 'data' && (
              <NativeDataPreparation
                version={version}
                reviews={reviews}
                onPublish={(ids) => {
                  setPublishSelection(ids);
                  setRework(undefined);
                  navigate(`/labeling/projects/${projectId}?panel=publish`);
                }}
                onGuard={(fn) => {
                  guard.current = fn;
                }}
                project={current}
                samples={samples}
                resources={resources}
                refresh={refresh}
                run={run}
                busy={busy}
                suggest={(xml) => {
                  setSuggestion(xml);
                  sessionStorage.setItem(
                    `native-config-draft:${current.projectId}`,
                    xml,
                  );
                  navigate(`/labeling/projects/${projectId}?panel=settings`);
                }}
              />
            )}
            {tab === 'settings' && (
              <NativeSpecSettings
                resources={resources}
                project={current}
                samples={samples}
                version={version}
                specVersions={current.specVersions}
                onVersionChange={(id) => {
                  if (guard.current()) setSpecId(id);
                }}
                suggestion={suggestion}
                run={run}
                refresh={(id) => {
                  setSpecId(id || '');
                  setSuggestion('');
                  refresh();
                  void message.success('已保存为新规范版本');
                }}
                onGuard={(fn) => {
                  guard.current = fn;
                }}
              />
            )}
            {aiSession?.projectId === projectId && (
              <div hidden={tab !== 'ai'}>
                <NativeAi
                  key={
                    aiSession.config.modelId +
                    aiSession.samples.map((sample) => sample.sampleId).join('|')
                  }
                  project={current}
                  selectedSamples={aiSession.samples}
                  version={aiSession.version}
                  execution={aiSession.config}
                  results={aiSession.results}
                  progress={
                    finishedExecutions.has(aiSession.execution.status)
                      ? 100
                      : Math.floor(
                          (100 *
                            (aiSession.execution.sampleCount -
                              aiSession.execution.pendingCount)) /
                            Math.max(aiSession.execution.sampleCount, 1),
                        )
                  }
                  running={!finishedExecutions.has(aiSession.execution.status)}
                  onReview={() =>
                    navigate(`/labeling/projects/${projectId}?panel=review`)
                  }
                />
              </div>
            )}
            {tab === 'ai' && !aiSession && (
              <Alert
                type="info"
                message="暂无 AI 执行任务，请从发布任务选择 AI 标注并完成配置。"
                action={
                  <Button
                    onClick={() =>
                      navigate(`/labeling/projects/${projectId}?panel=publish`)
                    }
                  >
                    前往发布任务
                  </Button>
                }
              />
            )}
            {tab === 'publish' && (
              <NativePublish
                mode={publishMode}
                onModeChange={(mode) => {
                  setPublishMode(mode);
                }}
                onPublishAi={(selectedSamples, selectedVersion, config) => {
                  void run(async () => {
                    const execution = await post<LiveExecution>(
                      `/projects/${encodeURIComponent(current.projectId)}/prelabel-runs`,
                      {
                        specVersionId: selectedVersion.specVersionId,
                        modelId: config.modelId,
                        instructions: config.instructions,
                        samples: selectedSamples.map(({ sampleId }) => ({
                          sampleId,
                        })),
                      },
                      operationKey(),
                    );
                    setAiSession({
                      projectId: current.projectId,
                      samples: selectedSamples,
                      version: selectedVersion,
                      config,
                      execution,
                      results: [],
                    });
                    try {
                      const receipt: PrelabelReceipt = {
                        executionId: execution.executionId,
                        specVersionId: selectedVersion.specVersionId,
                        sampleIds: selectedSamples.map(
                          ({ sampleId }) => sampleId,
                        ),
                        config,
                      };
                      sessionStorage.setItem(
                        receiptKey(current.projectId),
                        JSON.stringify(receipt),
                      );
                    } catch {
                      // Storage may be disabled; the active run still works.
                    }
                    navigate(`/labeling/projects/${projectId}?panel=ai`);
                  });
                }}
                initialSelection={publishSelection}
                project={current}
                samples={samples}
                version={version}
                specVersions={current.specVersions}
                onVersionChange={(id) => setSpecId(id)}
                reviews={reviews}
                rework={rework}
                run={run}
                refresh={refresh}
                busy={busy}
              />
            )}
            {tab === 'review' && (
              <NativeReview
                aiResults={
                  aiSession?.projectId === projectId
                    ? aiSession.results
                    : undefined
                }
                onAiResultsChange={async (results) => {
                  if (!aiSession || aiSession.projectId !== current.projectId)
                    return;
                  const before = new Map(
                    aiSession.results.map((row) => [
                      row.sample.sampleId,
                      row.status,
                    ]),
                  );
                  const items = results
                    .filter(
                      (row) =>
                        row.reviewable &&
                        before.get(row.sample.sampleId) === '待核查' &&
                        (row.status === '已确认' || row.status === '需重标'),
                    )
                    .map((row) => ({
                      sampleId: row.sample.sampleId,
                      specVersionId: aiSession.version.specVersionId,
                      action:
                        row.status === '已确认'
                          ? ('ACCEPT' as const)
                          : ('NEEDS_REVISION' as const),
                      sourceKind: 'AI' as const,
                      executionId: aiSession.execution.executionId,
                      reason: row.reason,
                    }));
                  if (!items.length) return;
                  await ownerApi.review(
                    current.projectId,
                    items,
                    operationKey(),
                  );
                  setAiSession((session) =>
                    session?.execution.executionId ===
                    aiSession.execution.executionId
                      ? { ...session, results }
                      : session,
                  );
                }}
                project={current}
                submissions={submissions}
                run={run}
                refresh={refresh}
                busy={busy}
                onGuard={(fn) => {
                  guard.current = fn;
                }}
                rework={(scope) => {
                  setPublishSelection([]);
                  setRework(scope);
                  setSpecId(scope.specVersionId);
                  navigate(`/labeling/projects/${projectId}?panel=publish`);
                }}
              />
            )}
            {tab === 'outcome' && (
              <NativeOutcome
                project={current}
                version={version}
                onVersionChange={setSpecId}
                reviews={reviews}
                snapshots={snapshots}
                run={run}
                refresh={refresh}
                busy={busy}
              />
            )}
          </>
        ) : null}
      </Space>
    </WorkspaceLayout>
  );
}
