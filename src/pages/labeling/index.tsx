import {
  ArrowLeftOutlined,
  CloudUploadOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { history, Link, useParams } from '@umijs/max';
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
  Radio,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import type {
  Batch,
  Label,
  Page,
  Project,
  Sample,
  SpecVersion,
} from '@/services/labeling';
import {
  LabelingError,
  labelingJson,
  operationKey,
  pageQuery,
  post,
  sampleBlob,
} from '@/services/labeling';
import { hashFile, uploadChunks } from '@/services/labelingUpload.mjs';
import styles from './index.less';

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
  const [createForm] = Form.useForm<{ name: string; description: string }>();
  const createAttempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  const [specForm] = Form.useForm<{ labels: string; instructions: string }>();
  const [specBusy, setSpecBusy] = useState(false);
  const specAttempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  const [batch, setBatch] = useState<Batch>();
  const [batchInput, setBatchInput] = useState('');
  const [batchBusy, setBatchBusy] = useState(false);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [sampleCursor, setSampleCursor] = useState<string | null>(null);
  const [sampleBusy, setSampleBusy] = useState(false);
  const [selected, setSelected] = useState<Sample>();
  const [category, setCategory] = useState('');
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
  const latestSpec: SpecVersion | undefined = project?.specVersions?.reduce<
    SpecVersion | undefined
  >(
    (latest, spec) =>
      !latest || spec.versionNo > latest.versionNo ? spec : latest,
    undefined,
  );
  const writable = project?.status === 'DRAFT' || project?.status === 'OPEN';
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
      } catch {
        /* URL remains shareable without local storage. */
      }
    }
    history.replace(
      `/labeling/projects/${encodeURIComponent(value.projectId)}?batch=${encodeURIComponent(value.batchId)}`,
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
      setCategory('');
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
    setSamples([]);
    setSelected(undefined);
    setCategory('');
    setError('');
    setUploadBusy(false);
    setBatchInput('');
    setBatchBusy(false);
    setSampleBusy(false);
    specAttempt.current = undefined;
    uploadAttempt.current = undefined;
    specForm.resetFields();
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
        setCategory('');
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

  async function saveSpec() {
    if (!projectId) return;
    const values = await specForm.validateFields().catch(() => null);
    if (!values) return;
    const names = values.labels
      .split(/\r?\n/)
      .map((name: string) => name.trim())
      .filter(Boolean);
    if (new Set(names).size !== names.length) {
      message.error('分类名称不能重复');
      return;
    }
    const labels: Label[] = names.map((name: string, index: number) => ({
      id: `category-${index + 1}`,
      name,
    }));
    const body = {
      taskType,
      labels,
      instructions: values.instructions?.trim() || '',
      editorConfig: {},
      schemaVersion: '1.0',
    };
    const payload = JSON.stringify({ projectId, body });
    if (specAttempt.current?.payload !== payload)
      specAttempt.current = { payload, key: operationKey() };
    setSpecBusy(true);
    try {
      await post(
        `/projects/${encodeURIComponent(projectId)}/spec-versions`,
        body,
        specAttempt.current.key,
      );
      const next = await labelingJson<Project>(
        `/projects/${encodeURIComponent(projectId)}`,
      );
      if (currentProjectId.current !== projectId) return;
      setProject(next);
      specForm.resetFields();
      specAttempt.current = undefined;
      message.success('分类标签与标注说明已保存');
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setSpecBusy(false);
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

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <Link to="/platforms" className={styles.brand}>
          TSS <span>/</span> <small>DATA ANNOTATION</small>
        </Link>
        <Link to="/label-projects">打开原演示版</Link>
      </header>
      <section className={styles.content}>
        <Link to={projectId ? '/labeling' : '/platforms'}>
          <ArrowLeftOutlined /> {projectId ? '我的项目' : '平台选择'}
        </Link>
        <div className={styles.heading}>
          <div>
            <Text type="secondary">图片分类 · 工作空间</Text>
            <h1>{project?.name || '数据标注平台'}</h1>
            <Paragraph type="secondary">
              {project?.description ||
                '创建项目，导入图片，组织分类标签与标注说明。'}
            </Paragraph>
          </div>
          {project && (
            <Tag>{statusNames[project.status] || project.status}</Tag>
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
              dataSource={projects}
              pagination={false}
              locale={{
                emptyText: (
                  <Empty description="还没有项目，创建第一个图片分类项目" />
                ),
              }}
              columns={[
                {
                  title: '项目名称',
                  dataIndex: 'name',
                  render: (name: string, item) => (
                    <Link
                      to={`/labeling/projects/${encodeURIComponent(item.projectId)}`}
                    >
                      {name}
                    </Link>
                  ),
                },
                { title: '说明', dataIndex: 'description', ellipsis: true },
                {
                  title: '任务类型',
                  dataIndex: 'taskType',
                  render: (type: string) =>
                    type === taskType ? '图片分类' : type,
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
            message="当前工作台仅支持图片分类，请创建 IMAGE_CLASSIFICATION 项目。"
          />
        )}
        {project?.taskType === taskType && (
          <>
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
              <Card title="1. 分类标签与标注说明">
                {latestSpec ? (
                  <>
                    <Space wrap>
                      {latestSpec.labels.map((label) => (
                        <Tag key={label.id} color="blue">
                          {label.name}
                        </Tag>
                      ))}
                    </Space>
                    <Paragraph className={styles.instructions}>
                      {latestSpec.instructions || '暂无标注说明'}
                    </Paragraph>
                    <Text type="secondary">
                      规范版本 {latestSpec.versionNo} · 已保存到平台
                    </Text>
                  </>
                ) : (
                  <Form
                    form={specForm}
                    layout="vertical"
                    onFinish={() => void saveSpec()}
                  >
                    <Form.Item
                      name="labels"
                      label="分类名称（每行一类）"
                      rules={[
                        {
                          required: true,
                          whitespace: true,
                          message: '请至少输入一个分类',
                        },
                      ]}
                    >
                      <Input.TextArea
                        rows={4}
                        maxLength={4000}
                        placeholder={'正常\n划痕\n破损'}
                        disabled={!writable}
                      />
                    </Form.Item>
                    <Form.Item name="instructions" label="标注说明">
                      <Input.TextArea
                        rows={3}
                        maxLength={10000}
                        placeholder="说明分类依据与容易混淆的情况"
                        disabled={!writable}
                      />
                    </Form.Item>
                    <Button
                      type="primary"
                      htmlType="submit"
                      loading={specBusy}
                      disabled={!writable}
                    >
                      保存分类规范
                    </Button>
                  </Form>
                )}
              </Card>
              <Card title="2. 导入图片">
                <Paragraph>
                  上传包含 JPEG / PNG 图片的 ZIP
                  压缩包。上传后由服务器解析，部分图片失败时可查看明细。
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
            {batch?.status === 'READY' && (
              <div className={styles.workspace}>
                <Card
                  title="3. 图片列表"
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
                              setCategory('');
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
                <Card title="4. 图片预览与分类选择">
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
                      <div className={styles.classification}>
                        <Text strong>选择分类</Text>
                        {latestSpec ? (
                          <Radio.Group
                            value={category}
                            disabled={!writable}
                            onChange={(e) => setCategory(e.target.value)}
                            options={latestSpec.labels.map((label) => ({
                              label: label.name,
                              value: label.id,
                            }))}
                            optionType="button"
                          />
                        ) : (
                          <Text type="secondary">
                            请先保存分类标签与标注说明。
                          </Text>
                        )}
                        <Alert
                          type="warning"
                          showIcon
                          message="分类保存待接入"
                          description="当前可预览图片和试选分类，所选内容尚未保存。待领取、分类保存接口及编辑器同步链路接入后，才能保存到平台。"
                        />
                        <Button type="primary" disabled>
                          保存标注（待接入）
                        </Button>
                      </div>
                    </>
                  ) : (
                    <Empty description="此批次暂无图片" />
                  )}
                </Card>
              </div>
            )}
          </>
        )}
      </section>
      <Modal
        title="创建图片分类项目"
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
              disabled
              options={[{ value: taskType, label: '图片分类' }]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  );
}
