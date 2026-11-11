import {
  CloudUploadOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { history, Link, useLocation, useParams } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
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
import {
  previewDraftDeletion,
  projectEntryPath,
} from '@/services/labelingProjectActions';
import { taskTemplates } from '@/services/labelingTemplates.mjs';
import { hashFile, uploadChunks } from '@/services/labelingUpload.mjs';
import { CapabilitiesNotice } from './gapDemo';
import styles from './index.less';
import ProjectSubmissions from './ProjectSubmissions';
import PublishPlan from './PublishPlan';
import SpecVersions from './SpecVersions';
import WorkspaceLayout from './WorkspaceLayout';

const { Text, Paragraph } = Typography;
const taskType = 'IMAGE_CLASSIFICATION';
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
  const { message } = App.useApp();
  const { projectId } = useParams<{ projectId?: string }>();
  const [identity, setIdentity] = useState<Identity>();
  const [identityError, setIdentityError] = useState('');
  const [identityRetry, setIdentityRetry] = useState(0);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectCursor, setProjectCursor] = useState<string | null>(null);
  const [project, setProject] = useState<Project>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const [deletingProject, setDeletingProject] = useState<Project>();
  const [deleteBusy, setDeleteBusy] = useState(false);
  const deleteLock = useRef(false);
  const [previewDeleted, setPreviewDeleted] = useState<string[]>([]);
  const [createForm] = Form.useForm<{ name: string; description: string }>();
  const createAttempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  const [selectedSpecId, setSelectedSpecId] = useState<string>();
  const [batch, setBatch] = useState<Batch>();
  const [publishedBatchId, setPublishedBatchId] = useState('');
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
  useEffect(() => {
    if (new URLSearchParams(location.search).get('panel') === 'publish')
      document
        .getElementById('labeling-publish')
        ?.scrollIntoView({ block: 'start' });
  }, [location.search, project?.projectId, batch?.batchId, batch?.status]);
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
      if (!cursor) setPreviewDeleted([]);
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
    const values = await createForm.validateFields().catch(() => null);
    if (!values) return;
    const body = {
      name: values.name.trim(),
      description: values.description?.trim() || '',
      taskType,
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
      message.success('项目已创建，请添加分类标签和标注说明');
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
      message.error('请选择非空 ZIP 文件，内含 JPEG/PNG 图片');
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
        setUploadStage('上传图片压缩包');
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
          active.status === 'READY' ? '图片已就绪' : '上传已完成，正在解析图片',
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

  async function deleteDraft() {
    if (!deletingProject || deleteLock.current) return;
    deleteLock.current = true;
    setDeleteBusy(true);
    try {
      const receipt = await previewDraftDeletion(deletingProject);
      setPreviewDeleted((old) => [...new Set([...old, receipt.projectId])]);
      setDeletingProject(undefined);
      message.success('草稿已从本页预览列表移除，服务器数据保留');
    } catch (e) {
      message.error(errorText(e));
    } finally {
      deleteLock.current = false;
      setDeleteBusy(false);
    }
  }

  return (
    <WorkspaceLayout project={project}>
      <div className={styles.heading}>
        <div>
          <div className={styles.eyebrow}>
            数据标注平台 / {projectId ? '任务发布' : '我的项目'}
          </div>
          <h1>
            {project?.name || (projectId ? '正在读取项目' : '数据标注工作空间')}
          </h1>
          {!projectId && (
            <p className={styles.headingHint}>
              创建项目，选择任务模板；准备规范与数据后发布任务，完成标注后审核并导出成果。
            </p>
          )}
          {project?.description && (
            <Paragraph type="secondary">{project.description}</Paragraph>
          )}
        </div>
        {project && (
          <Space wrap>
            <Tag>{statusNames[project.status] || project.status}</Tag>
            {project.status !== 'DRAFT' && (
              <Link
                to={`/labeling/projects/${encodeURIComponent(project.projectId)}/workbench`}
              >
                <Button>查看结果工作台</Button>
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
        <>
          <section className={styles.projectStats} aria-label="已加载项目统计">
            {[
              [
                '已加载项目',
                projects.filter((p) => !previewDeleted.includes(p.projectId))
                  .length,
              ],
              [
                '开放项目',
                projects.filter(
                  (p) =>
                    p.status === 'OPEN' &&
                    !previewDeleted.includes(p.projectId),
                ).length,
              ],
              [
                '草稿项目',
                projects.filter(
                  (p) =>
                    p.status === 'DRAFT' &&
                    !previewDeleted.includes(p.projectId),
                ).length,
              ],
              [
                '已归档',
                projects.filter(
                  (p) =>
                    p.status === 'ARCHIVED' &&
                    !previewDeleted.includes(p.projectId),
                ).length,
              ],
            ].map(([label, value]) => (
              <div key={label}>
                <span>{label}</span>
                <strong>{loading ? '—' : value}</strong>
              </div>
            ))}
          </section>
          <Alert
            className={styles.deleteNotice}
            type="info"
            showIcon
            message="草稿删除为本地预览"
            description={
              previewDeleted.length
                ? `已从本页预览移除 ${previewDeleted.length} 个草稿。服务器数据未删除，刷新列表可恢复。`
                : '仅草稿项目显示删除操作。后端删除接口尚未提供，预览删除不会修改服务器数据。'
            }
          />
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
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  disabled={!identity.canCreateProject}
                  onClick={() => setCreating(true)}
                >
                  创建项目
                </Button>
              </Space>
            }
          >
            <Table<Project>
              rowKey="projectId"
              loading={loading}
              dataSource={projects.filter(
                (item) => !previewDeleted.includes(item.projectId),
              )}
              pagination={false}
              locale={{
                emptyText: (
                  <Empty description="还没有项目，创建第一个标注项目" />
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
                  render: (status: string) => (
                    <Tag>{statusNames[status] || status}</Tag>
                  ),
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
                    <Space wrap>
                      <Link to={projectEntryPath(item)}>进入工作台</Link>
                      {item.status !== 'DRAFT' && (
                        <Link
                          to={`/labeling/projects/${encodeURIComponent(item.projectId)}`}
                        >
                          任务发布
                        </Link>
                      )}
                      {item.status === 'DRAFT' && (
                        <Button
                          type="link"
                          danger
                          size="small"
                          onClick={() => setDeletingProject(item)}
                        >
                          删除项目
                        </Button>
                      )}
                    </Space>
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
          {process.env.NODE_ENV !== 'production' &&
            process.env.LABELING_GAP_MOCK === 'true' && (
              <Card title="待接入能力 · 独立流程预览" style={{ marginTop: 20 }}>
                <Paragraph type="secondary">
                  使用独立合成数据体验审核、AI、再次发布、成果导出和时序试标；预览操作不会修改真实项目。
                </Paragraph>
                <Space wrap>
                  <Link to="/labeling/demo">
                    <Button>审核、AI与成果预览</Button>
                  </Link>
                  <Link to="/labeling/demo/energy">
                    <Button>能耗时序与质检预览</Button>
                  </Link>
                </Space>
              </Card>
            )}
        </>
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
      {project && project.taskType !== taskType && (
        <Alert
          type="info"
          message="此任务模板的正式导入与发布尚未接入，可返回项目列表使用独立流程预览。"
        />
      )}
      {project?.taskType === taskType && (
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
                !project.specVersions?.length
                  ? 0
                  : batch?.status !== 'READY'
                    ? 1
                    : publishedBatchId === batch.batchId
                      ? 3
                      : 2
              }
              items={[
                { title: '保存规范' },
                { title: '导入数据' },
                { title: '预览并发布' },
                { title: '领取与审核' },
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
          <div className={styles.setup}>
            <div id="labeling-specification" className={styles.sectionAnchor}>
              <SpecVersions
                key={project.projectId}
                project={project}
                writable={Boolean(writable)}
                selectedId={selectedSpecId}
                onSelect={setSelectedSpecId}
                onUpdated={setProject}
              />
            </div>
            <Card
              title="2. 导入图片"
              id="labeling-import"
              className={styles.sectionAnchor}
            >
              <Paragraph>
                仅导入未标注的 JPEG / PNG
                图片；压缩包内已有标签不会采用。非支持文件由批次错误明细报告未导入。上传
                ZIP 压缩包。上传后由服务器解析，部分图片失败时可查看明细。
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
                上传异常后重新选择同一文件可继续当前上传。刷新页面后可用批次编号找回已上传的图片。
              </Paragraph>
              <Typography.Paragraph type="secondary">
                完整批次历史待后端接入；下列仅为本机已知批次，点击后查询真实详情。
              </Typography.Paragraph>
              <Select
                aria-label="本机已知批次"
                placeholder="选择本机已知批次"
                style={{ width: '100%', marginBottom: 16 }}
                value={
                  knownBatchIds.includes(batchInput) ? batchInput : undefined
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
            </Card>
          </div>
          {batch && (
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
                <Text>有效图片 {batch.validSampleCount} 张</Text>
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
                  图片解析中，页面会自动查询进度。
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
          {batch?.status === 'READY' ? (
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
                      <Alert type="info" message="项目已暂停，原图不可读取" />
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
          ) : (
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
                    ? '请先保存至少一个分类规范，再导入图片。'
                    : batch
                      ? `当前批次${statusNames[batch.status] || batch.status}，待图片解析就绪后可发布。`
                      : '请导入图片 ZIP，或在上方打开已有批次；图片就绪后可预览并确认发布。'
                }
              />
              <Space wrap style={{ marginTop: 16 }}>
                {!project.specVersions?.length && (
                  <Button
                    onClick={() =>
                      document
                        .getElementById('labeling-specification')
                        ?.scrollIntoView({ block: 'start' })
                    }
                  >
                    配置分类规范
                  </Button>
                )}
                <Button
                  onClick={() =>
                    document
                      .getElementById('labeling-import')
                      ?.scrollIntoView({ block: 'start' })
                  }
                >
                  准备导入数据
                </Button>
              </Space>
            </Card>
          )}
          {batch?.status === 'READY' && (
            <div id="labeling-publish" style={{ scrollMarginTop: 80 }}>
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
          {project.status !== 'DRAFT' && (
            <>
              <Link
                to={`/labeling/projects/${encodeURIComponent(project.projectId)}/workbench`}
              >
                进入需求方结果工作台
              </Link>
              <CapabilitiesNotice />
              <ProjectSubmissions
                key={project.projectId}
                projectId={project.projectId}
              />
            </>
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
              options={taskTemplates.map((template) => ({
                value: template.taskType,
                label: template.name,
                disabled: !template.enabled,
              }))}
            />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message="能耗模板待后端模板注册、导入及授权切片接口接入，目前仅开放独立合成试标。"
          />
        </Form>
        {process.env.NODE_ENV !== 'production' &&
          process.env.LABELING_GAP_MOCK === 'true' && (
            <Space wrap style={{ marginTop: 16 }}>
              <Link to="/labeling/demo/energy">预览能耗时序标注</Link>
              <Link to="/labeling/demo">预览审核、AI与成果流程</Link>
            </Space>
          )}
      </Modal>
      <Modal
        title="删除草稿项目（本地预览）"
        open={Boolean(deletingProject)}
        confirmLoading={deleteBusy}
        onOk={() => void deleteDraft()}
        onCancel={() => {
          if (!deleteBusy) setDeletingProject(undefined);
        }}
        okText="确认预览删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        closable={!deleteBusy}
        maskClosable={!deleteBusy}
      >
        <Paragraph>
          项目：<Text strong>{deletingProject?.name}</Text>
        </Paragraph>
        <Alert
          type="warning"
          showIcon
          message="后端尚未提供项目删除接口"
          description="本次仅从当前页面的预览列表移除草稿，不删除服务器项目或文件。刷新列表后恢复。确认前将重新核对项目状态和版本。"
        />
      </Modal>
    </WorkspaceLayout>
  );
}
