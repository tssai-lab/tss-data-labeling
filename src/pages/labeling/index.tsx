import {
  CloudUploadOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { history, Link, useLocation, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  Image,
  Input,
  Modal,
  Progress,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { Batch, Page, Project, Sample } from '@/services/labeling';
import {
  LabelingError,
  labelingJson,
  operationKey,
  pageQuery,
  post,
  sampleBlob,
} from '@/services/labeling';
import { ownerApi, type RegisteredTemplate } from '@/services/labelingOwner';
import { projectEntryPath } from '@/services/labelingProjectActions';
import {
  taskTypeFilterKey,
  resolveTaskTemplate,
  taskTemplates,
} from '@/services/labelingTemplates.mjs';
import { hashFile, uploadChunks } from '@/services/labelingUpload.mjs';
import { publishingStep } from '@/services/labelingWorkspace';
import styles from './index.less';
import ProjectBreadcrumbs from './ProjectBreadcrumbs';
import ProjectHistory from './ProjectHistory';
import ProjectManagement from './ProjectManagement';
import PublishPlan from './PublishPlan';
import SpecVersions from './SpecVersions';
import StatusBadge from './StatusBadge';
import StructuredPreview from './StructuredPreview';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

const { Text, Paragraph } = Typography;
const statusNames: Record<string, string> = {
  DRAFT: '草稿',
  OPEN: '开放',
  PAUSED: '暂停',
  ARCHIVED: '已归档',
  UPLOADING: '待上传 / 上传中',
  PROCESSING: '解析中',
  READY: '可浏览',
  FAILED: '解析失败',
};
function errorText(error: unknown): string {
  if (error instanceof LabelingError)
    return `${error.message}${error.code ? `（${error.code}）` : ''}${error.requestId ? ` · 请求编号：${error.requestId}` : ''}`;
  return error instanceof Error ? error.message : '操作失败，请重试';
}

type Identity = { actorId: string; canCreateProject: boolean };
type UploadAttempt = {
  projectId: string;
  filename: string;
  size: number;
  hash: string;
  key: string;
  batchId?: string;
};

function Preview({ sample, projectId }: { sample: Sample; projectId: string }) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let objectUrl = '';
    setUrl('');
    setError('');
    sampleBlob(projectId, sample.sampleId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [projectId, sample.sampleId, retry]);
  if (error)
    return (
      <Alert
        type="error"
        showIcon
        message="图片加载失败"
        description={error}
        action={<Button onClick={() => setRetry(retry + 1)}>重试</Button>}
      />
    );
  return (
    <div className={styles.preview}>
      {url ? (
        <Image
          src={url}
          alt={sample.sourcePath}
          style={{ maxHeight: 520, objectFit: 'contain' }}
        />
      ) : (
        <Spin tip="正在读取图片">
          <div style={{ height: 240 }} />
        </Spin>
      )}
    </div>
  );
}

export default function Labeling() {
  const location = useLocation();
  const [taskType, setTaskType] = useState('IMAGE_CLASSIFICATION');
  const [registered, setRegistered] = useState<RegisteredTemplate[]>([]);
  const [templateError, setTemplateError] = useState('');
  const { message } = App.useApp();
  const { projectId } = useParams<{ projectId?: string }>();
  const [identity, setIdentity] = useState<Identity>();
  useEffect(() => {
    if (!identity) return;
    const abort = new AbortController();
    void ownerApi
      .templates(abort.signal)
      .then((rows) => {
        if (!abort.signal.aborted) setRegistered(rows);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setTemplateError(errorText(e));
      });
    return () => abort.abort();
  }, [identity?.actorId]);
  const available = registered.filter((template) => {
    try {
      return template.enabled && resolveTaskTemplate(template).enabled;
    } catch {
      return false;
    }
  });
  const [identityError, setIdentityError] = useState('');
  const [identityRetry, setIdentityRetry] = useState(0);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectNameQuery, setProjectNameQuery] = useState('');
  const [projectDescriptionQuery, setProjectDescriptionQuery] = useState('');
  const [projectStatus, setProjectStatus] = useState('ALL');
  const [projectTaskType, setProjectTaskType] = useState('ALL');
  const [createdAtRange, setCreatedAtRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const filteredProjects = projects.filter((item) => {
    const createdAt = item.createdAt ? dayjs(item.createdAt) : null;
    const [startDate, endDate] = createdAtRange || [];
    return (
      (projectStatus === 'ALL' || item.status === projectStatus) &&
      (projectTaskType === 'ALL' || taskTypeFilterKey(item.taskType) === projectTaskType) &&
      item.name
        .toLocaleLowerCase()
        .includes(projectNameQuery.trim().toLocaleLowerCase()) &&
      (item.description || '')
        .toLocaleLowerCase()
        .includes(projectDescriptionQuery.trim().toLocaleLowerCase()) &&
      (!startDate ||
        Boolean(createdAt?.isValid() && !createdAt.isBefore(startDate, 'day'))) &&
      (!endDate ||
        Boolean(createdAt?.isValid() && !createdAt.isAfter(endDate, 'day')))
    );
  });
  const [projectCursor, setProjectCursor] = useState<string | null>(null);
  const [project, setProject] = useState<Project>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const [createForm] = Form.useForm<{ name: string; description: string }>();
  const createAttempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  const [selectedSpecId, setSelectedSpecId] = useState<string>();
  const [batch, setBatch] = useState<Batch>();
  const [publishedBatchId, setPublishedBatchId] = useState('');
  const [showImages, setShowImages] = useState(false);
  const [knownBatchIds, setKnownBatchIds] = useState<string[]>([]);
  const [batchInput, setBatchInput] = useState('');
  const [batchBusy, setBatchBusy] = useState(false);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [sampleCursor, setSampleCursor] = useState<string | null>(null);
  const [sampleBusy, setSampleBusy] = useState(false);
  const [selected, setSelected] = useState<Sample>();
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadStage, setUploadStage] = useState('');
  const [progress, setProgress] = useState(0);
  const uploadController = useRef<AbortController | undefined>(undefined);
  const uploadAttempt = useRef<UploadAttempt | undefined>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);
  const routeVersion = useRef(0);
  const batchRequest = useRef(0);
  const sampleRequest = useRef(0);
  const currentProjectId = useRef(projectId);
  currentProjectId.current = projectId;
  const writable = project?.status === 'DRAFT' || project?.status === 'OPEN';
  const setupStep = publishingStep(project, batch, location.search);
  const changeStep = (step: number) => {
    const query = new URLSearchParams(location.search);
    query.set('step', ['spec', 'data', 'publish'][step]);
    query.set('panel', 'publish');
    if (batch) query.set('batch', batch.batchId);
    history.replace(`${location.pathname}?${query}`);
  };
  const bookmarkKey =
    identity && projectId
      ? `labeling:last-batch:${identity.actorId}:${projectId}`
      : '';

  useEffect(() => {
    const abort = new AbortController();
    setIdentity(undefined);
    setIdentityError('');
    labelingJson<Identity>('/me', { signal: abort.signal })
      .then(setIdentity)
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setIdentityError(errorText(e));
      });
    return () => abort.abort();
  }, [identityRetry]);

  async function listProjects(cursor?: string | null) {
    const version = routeVersion.current;
    setLoading(true);
    setError('');
    try {
      const page = await labelingJson<Page<Project>>(
        `/projects?scope=owned&${pageQuery(cursor)}`,
      );
      if (version !== routeVersion.current) return;
      setProjects((previous) =>
        cursor ? [...previous, ...page.items] : page.items,
      );
      setProjectCursor(page.nextCursor);
    } catch (e) {
      if (version === routeVersion.current) setError(errorText(e));
    } finally {
      if (version === routeVersion.current) setLoading(false);
    }
  }

  function rememberBatch(value: Batch) {
    setBatch(value);
    setBatchInput(value.batchId);
    if (bookmarkKey) {
      try {
        localStorage.setItem(bookmarkKey, value.batchId);
        const historyKey = `${bookmarkKey}:known`;
        const parsed = JSON.parse(localStorage.getItem(historyKey) || '[]');
        const ids = [
          ...new Set([
            value.batchId,
            ...(Array.isArray(parsed)
              ? parsed.filter((id): id is string => typeof id === 'string')
              : []),
          ]),
        ].slice(0, 100);
        localStorage.setItem(historyKey, JSON.stringify(ids));
        setKnownBatchIds(ids);
      } catch {
        /* URL remains shareable without local storage. */
      }
    }
    const query = new URLSearchParams(history.location.search);
    query.set('batch', value.batchId);
    history.replace(
      `/labeling/projects/${encodeURIComponent(value.projectId)}?${query}`,
    );
  }

  async function restoreBatch(id: string, expectedProjectId = projectId) {
    const requestNumber = ++batchRequest.current;
    ++sampleRequest.current;
    setSampleBusy(false);
    if (!id.trim() || !expectedProjectId) return;
    const version = routeVersion.current;
    setBatchBusy(true);
    setError('');
    try {
      const value = await labelingJson<Batch>(
        `/imports/${encodeURIComponent(id.trim())}`,
      );
      if (version !== routeVersion.current) return;
      if (requestNumber !== batchRequest.current) return;
      if (value.projectId !== expectedProjectId)
        throw new Error('此批次不属于当前项目');
      setSamples([]);
      setSampleCursor(null);
      setSelected(undefined);
      rememberBatch(value);
    } catch (e) {
      if (version === routeVersion.current) setError(errorText(e));
    } finally {
      if (version === routeVersion.current) setBatchBusy(false);
    }
  }

  useEffect(() => {
    routeVersion.current += 1;
    ++batchRequest.current;
    ++sampleRequest.current;
    const version = routeVersion.current;
    uploadController.current?.abort();
    setProject(undefined);
    setBatch(undefined);
    setPublishedBatchId('');
    setShowImages(false);
    setKnownBatchIds([]);
    setSamples([]);
    setSelected(undefined);
    setError('');
    setUploadBusy(false);
    setBatchInput('');
    setBatchBusy(false);
    setSampleBusy(false);
    uploadAttempt.current = undefined;
    setSelectedSpecId(undefined);
    if (!identity) return;
    if (!projectId) {
      void listProjects();
      return;
    }
    const abort = new AbortController();
    setLoading(true);
    labelingJson<Project>(`/projects/${encodeURIComponent(projectId)}`, {
      signal: abort.signal,
    })
      .then((value) => {
        if (version !== routeVersion.current) return;
        setProject(value);
        let id = new URLSearchParams(
          window.location.hash.split('?')[1] || window.location.search,
        ).get('batch');
        if (!id && bookmarkKey) {
          try {
            id = localStorage.getItem(bookmarkKey);
            const known = JSON.parse(
              localStorage.getItem(`${bookmarkKey}:known`) || '[]',
            );
            setKnownBatchIds(
              Array.isArray(known)
                ? known.filter(
                    (value): value is string => typeof value === 'string',
                  )
                : [],
            );
          } catch {
            /* No saved batch. */
          }
        }
        if (id) void restoreBatch(id, projectId);
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (version === routeVersion.current) setLoading(false);
      });
    return () => abort.abort();
  }, [projectId, identity, bookmarkKey]);

  useEffect(() => () => uploadController.current?.abort(), []);
  useEffect(() => {
    if (!uploadBusy) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [uploadBusy]);

  useEffect(() => {
    if (batch?.status !== 'PROCESSING') return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const next = await labelingJson<Batch>(
          `/imports/${encodeURIComponent(batch.batchId)}`,
          { signal: abort.signal },
        );
        if (abort.signal.aborted) return;
        setBatch(next);
        if (next.status === 'PROCESSING') timer = setTimeout(poll, 2000);
      } catch (e) {
        if (!abort.signal.aborted)
          setError(`状态查询失败，可点击刷新批次重试：${errorText(e)}`);
      }
    };
    timer = setTimeout(poll, 1000);
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [batch?.batchId, batch?.status]);

  async function listSamples(cursor?: string | null) {
    if (batch?.status !== 'READY') return;
    const requestNumber = ++sampleRequest.current;
    const version = routeVersion.current;
    const id = batch.batchId;
    setSampleBusy(true);
    setError('');
    try {
      const page = await labelingJson<Page<Sample>>(
        `/imports/${encodeURIComponent(id)}/samples?${pageQuery(cursor)}`,
      );
      if (version !== routeVersion.current) return;
      if (requestNumber !== sampleRequest.current) return;
      setSamples((previous) =>
        cursor ? [...previous, ...page.items] : page.items,
      );
      setSampleCursor(page.nextCursor);
      if (!cursor) {
        setSelected(page.items[0]);
      }
    } catch (e) {
      if (version === routeVersion.current) setError(errorText(e));
    } finally {
      if (version === routeVersion.current) setSampleBusy(false);
    }
  }
  useEffect(() => {
    if (batch?.status === 'READY') void listSamples();
  }, [batch?.batchId, batch?.status]);

  async function createProject() {
    if (!available.some((t) => t.taskType === taskType && t.enabled)) {
      message.error(templateError || '请先等待服务器模板加载完成');
      return;
    }
    const values = await createForm.validateFields().catch(() => null);
    if (!values) return;
    const body = {
      name: values.name.trim(),
      description: values.description?.trim() || '',
      taskType,
      templateId: available.find((t) => t.taskType === taskType)?.templateId,
      templateVersion: available.find((t) => t.taskType === taskType)
        ?.templateVersion,
    };
    const payload = JSON.stringify(body);
    if (createAttempt.current?.payload !== payload)
      createAttempt.current = { payload, key: operationKey() };
    setCreateBusy(true);
    try {
      const value = await post<Project>(
        '/projects',
        body,
        createAttempt.current.key,
      );
      setCreating(false);
      createForm.resetFields();
      createAttempt.current = undefined;
      message.success('项目已创建，请设置规范并导入数据');
      history.push(`/labeling/projects/${encodeURIComponent(value.projectId)}`);
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setCreateBusy(false);
    }
  }

  async function upload(file: File) {
    if (!projectId || !writable || uploadBusy) return;
    if (!/\.zip$/i.test(file.name) || file.size === 0) {
      message.error(
        '请选择非空 ZIP：图片项目放 JPEG/PNG；能耗项目按站点日 CSV 交付结构打包',
      );
      return;
    }
    const version = routeVersion.current;
    const controller = new AbortController();
    uploadController.current = controller;
    setUploadBusy(true);
    setError('');
    setProgress(0);
    setUploadStage('计算文件校验值');
    try {
      const hash = await hashFile(file, controller.signal, (fraction) =>
        setProgress(Math.round(fraction * 100)),
      );
      let attempt = uploadAttempt.current;
      if (
        !attempt ||
        attempt.projectId !== projectId ||
        attempt.filename !== file.name ||
        attempt.size !== file.size ||
        attempt.hash !== hash
      ) {
        attempt = {
          projectId,
          filename: file.name,
          size: file.size,
          hash,
          key: operationKey(),
        };
        uploadAttempt.current = attempt;
      }
      let active: Batch;
      if (attempt.batchId) {
        active = await labelingJson<Batch>(
          `/imports/${encodeURIComponent(attempt.batchId)}`,
          { signal: controller.signal },
        );
      } else {
        active = await post<Batch>(
          `/projects/${encodeURIComponent(projectId)}/imports`,
          { filename: file.name, sizeBytes: file.size, sha256: hash },
          attempt.key,
        );
        attempt.batchId = active.batchId;
      }
      controller.signal.throwIfAborted();
      if (version !== routeVersion.current) return;
      rememberBatch(active);
      if (active.status === 'UPLOADING') {
        setUploadStage('上传数据压缩包');
        await uploadChunks(
          file,
          active,
          labelingJson<{ receivedBytes: number }>,
          controller.signal,
          (fraction) => setProgress(Math.round(fraction * 100)),
        );
        controller.signal.throwIfAborted();
        setUploadStage('校验并提交解析');
        active = await post<Batch>(
          `/imports/${encodeURIComponent(active.batchId)}/complete`,
          undefined,
          `complete-${active.batchId}`,
        );
        // Same-key complete may return an old PROCESSING snapshot. Always query current state.
        active = await labelingJson<Batch>(
          `/imports/${encodeURIComponent(active.batchId)}`,
          { signal: controller.signal },
        );
      }
      if (version !== routeVersion.current) return;
      rememberBatch(active);
      setProgress(100);
      if (active.status === 'FAILED') setError('解析失败，请查看批次错误明细');
      else
        message.success(
          active.status === 'READY' ? '样本已就绪' : '上传已完成，正在解析数据',
        );
    } catch (e) {
      if (version === routeVersion.current)
        setError(
          controller.signal.aborted
            ? '已暂停上传。重新选择同一 ZIP 可查询服务器进度后继续。'
            : errorText(e),
        );
    } finally {
      if (version === routeVersion.current) setUploadBusy(false);
    }
  }

  async function retryImport() {
    if (!batch) return;
    setBatchBusy(true);
    setError('');
    try {
      await post(
        `/imports/${encodeURIComponent(batch.batchId)}/retry`,
        undefined,
        operationKey(),
      );
      await restoreBatch(batch.batchId);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBatchBusy(false);
    }
  }

  return (
    <WorkspaceLayout>
      <div className={styles.heading}>
        <div>
          <ProjectBreadcrumbs
            projectId={projectId}
            current={projectId ? '任务发布' : '我的项目'}
          />
          <h1>
            {project?.name || (projectId ? '正在读取项目' : '数据标注工作空间')}
          </h1>
          {!projectId && (
            <p className={styles.headingHint}>
              创建项目，选择任务模板；准备规范与数据后发布任务，完成标注后审核并导出成果。
            </p>
          )}
          {projectId && (
            <p className={styles.headingHint}>
              设置规范、导入新数据，再发布人工标注任务。处理交回的答案请进入结果工作台。
            </p>
          )}
          {project?.description && (
            <Paragraph type="secondary">{project.description}</Paragraph>
          )}
        </div>
        {identity && !projectId && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!identity.canCreateProject}
            onClick={() => setCreating(true)}
          >
            创建项目
          </Button>
        )}
        {project && (
          <Space wrap>
            <StatusBadge status={project.status} />
            <ProjectManagement
              project={project}
              onChanged={() => {
                void labelingJson<Project>(
                  `/projects/${encodeURIComponent(project.projectId)}`,
                )
                  .then(setProject)
                  .catch((e) => setError(errorText(e)));
              }}
              onDeleted={() => history.push('/labeling')}
            />
            {project.status !== 'DRAFT' && (
              <Link
                to={`/labeling/projects/${encodeURIComponent(project.projectId)}/workbench`}
              >
                <Button>进入结果工作台</Button>
              </Link>
            )}
          </Space>
        )}
      </div>
      {identityError && (
        <Alert
          type="error"
          showIcon
          message="无法连接标注服务"
          description={identityError}
          action={
            <Button onClick={() => setIdentityRetry(identityRetry + 1)}>
              重试
            </Button>
          }
        />
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          closable
          onClose={() => setError('')}
        />
      )}
      {!identity && !identityError && (
        <Spin tip="正在验证标注平台身份">
          <div style={{ height: 180 }} />
        </Spin>
      )}
      {identity && !projectId && (
        <div className={ui.projectColumns}>
          <Card
            title="我创建的项目"
            extra={
              <Space>
                <Button
                  icon={<ReloadOutlined />}
                  loading={loading}
                  onClick={() => void listProjects()}
                >
                  刷新
                </Button>
              </Space>
            }
          >
            <div className={ui.inlineSearch}>
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
                onChange={(event) => setProjectDescriptionQuery(event.target.value)}
              />
              <Select
                aria-label="按任务类型筛选"
                value={projectTaskType}
                onChange={setProjectTaskType}
                style={{ minWidth: 145 }}
                options={[
                  { value: 'ALL', label: '全部任务类型' },
                  ...Array.from(
                    new Set(projects.map((item) => taskTypeFilterKey(item.taskType))),
                  ).map((value) => ({
                    value,
                    label:
                      value === 'COMPUTER_VISION'
                        ? '图像标注'
                        : value === 'TIME_SERIES'
                          ? '时序标注'
                          : taskTemplates.find(
                              (template) => taskTypeFilterKey(template.taskType) === value,
                            )?.name || value,
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
                aria-label="筛选已加载项目状态"
                value={projectStatus}
                onChange={setProjectStatus}
                style={{ minWidth: 115 }}
                options={[
                  { value: 'ALL', label: '全部状态' },
                  ...['DRAFT', 'OPEN', 'PAUSED', 'ARCHIVED'].map((value) => ({
                    value,
                    label: statusNames[value],
                  })),
                ]}
              />
            </div>
            <p className={styles.headingHint} style={{ marginBottom: 12 }}>
              已加载 {projects.length} 个项目 · 当前匹配{' '}
              {filteredProjects.length} 个
              {projectCursor ? '，可继续加载更多' : ''}
            </p>
            <Table<Project>
              rowKey="projectId"
              loading={loading}
              dataSource={filteredProjects}
              pagination={false}
              locale={{
                emptyText: (
                  <Empty
                    description={
                      projects.length
                        ? '没有匹配的已加载项目'
                        : '还没有项目，创建第一个标注项目'
                    }
                  />
                ),
              }}
              columns={[
                {
                  title: '项目名称',
                  dataIndex: 'name',
                  render: (name: string, item) => (
                    <Link to={projectEntryPath(item)}>{name}</Link>
                  ),
                },
                { title: '说明', dataIndex: 'description', ellipsis: true },
                {
                  title: '任务类型',
                  dataIndex: 'taskType',
                  render: (type: string) =>
                    taskTemplates.find((template) => template.taskType === type)
                      ?.name || type,
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  render: (status: string) => <StatusBadge status={status} />,
                },
                {
                  title: '创建时间',
                  dataIndex: 'createdAt',
                  render: (date: string) =>
                    new Date(date).toLocaleString('zh-CN'),
                },
                {
                  title: '项目操作',
                  key: 'actions',
                  render: (_, item) => (
                    <ProjectManagement
                      project={item}
                      onChanged={() => void listProjects()}
                      onDeleted={() => void listProjects()}
                    />
                  ),
                },
              ]}
              scroll={{ x: 700 }}
            />
            {projectCursor && (
              <Button
                className={styles.more}
                loading={loading}
                onClick={() => void listProjects(projectCursor)}
              >
                加载更多项目
              </Button>
            )}
          </Card>
        </div>
      )}
      {identity && projectId && loading && (
        <Spin>
          <div style={{ height: 180 }} />
        </Spin>
      )}
      {identity && projectId && !loading && !project && (
        <Button onClick={() => history.replace('/labeling')}>
          返回项目列表
        </Button>
      )}
      {project &&
        !['IMAGE_CLASSIFICATION', 'TIMESERIES_ANNOTATION'].includes(
          project.taskType,
        ) && (
          <Alert
            type="info"
            message="此任务模板暂未安装本地编辑器，请联系管理员。"
          />
        )}
      {project &&
        ['IMAGE_CLASSIFICATION', 'TIMESERIES_ANNOTATION'].includes(
          project.taskType,
        ) && (
          <>
            <Card className={styles.publishSteps} title="发布准备">
              {project.status === 'DRAFT' && (
                <Paragraph type="secondary">
                  草稿项目可直接准备并发布任务。首次发布成功后，项目会自动转为开放。
                </Paragraph>
              )}
              <Steps
                size="small"
                current={
                  setupStep === 2 &&
                  publishedBatchId &&
                  publishedBatchId === batch?.batchId
                    ? 3
                    : setupStep
                }
                onChange={uploadBusy || batchBusy ? undefined : changeStep}
                items={[
                  { title: '设置规范' },
                  {
                    title: '导入数据',
                    disabled: !project.specVersions?.length,
                  },
                  {
                    title: '确认并发布',
                    disabled: !project.specVersions?.length,
                  },
                ]}
              />
            </Card>
            {!writable && (
              <Alert
                type="info"
                showIcon
                message="当前项目不能上传或修改规范"
                description={
                  project.status === 'PAUSED'
                    ? '项目暂停期间不能读取原始图片。'
                    : '已归档项目只读。'
                }
              />
            )}
            <div>
              <div
                id="labeling-specification"
                hidden={setupStep !== 0}
                className={styles.sectionAnchor}
              >
                <SpecVersions
                  key={project.projectId}
                  project={project}
                  writable={Boolean(writable)}
                  selectedId={selectedSpecId}
                  onSelect={setSelectedSpecId}
                  onUpdated={setProject}
                />
              </div>
              <div hidden={setupStep !== 1}>
                <Card
                  title="2. 导入数据"
                  id="labeling-import"
                  className={styles.sectionAnchor}
                >
                  <Paragraph>
                    {project.taskType === 'TIMESERIES_ANNOTATION'
                      ? '上传站点日 ZIP：根 manifest.json 需包含 datasetVersion，数据位于 sample/station_day_tasks_2k.csv，每行对应一个站点日。完整小时/日资产和历史上下文尚未接入。'
                      : '仅导入未标注的 JPEG / PNG 图片 ZIP，压缩包内已有标签不会采用。服务器解析后可查看有效图片和错误明细。'}
                  </Paragraph>
                  <input
                    ref={fileInput}
                    type="file"
                    accept=".zip,application/zip"
                    hidden
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      event.target.value = '';
                      if (file) void upload(file);
                    }}
                  />
                  <Space wrap>
                    <Button
                      type="primary"
                      icon={<CloudUploadOutlined />}
                      loading={uploadBusy}
                      disabled={!writable || batchBusy}
                      onClick={() => fileInput.current?.click()}
                    >
                      选择 ZIP 并上传
                    </Button>
                    {uploadBusy && (
                      <Button onClick={() => uploadController.current?.abort()}>
                        暂停上传
                      </Button>
                    )}
                  </Space>
                  {uploadBusy && (
                    <div className={styles.uploadProgress}>
                      <Text>{uploadStage}</Text>
                      <Progress percent={progress} />
                    </div>
                  )}
                  <Paragraph className={styles.hint} type="secondary">
                    上传异常后重新选择同一文件可继续当前上传。刷新页面后可用批次编号找回已上传的批次。
                  </Paragraph>
                  <details>
                    <summary>打开已有导入批次</summary>
                    <Typography.Paragraph type="secondary">
                      下列只包含本机保存的批次记录；也可输入已知编号查询。
                    </Typography.Paragraph>
                    <Select
                      aria-label="本机已知批次"
                      placeholder="选择本机已知批次"
                      style={{ width: '100%', marginBottom: 16 }}
                      value={
                        knownBatchIds.includes(batchInput)
                          ? batchInput
                          : undefined
                      }
                      disabled={uploadBusy || batchBusy}
                      onChange={(id) => void restoreBatch(id)}
                      options={knownBatchIds.map((value) => ({
                        value,
                        label: value,
                      }))}
                    />
                    <Space.Compact block>
                      <Input
                        aria-label="导入批次编号"
                        value={batchInput}
                        onChange={(e) => setBatchInput(e.target.value)}
                        placeholder="输入批次编号，查询已有导入"
                        disabled={uploadBusy || batchBusy}
                      />
                      <Button
                        loading={batchBusy}
                        disabled={uploadBusy || !batchInput.trim()}
                        onClick={() => void restoreBatch(batchInput)}
                      >
                        查询批次
                      </Button>
                    </Space.Compact>
                  </details>
                </Card>
              </div>
            </div>
            {setupStep === 0 && project.specVersions?.length && (
              <Button
                type="primary"
                style={{ marginBottom: 20 }}
                onClick={() => changeStep(1)}
              >
                下一步：导入数据
              </Button>
            )}
            {setupStep === 1 && (
              <details>
                <summary>查看服务器导入历史</summary>
                <ProjectHistory
                  projectId={project.projectId}
                  kind="imports"
                  onOpen={(row) => {
                    if (row.batchId) void restoreBatch(row.batchId);
                  }}
                />
              </details>
            )}
            {setupStep === 1 && batch?.status === 'READY' && (
              <Button
                type="primary"
                style={{ marginBottom: 20 }}
                onClick={() => changeStep(2)}
              >
                下一步：确认并发布
              </Button>
            )}
            {setupStep === 1 && batch && (
              <Card
                title="导入批次"
                extra={
                  <Button
                    icon={<ReloadOutlined />}
                    disabled={uploadBusy}
                    loading={batchBusy}
                    onClick={() => void restoreBatch(batch.batchId)}
                  >
                    刷新批次
                  </Button>
                }
              >
                <Space wrap>
                  <Tag
                    color={
                      batch.status === 'READY'
                        ? 'green'
                        : batch.status === 'FAILED'
                          ? 'red'
                          : 'blue'
                    }
                  >
                    {statusNames[batch.status]}
                  </Tag>
                  <Text>有效样本 {batch.validSampleCount} 个</Text>
                  <Text>错误 {batch.errorCount} 项</Text>
                  <Text copyable>{batch.batchId}</Text>
                </Space>
                {batch.status === 'UPLOADING' && (
                  <Progress
                    percent={Math.floor(
                      (batch.receivedBytes / batch.sizeBytes) * 100,
                    )}
                  />
                )}
                {batch.status === 'PROCESSING' && (
                  <Paragraph className={styles.hint}>
                    数据解析中，页面会自动查询进度。
                  </Paragraph>
                )}
                {batch.status === 'FAILED' && (
                  <Alert
                    className={styles.hint}
                    type="error"
                    showIcon
                    message={batch.errorCode || '导入失败'}
                    description="可重试的完整原包允许重新解析；若文件内容有误，请修正 ZIP 后创建新导入。"
                    action={
                      batch.originalComplete ? (
                        <Button
                          loading={batchBusy}
                          onClick={() => void retryImport()}
                        >
                          重试解析
                        </Button>
                      ) : undefined
                    }
                  />
                )}
                {batch.errorCount > 0 && (
                  <details className={styles.hint}>
                    <summary>
                      查看导入错误（显示 {batch.errors?.length || 0} /{' '}
                      {batch.errorCount} 项）
                    </summary>
                    <Table
                      rowKey={(_, index) => String(index)}
                      dataSource={batch.errors}
                      size="small"
                      pagination={{ pageSize: 5 }}
                      columns={[
                        { title: '文件', dataIndex: 'sourcePath' },
                        { title: '错误码', dataIndex: 'code' },
                        { title: '原因', dataIndex: 'message' },
                      ]}
                    />
                  </details>
                )}
              </Card>
            )}
            {setupStep === 1 && batch?.status === 'READY' && (
              <Button
                style={{ marginBottom: 16 }}
                onClick={() => setShowImages((value) => !value)}
              >
                {showImages
                  ? '收起样本预览'
                  : `浏览导入的 ${batch.validSampleCount} 个样本`}
              </Button>
            )}
            {setupStep === 1 &&
              batch?.status === 'READY' &&
              showImages &&
              project.taskType === 'TIMESERIES_ANNOTATION' && (
                <Card title="站点日样本">
                  <Table<Sample>
                    rowKey="sampleId"
                    dataSource={samples}
                    loading={sampleBusy}
                    pagination={false}
                    columns={[
                      { title: '来源路径', dataIndex: 'sourcePath' },
                      {
                        title: '操作',
                        render: (_, r) => (
                          <Button onClick={() => setSelected(r)}>
                            查看曲线
                          </Button>
                        ),
                      },
                    ]}
                  />
                  {sampleCursor && (
                    <Button
                      disabled={sampleBusy}
                      onClick={() => void listSamples(sampleCursor)}
                    >
                      更多样本
                    </Button>
                  )}
                  {selected &&
                    project.specVersions?.find(
                      (s) => s.specVersionId === selectedSpecId,
                    ) && (
                      <StructuredPreview
                        projectId={project.projectId}
                        spec={
                          project.specVersions.find(
                            (s) => s.specVersionId === selectedSpecId,
                          ) || project.specVersions[0]
                        }
                        row={{
                          sampleId: selected.sampleId,
                          resultId: null,
                          result: null,
                          reviewRevision: 0,
                        }}
                      />
                    )}
                </Card>
              )}
            {setupStep === 1 &&
              batch?.status === 'READY' &&
              showImages &&
              project.taskType === 'IMAGE_CLASSIFICATION' && (
                <div className={styles.workspace}>
                  <Card
                    title="图片列表"
                    extra={
                      <Button
                        loading={sampleBusy}
                        onClick={() => void listSamples()}
                      >
                        刷新
                      </Button>
                    }
                  >
                    <Table<Sample>
                      rowKey="sampleId"
                      dataSource={samples}
                      loading={sampleBusy}
                      pagination={false}
                      size="small"
                      columns={[
                        {
                          title: '图片',
                          dataIndex: 'sourcePath',
                          render: (path: string, item) => (
                            <Button
                              type="link"
                              className={styles.sampleButton}
                              onClick={() => {
                                setSelected(item);
                              }}
                            >
                              {path}
                            </Button>
                          ),
                        },
                        {
                          title: '尺寸',
                          render: (_, item) =>
                            `${item.metadata.width} × ${item.metadata.height}`,
                        },
                      ]}
                      rowClassName={(item) =>
                        item.sampleId === selected?.sampleId
                          ? styles.selectedRow
                          : ''
                      }
                      scroll={{ x: 300, y: 480 }}
                    />
                    {sampleCursor && (
                      <Button
                        className={styles.more}
                        loading={sampleBusy}
                        onClick={() => void listSamples(sampleCursor)}
                      >
                        加载更多图片
                      </Button>
                    )}
                  </Card>
                  <Card title="图片预览">
                    {selected ? (
                      <>
                        <Paragraph ellipsis={{ tooltip: selected.sourcePath }}>
                          {selected.sourcePath}
                        </Paragraph>
                        {project.status === 'PAUSED' ? (
                          <Alert
                            type="info"
                            message="项目已暂停，原图不可读取"
                          />
                        ) : (
                          <Preview
                            key={selected.sampleId}
                            sample={selected}
                            projectId={project.projectId}
                          />
                        )}
                        <Alert
                          style={{ marginTop: 20 }}
                          type="info"
                          showIcon
                          message="此处是需求方图片预览"
                          description={
                            <span>
                              保存分类需要先发布任务包并领取，进入
                              <Link to="/labeling/assignments">我的任务</Link>
                              完成逐张草稿保存与整包提交。
                            </span>
                          }
                        />
                      </>
                    ) : (
                      <Empty description="此批次暂无图片" />
                    )}
                  </Card>
                </div>
              )}
            {setupStep === 2 && batch?.status !== 'READY' && (
              <Card
                id="labeling-publish"
                title="3. 任务发布"
                className={styles.sectionAnchor}
                style={{ marginBottom: 20 }}
              >
                <Alert
                  showIcon
                  type="info"
                  message="完成发布准备后，即可生成任务包预览"
                  description={
                    !project.specVersions?.length
                      ? '请先保存至少一个标注规范，再导入数据。'
                      : batch
                        ? `当前批次${statusNames[batch.status] || batch.status}，待样本解析就绪后可发布。`
                        : '请导入数据 ZIP，或打开已有批次；样本就绪后可预览并确认发布。'
                  }
                />
                <Space wrap style={{ marginTop: 16 }}>
                  {!project.specVersions?.length && (
                    <Button onClick={() => changeStep(0)}>配置分类规范</Button>
                  )}
                  <Button onClick={() => changeStep(1)}>准备导入数据</Button>
                </Space>
              </Card>
            )}
            {batch?.status === 'READY' && (
              <div
                hidden={setupStep !== 2}
                id="labeling-publish"
                style={{ scrollMarginTop: 80 }}
              >
                <PublishPlan
                  key={`${project.projectId}:${batch.batchId}`}
                  projectId={project.projectId}
                  batchId={batch.batchId}
                  specVersionId={
                    selectedSpecId || project.specVersions?.[0]?.specVersionId
                  }
                  writable={Boolean(writable)}
                  onPublished={() => {
                    setPublishedBatchId(batch.batchId);
                    void labelingJson<Project>(
                      `/projects/${encodeURIComponent(project.projectId)}`,
                    )
                      .then((value) => {
                        if (currentProjectId.current === value.projectId)
                          setProject(value);
                      })
                      .catch((e: unknown) => setError(errorText(e)));
                  }}
                />
              </div>
            )}
            {setupStep === 2 && project.status !== 'DRAFT' && (
              <Card title="发布之后做什么？">
                <Space wrap>
                  <Link to="/labeling/tasks">到任务广场领取标注</Link>
                  <Link
                    to={`/labeling/projects/${encodeURIComponent(project.projectId)}/workbench`}
                  >
                    到结果工作台审核答案
                  </Link>
                </Space>
              </Card>
            )}
          </>
        )}
      <Modal
        title="创建标注项目"
        open={creating}
        confirmLoading={createBusy}
        onOk={() => void createProject()}
        onCancel={() => {
          if (!createBusy) setCreating(false);
        }}
        okText="创建项目"
        cancelText="取消"
        closable={!createBusy}
        maskClosable={!createBusy}
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="name"
            label="项目名称"
            rules={[
              { required: true, whitespace: true, message: '请输入项目名称' },
            ]}
          >
            <Input maxLength={120} disabled={createBusy} />
          </Form.Item>
          <Form.Item name="description" label="项目说明">
            <Input.TextArea maxLength={4000} rows={3} disabled={createBusy} />
          </Form.Item>
          <Form.Item label="任务类型">
            <Select
              value={taskType}
              onChange={setTaskType}
              disabled={createBusy}
              options={taskTemplates.map((template) => ({
                value: template.taskType,
                label: template.name,
                disabled: !available.some(
                  (t) =>
                    t.templateId === template.id &&
                    t.templateVersion === template.version &&
                    t.enabled,
                ),
              }))}
            />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message={
              templateError ||
              '仅开放服务器已注册并与本地编辑器匹配的模板。能耗 ZIP 需包含 manifest.json 和 sample/station_day_tasks_2k.csv。'
            }
          />
        </Form>
      </Modal>
    </WorkspaceLayout>
  );
}
