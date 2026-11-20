import { history, Link, useLocation, useParams } from '@umijs/max';
import { PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
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
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  type Project,
  post,
} from '@/services/labeling';
import {
  collectPages,
  type InputResource,
  type NativeProject,
  type NativeSample,
} from '@/services/nativeLabeling';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { normalizeWorkspaceProject, projectPanel } from '@/services/projectCompatibility.mjs';
import styles from './index.less';
import NativeDataPreparation from './NativeDataPreparation';
import NativeOutcome from './NativeOutcome';
import NativePublish, { type ReworkScope } from './NativePublish';
import NativeReview from './NativeReview';
import NativeAi, { type DemoResult } from './NativeAi';
import NativeSpecSettings from './NativeSpecSettings';
import StatusBadge from './StatusBadge';
import WorkspaceLayout from './WorkspaceLayout';
import ProjectManagement from './ProjectManagement';
import ProjectBreadcrumbs from './ProjectBreadcrumbs';

const statusName: Record<string, string> = {
  DRAFT: '草稿',
  OPEN: '进行中',
  PAUSED: '已暂停',
  ARCHIVED: '已归档',
  COMPLETED: '已完成',
};
const taskName: Record<string, string> = {
  TIME_SERIES: '时序分析',
  TIMESERIES_ANNOTATION: '时序标注',
  COMPUTER_VISION: '计算机视觉',
  IMAGE_CLASSIFICATION: '图片分类',
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
  const [aiSession, setAiSession] = useState<{ projectId: string; samples: NativeSample[]; version: NativeProject['specVersions'][number]; config: { modelId: string; instructions: string }; results: DemoResult[] }>();
  const [projectQuery, setProjectQuery] = useState('');
  const [projectStatus, setProjectStatus] = useState('ALL');
  const [taskTypes, setTaskTypes] = useState<
    { taskType: string; name: string }[]
  >([]);
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
          labelingJson<{ taskType: string; name: string }[]>(
            '/label-configs/task-types',
            { signal: abort.signal },
          ),
        ]);
        if (!abort.signal.aborted) {
          setProjects(values);
          setTaskTypes(
            types.filter((value) =>
              ['TIME_SERIES', 'COMPUTER_VISION'].includes(value.taskType),
            ),
          );
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
  const current = project?.projectId === projectId ? project : undefined;
  const filteredProjects = projects.filter((value) => {
    const query = projectQuery.trim().toLocaleLowerCase();
    const matchesQuery =
      !query ||
      `${value.name} ${value.description || ''}`
        .toLocaleLowerCase()
        .includes(query);
    return (
      matchesQuery &&
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
                创建项目，选择任务模板；准备规范与数据后发布任务，完成标注后审核并导出成果。
              </p>
            </div>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={loading || !taskTypes.length}
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
              extra={<Button loading={loading} onClick={refresh}>刷新</Button>}
            >
              <div className={styles.projectListTools}>
                <Input
                  allowClear
                  aria-label="搜索项目名称或说明"
                  placeholder="搜索项目名称或说明"
                  value={projectQuery}
                  onChange={(event) => setProjectQuery(event.target.value)}
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
              <Typography.Text type="secondary" className={styles.projectListCount}>
                已加载 {projects.length} 个项目 · 当前匹配 {filteredProjects.length} 个
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
                            className={imported ? styles.datasetImportedTag : undefined}
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
                    width: 220,
                    render: (_, value) => (
                      <Space size={12} wrap>
                        <Link to={`/labeling/projects/${value.projectId}`}>
                          {value.status === 'DRAFT' ? '准备并发布' : '审核结果'}
                        </Link>
                        {value.status !== 'DRAFT' && (
                          <Link
                            to={`/labeling/projects/${value.projectId}?panel=publish`}
                          >
                            任务发布
                          </Link>
                        )}
                        <ProjectManagement
                          project={value}
                          onChanged={refresh}
                          onDeleted={refresh}
                        />
                      </Space>
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
              okButtonProps={{ disabled: !name.trim() || !type }}
              onOk={() =>
                void run(async () => {
                  if (!name.trim()) throw Error('请输入项目名称');
                  if (!type) throw Error('请选择任务类型');
                  const created = await post<NativeProject>(
                    '/projects',
                    {
                      name: name.trim(),
                      description: description.trim(),
                      taskType: type,
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
                <Form.Item label="任务类型" required>
                  <Select
                    aria-label="任务类别"
                    style={{ width: '100%' }}
                    value={type || undefined}
                    placeholder="请选择任务类型"
                    onChange={setType}
                    options={taskTypes.map((value) => ({
                      value: value.taskType,
                      label: value.name,
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
                ...(publishMode === 'AI' ? [{ key: 'ai', label: 'AI 执行' }] : []),
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
                  key={aiSession.config.modelId + aiSession.samples.map((sample) => sample.sampleId).join('|')}
                  project={current}
                  selectedSamples={aiSession.samples}
                  version={aiSession.version}
                  execution={aiSession.config}
                  onComplete={(results) => setAiSession((session) => session ? { ...session, results } : session)}
                  onReview={() => navigate(`/labeling/projects/${projectId}?panel=review`)}
                />
              </div>
            )}
            {tab === 'ai' && !aiSession && (
              <Alert type="info" message="暂无 AI 执行任务，请从发布任务选择 AI 标注并完成配置。" action={<Button onClick={() => navigate(`/labeling/projects/${projectId}?panel=publish`)}>前往发布任务</Button>} />
            )}
            {tab === 'publish' && (
              <NativePublish
                mode={publishMode}
                onModeChange={(mode) => {
                  setPublishMode(mode);
                  if (mode === 'HUMAN') setAiSession(undefined);
                }}
                onPublishAi={(selectedSamples, selectedVersion, config) => {
                  setAiSession({ projectId: current.projectId, samples: selectedSamples, version: selectedVersion, config, results: [] });
                  navigate(`/labeling/projects/${projectId}?panel=ai`);
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
                aiResults={aiSession?.projectId === projectId ? aiSession.results : undefined}
                onAiResultsChange={(results) => setAiSession((session) => session ? { ...session, results } : session)}
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
