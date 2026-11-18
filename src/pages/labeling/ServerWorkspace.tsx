import { history, Link, useLocation, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
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
import LegacyProjects from './index';
import styles from './index.less';
import NativeDataPreparation from './NativeDataPreparation';
import NativeOutcome from './NativeOutcome';
import NativePublish, { type ReworkScope } from './NativePublish';
import NativeReview from './NativeReview';
import NativeSpecSettings from './NativeSpecSettings';
import WorkspaceLayout from './WorkspaceLayout';

const statusName: Record<string, string> = {
  DRAFT: '草稿',
  OPEN: '进行中',
  PAUSED: '已暂停',
  ARCHIVED: '已归档',
  COMPLETED: '已完成',
};
const taskName: Record<string, string> = {
  TIME_SERIES: '时序分析',
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
  const [taskTypes, setTaskTypes] = useState<
    { taskType: string; name: string }[]
  >([]);
  const [reload, setReload] = useState(0),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [creating, setCreating] = useState(false),
    [name, setName] = useState(''),
    [type, setType] = useState('TIME_SERIES'),
    [tab, setTab] = useState('data'),
    [specId, setSpecId] = useState(''),
    [suggestion, setSuggestion] = useState(''),
    [rework, setRework] = useState<ReworkScope>();
  const [publishSelection, setPublishSelection] = useState<string[]>([]);
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
    setTab(
      location.pathname.endsWith('/workbench')
        ? query.get('view') === 'export'
          ? 'outcome'
          : 'review'
        : query.get('panel') === 'publish'
          ? 'publish'
          : query.get('panel') || 'data',
    );
  }, [location.pathname, location.search]);
  useEffect(() => {
    if (project?.taskType === 'IMAGE_CLASSIFICATION') return;
    const unblock = history.block((transition) => {
      if (internalNavigation.current || (!saving.current && guard.current())) {
        unblock();
        transition.retry();
      }
    });
    return unblock;
  }, [projectId, project?.taskType, location.pathname, location.search]);
  useEffect(() => {
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
      if (detail.taskType === 'IMAGE_CLASSIFICATION') {
        if (!abort.signal.aborted) setProject(detail);
        return;
      }
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
        setProject(detail);
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
  if (
    projectId &&
    project?.projectId === projectId &&
    project.taskType === 'IMAGE_CLASSIFICATION'
  )
    return <LegacyProjects />;
  const current = project?.projectId === projectId ? project : undefined;
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
        <div className={styles.managerToolbar}>
          <Space wrap>
            <Link to="/labeling">我的项目</Link>
            {current && (
              <>
                <span>/</span>
                <Typography.Title level={4} style={{ margin: 0 }}>
                  {current.name}
                </Typography.Title>
                <Tag>{statusName[current.status]}</Tag>
              </>
            )}
          </Space>
          <Space wrap>
            {current && (
              <>
                <span>规范版本</span>
                <Select
                  aria-label="规范版本"
                  style={{ minWidth: 160 }}
                  value={version?.specVersionId}
                  placeholder="尚未保存规范"
                  onChange={(id) => {
                    if (guard.current()) setSpecId(id);
                  }}
                  options={current.specVersions.map((value) => ({
                    value: value.specVersionId,
                    label: `第 ${value.versionNo} 版`,
                  }))}
                />
              </>
            )}
            <Button
              onClick={() => {
                if (guard.current()) refresh();
              }}
              loading={loading}
            >
              刷新
            </Button>
            <Link to="/labeling/tasks">
              <Button>前往任务广场</Button>
            </Link>
            <Link to="/labeling/assignments">
              <Button>我的任务</Button>
            </Link>
          </Space>
        </div>
        {error && <Alert type="error" showIcon message={error} />}
        {loading && !current ? (
          <Spin tip="正在读取项目">
            <div style={{ minHeight: 180 }} />
          </Spin>
        ) : !projectId ? (
          <>
            <Typography.Text type="secondary">
              需求方创建项目；所有角色均可领取标注。
            </Typography.Text>
            <Card
              title="我创建的项目"
              extra={
                <Button
                  type="primary"
                  disabled={loading || !taskTypes.length}
                  onClick={() => setCreating(true)}
                >
                  创建项目
                </Button>
              }
            >
              <Table
                rowKey="projectId"
                dataSource={projects}
                scroll={{ x: 700 }}
                columns={[
                  {
                    title: '项目名称',
                    render: (_, value) => (
                      <Link to={`/labeling/projects/${value.projectId}`}>
                        {value.name}
                      </Link>
                    ),
                  },
                  {
                    title: '任务类别',
                    render: (_, value) =>
                      taskName[value.taskType] || value.taskType,
                  },
                  {
                    title: '状态',
                    render: (_, value) => (
                      <Tag>{statusName[value.status] || value.status}</Tag>
                    ),
                  },
                  {
                    title: '创建时间',
                    dataIndex: 'createdAt',
                    render: formatDisplayDateTime,
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
              onOk={() =>
                void run(async () => {
                  if (!name.trim()) throw Error('请输入项目名称');
                  const created = await post<NativeProject>(
                    '/projects',
                    { name: name.trim(), taskType: type },
                    operationKey(),
                  );
                  setCreating(false);
                  setName('');
                  navigate(`/labeling/projects/${created.projectId}`);
                })
              }
            >
              <Space direction="vertical" style={{ width: '100%' }}>
                {error && <Alert type="error" message={error} />}
                <Input
                  aria-label="项目名称"
                  placeholder="项目名称"
                  value={name}
                  maxLength={120}
                  onChange={(e) => setName(e.target.value)}
                />
                <Select
                  aria-label="任务类别"
                  style={{ width: '100%' }}
                  value={type}
                  onChange={setType}
                  options={taskTypes.map((value) => ({
                    value: value.taskType,
                    label: value.name,
                  }))}
                />
              </Space>
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
              activeKey={tab}
              onChange={(key) => {
                if (guard.current())
                  history.push(`/labeling/projects/${projectId}?panel=${key}`);
              }}
              items={[
                { key: 'data', label: `1 数据管理（${samples.length}）` },
                { key: 'settings', label: '2 标注设置' },
                { key: 'publish', label: '3 发布任务' },
                { key: 'review', label: '4 核查与重标' },
                { key: 'outcome', label: '5 成果与导出' },
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
            {tab === 'publish' && (
              <NativePublish
                initialSelection={publishSelection}
                project={current}
                samples={samples}
                version={version}
                reviews={reviews}
                rework={rework}
                run={run}
                refresh={refresh}
                busy={busy}
              />
            )}
            {tab === 'review' && (
              <NativeReview
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
