import {
  CheckOutlined,
  CheckSquareOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  EditOutlined,
  EyeOutlined,
  FileTextOutlined,
  LeftOutlined,
  PictureOutlined,
  RightOutlined,
  RobotOutlined,
  SettingOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, Link, useLocation } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Drawer,
  Empty,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import {
  describeLabelingError,
  type Project,
  sampleBlob,
} from '@/services/labeling';
import {
  type Submission,
  type SubmissionSummary,
  tasks,
} from '@/services/labelingTasks';
import ProjectSubmissions from './ProjectSubmissions';
import a from './projectReview.less';
import s from './reviewWorkbench.less';

type Item = Submission['items'][number];
const tabs = [
  { key: 'review', label: '结果与审核' },
  { key: 'publish', label: '范围发布' },
  { key: 'ai', label: 'AI进度与分档' },
  { key: 'sources', label: '来源与集成' },
  { key: 'export', label: '成果导出' },
];

function OriginalImage({
  project,
  sampleId,
  thumbnail = false,
  onSize,
}: {
  project: Project;
  sampleId?: string;
  thumbnail?: boolean;
  onSize?: (id: string, width: number, height: number) => void;
}) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const target = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(!thumbnail);
  useEffect(() => {
    if (!thumbnail || !target.current) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true);
        observer.disconnect();
      }
    });
    observer.observe(target.current);
    return () => observer.disconnect();
  }, [thumbnail]);
  useEffect(() => {
    const abort = new AbortController();
    let objectUrl = '';
    setUrl('');
    setError('');
    setLoading(false);
    if (visible && sampleId && project.status !== 'PAUSED') {
      setLoading(true);
      void sampleBlob(project.projectId, sampleId, abort.signal)
        .then((blob) => {
          if (abort.signal.aborted) return;
          objectUrl = URL.createObjectURL(blob);
          setUrl(objectUrl);
        })
        .catch((e) => {
          if (!abort.signal.aborted) setError(describeLabelingError(e));
        })
        .finally(() => {
          if (!abort.signal.aborted) setLoading(false);
        });
    }
    return () => {
      abort.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [project.projectId, project.status, sampleId, retry, visible]);
  if (thumbnail)
    return (
      <div className={a.thumb} ref={target} title={error || sampleId}>
        {url ? (
          <img
            src={url}
            alt={`缩略图 ${sampleId}`}
            onError={() => {
              setUrl('');
              setError('缩略图解码失败');
            }}
          />
        ) : (
          <PictureOutlined
            aria-label={error ? '缩略图读取失败' : '缩略图待加载'}
          />
        )}
      </div>
    );
  return (
    <>
      <div className={a.image} data-sample-id={sampleId || ''}>
        {loading ? (
          <Spin tip="正在读取授权原图">
            <div style={{ padding: 32 }} />
          </Spin>
        ) : url ? (
          <img
            src={url}
            alt={`样本 ${sampleId} 的授权原图`}
            onLoad={(event) => {
              if (sampleId)
                onSize?.(
                  sampleId,
                  event.currentTarget.naturalWidth,
                  event.currentTarget.naturalHeight,
                );
            }}
            onError={() => {
              setUrl('');
              setError('原图解码失败，请重试');
            }}
          />
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              project.status === 'PAUSED'
                ? '项目已暂停，原图不可读取'
                : error
                  ? '原图读取失败'
                  : '选择样本后查看原图'
            }
          />
        )}
      </div>
      {error && (
        <Alert
          className={s.error}
          type="error"
          showIcon
          message={error}
          action={
            <Button onClick={() => setRetry((n) => n + 1)}>重试原图</Button>
          }
        />
      )}
    </>
  );
}

/** Real submissions only. Review/result APIs are not implemented by this backend. */
export default function ProjectReviewWorkbench({
  project,
}: {
  project: Project;
}) {
  const reasonId = useId();
  const location = useLocation();
  const requestedView =
    new URLSearchParams(location.search).get('view') || 'review';
  const activeTab = tabs.some((tab) => tab.key === requestedView)
    ? requestedView
    : 'review';
  const setActiveTab = (view: string) =>
    history.push(
      `/labeling/projects/${encodeURIComponent(project.projectId)}/workbench${view === 'review' ? '' : `?view=${view}`}`,
    );
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [imageSize, setImageSize] = useState<{
    sampleId: string;
    width: number;
    height: number;
  }>();
  const [summaries, setSummaries] = useState<SubmissionSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState('');
  const [submissionId, setSubmissionId] = useState('');
  const [storedSubmission, setSubmission] = useState<Submission>();
  const submission =
    storedSubmission?.submissionId === submissionId
      ? storedSubmission
      : undefined;
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [selectedId, setSelectedId] = useState('');
  const [chosen, setChosen] = useState<React.Key[]>([]);
  const [source, setSource] = useState<string>();
  const [answer, setAnswer] = useState<string>();
  const [loadedLimit, setLoadedLimit] = useState(20);
  const [historyOpen, setHistoryOpen] = useState(false);
  const listAbort = useRef<AbortController | undefined>(undefined);

  async function loadSummaries(next?: string | null) {
    listAbort.current?.abort();
    const abort = new AbortController();
    listAbort.current = abort;
    setListLoading(true);
    setListError('');
    if (!next) {
      setSummaries([]);
      setCursor(null);
      setSubmissionId('');
      setSubmission(undefined);
      setSelectedId('');
    }
    try {
      const page = await tasks.submissions(
        project.projectId,
        next,
        abort.signal,
      );
      if (abort.signal.aborted) return;
      if (next && page.nextCursor === next)
        throw new Error('提交分页游标重复，请重新读取');
      setSummaries((old) => [
        ...new Map(
          [...(next ? old : []), ...page.items].map((item) => [
            item.submissionId,
            item,
          ]),
        ).values(),
      ]);
      setCursor(page.nextCursor);
      if (!next) setSubmissionId(page.items[0]?.submissionId || '');
    } catch (e) {
      if (!abort.signal.aborted) setListError(describeLabelingError(e));
    } finally {
      if (!abort.signal.aborted) setListLoading(false);
    }
  }
  useEffect(() => {
    setHistoryOpen(false);
    void loadSummaries();
    return () => listAbort.current?.abort();
  }, [project.projectId]);
  useEffect(() => {
    const abort = new AbortController();
    setSubmission(undefined);
    setError('');
    setSelectedId('');
    setChosen([]);
    setSource(undefined);
    setAnswer(undefined);
    setLoadedLimit(20);
    setLoading(false);
    if (submissionId) {
      setLoading(true);
      void tasks
        .submission(submissionId, abort.signal)
        .then((value) => {
          if (abort.signal.aborted) return;
          if (
            value.projectId !== project.projectId ||
            value.submissionId !== submissionId ||
            !Array.isArray(value.items)
          )
            throw new Error('提交详情与当前项目不匹配或响应格式异常');
          setSubmission(value);
        })
        .catch((e) => {
          if (!abort.signal.aborted) setError(describeLabelingError(e));
        })
        .finally(() => {
          if (!abort.signal.aborted) setLoading(false);
        });
    }
    return () => abort.abort();
  }, [project.projectId, submissionId, retry]);
  const filtered = useMemo(
    () =>
      (submission?.items || []).filter(
        (item) =>
          (!source || source === 'HUMAN') &&
          (!answer || (answer === 'true') === Boolean(item.result.labelId)),
      ),
    [submission, source, answer],
  );
  const rows = filtered.slice(0, loadedLimit);
  const selected = rows.find((item) => item.sampleId === selectedId);
  const index = rows.findIndex((item) => item.sampleId === selectedId);
  useEffect(() => {
    setSelectedId((old) =>
      filtered.slice(0, loadedLimit).some((item) => item.sampleId === old)
        ? old
        : filtered[0]?.sampleId || '',
    );
  }, [filtered, loadedLimit]);
  const spec = project.specVersions?.find(
    (value) => value.specVersionId === submission?.specVersionId,
  );
  const label = (item: Item) =>
    spec?.labels.find((value) => value.id === item.result.labelId)?.name ||
    item.result.labelId ||
    '—';
  function changeFilter(kind: 'source' | 'answer', value?: string) {
    if (kind === 'source') setSource(value);
    else setAnswer(value);
    setChosen([]);
    setLoadedLimit(20);
  }
  const ready = Boolean(submission && !loading);
  const projectData = `/labeling/projects/${encodeURIComponent(project.projectId)}`;
  const candidates = (submission?.items || []).filter(
    () => !source || source === 'HUMAN',
  );
  const hasAnswer = candidates.filter((item) =>
    Boolean(item.result.labelId),
  ).length;
  const currentSize =
    imageSize?.sampleId === selectedId
      ? `${imageSize.width} × ${imageSize.height}`
      : '—';
  const chooseSample = (id: string) => {
    setSelectedId(id);
    setInspectorOpen(true);
  };
  const scopeName =
    tabs.find((tab) => tab.key === activeTab)?.label || '结果审核';
  if (activeTab !== 'review')
    return (
      <div className={a.other}>
        <Space wrap style={{ marginBottom: 20 }}>
          <Button onClick={() => setActiveTab('review')}>返回结果审核</Button>
          <Button onClick={() => setActiveTab('sources')}>来源与集成</Button>
          <Button onClick={() => setActiveTab('ai')}>AI进度与分档</Button>
        </Space>
        <h1>{scopeName}</h1>
        {activeTab === 'publish' && (
          <Card title="范围发布">
            <p>使用现有冻结规范与导入批次创建固定范围人工任务包。</p>
            <Link to={`${projectData}?panel=publish`}>
              <Button type="primary">管理规范、导入与发布</Button>
            </Link>
          </Card>
        )}
        {activeTab === 'ai' && (
          <Card title="AI进度与分档">
            <Empty description="AI执行、进度与置信度分档待后端接入" />
            <Button disabled>AI范围执行 · 待后端接入</Button>
          </Card>
        )}
        {activeTab === 'sources' && (
          <>
            <ProjectSubmissions projectId={project.projectId} />
            <Card title="来源与集成">
              <Button disabled>集成建议 · 待后端接入</Button>
            </Card>
          </>
        )}
        {activeTab === 'export' && (
          <Card title="成果导出">
            <p>
              已交回人工提交保留只读详情，需求方确认与固定成果快照待后端接入。
            </p>
            <Button disabled>成果CSV导出 · 待后端接入</Button>
          </Card>
        )}
      </div>
    );
  return (
    <>
      <Alert
        style={{ margin: '20px 22px 0' }}
        showIcon
        type="info"
        message="当前展示已交回的人工结果；审核、修订、AI与成果接口尚未接入。"
        action={
          process.env.NODE_ENV !== 'production' &&
          process.env.LABELING_GAP_MOCK === 'true' ? (
            <Link to="/labeling/demo">
              <Button size="small">预览完整流程</Button>
            </Link>
          ) : undefined
        }
      />
      <section
        className={`${a.layout} ${!inspectorOpen ? a.fullWidth : ''}`}
        aria-label="真实结果审核工作台"
      >
        <div className={a.main}>
          <div className={a.pageHeading}>
            <h1>结果审核</h1>
            <Space wrap>
              <Button
                aria-label="刷新结果"
                loading={listLoading}
                onClick={() => void loadSummaries()}
              >
                刷新结果
              </Button>
              {cursor && (
                <Button
                  loading={listLoading}
                  onClick={() => void loadSummaries(cursor)}
                >
                  加载更多提交
                </Button>
              )}
              <Button type="link" onClick={() => setActiveTab('sources')}>
                提交记录
              </Button>
              <Button type="link" onClick={() => setActiveTab('ai')}>
                AI进度与分档
              </Button>
            </Space>
          </div>
          <div className={a.batch}>
            <Space wrap>
              <strong>人工提交</strong>
              <Tag color={submission ? 'green' : 'default'}>
                {submission ? '已交回' : listLoading ? '读取中' : '暂无提交'}
              </Tag>
            </Space>
            <div className={a.batchMeta}>
              <span>项目：{project.name}</span>
              <span>模型：不适用</span>
              <span>
                提交时间：
                {submission
                  ? new Date(submission.submittedAt).toLocaleString('zh-CN')
                  : '—'}
              </span>
            </div>
            <Select
              aria-label="选择已交回的人工提交"
              placeholder="选择人工提交"
              style={{ width: 230, maxWidth: '100%' }}
              value={submissionId || undefined}
              loading={listLoading}
              disabled={listLoading}
              onChange={setSubmissionId}
              options={summaries.map((item) => ({
                value: item.submissionId,
                label: `${item.submittedBy} · ${item.submissionId}`,
              }))}
            />
          </div>
          {(listError || error) && (
            <Alert
              style={{ marginBottom: 16 }}
              type="error"
              showIcon
              message={listError || error}
              action={
                error ? (
                  <Button onClick={() => setRetry((n) => n + 1)}>
                    重试提交详情
                  </Button>
                ) : undefined
              }
            />
          )}
          <section className={a.stats} aria-label="当前人工提交筛选统计">
            {[
              {
                name: '样本',
                value: ready ? filtered.length : '—',
                icon: <UnorderedListOutlined />,
              },
              {
                name: '人工候选',
                value: ready
                  ? filtered.filter((item) => Boolean(item.result.labelId))
                      .length
                  : '—',
                icon: <RobotOutlined />,
              },
              {
                name: '未作答',
                value: ready
                  ? filtered.filter((item) => !item.result.labelId).length
                  : '—',
                icon: <CloseCircleOutlined />,
              },
              { name: '已确认', value: '—', icon: <CheckSquareOutlined /> },
            ].map((stat) => (
              <div key={stat.name}>
                {stat.icon}
                <div>
                  <span data-stat-label>{stat.name}</span>
                  <strong>{stat.value}</strong>
                </div>
              </div>
            ))}
          </section>
          <section aria-label="样本列表">
            <div className={a.filters}>
              <span>审核范围</span>
              <fieldset className={a.scopes} aria-label="候选范围">
                <Button
                  size="small"
                  type={!answer ? 'primary' : 'text'}
                  onClick={() => changeFilter('answer')}
                >
                  全部 ({candidates.length})
                </Button>
                <Button
                  size="small"
                  type={answer === 'true' ? 'primary' : 'text'}
                  onClick={() => changeFilter('answer', 'true')}
                >
                  有候选 ({hasAnswer})
                </Button>
                <Button
                  size="small"
                  type={answer === 'false' ? 'primary' : 'text'}
                  onClick={() => changeFilter('answer', 'false')}
                >
                  未作答 ({candidates.length - hasAnswer})
                </Button>
                <Button size="small" type="text" disabled>
                  已确认 · 待接入
                </Button>
              </fieldset>
              <div className={a.filterField}>
                结果来源
                <Select
                  aria-label="结果来源筛选"
                  value={source}
                  allowClear
                  placeholder="全部"
                  onChange={(value) => changeFilter('source', value)}
                  options={[
                    { value: 'HUMAN', label: '人工' },
                    { value: 'AI', label: 'AI（待接入）' },
                  ]}
                />
              </div>
              <div className={a.filterField}>
                人工审核
                <Select
                  aria-label="审核状态筛选"
                  disabled
                  placeholder="待接入"
                />
              </div>
              <div className={a.filterField}>
                置信度
                <InputNumber
                  style={{ width: 56 }}
                  disabled
                  placeholder="L"
                  aria-label="置信度下界"
                />
                －
                <InputNumber
                  style={{ width: 56 }}
                  disabled
                  placeholder="H"
                  aria-label="置信度上界"
                />
                <Button
                  icon={<SettingOutlined />}
                  aria-label="设置阈值"
                  disabled
                >
                  设置阈值
                </Button>
              </div>
              <Button
                size="small"
                onClick={() => {
                  setSource(undefined);
                  changeFilter('answer');
                }}
              >
                清除筛选
              </Button>
            </div>
            <div className={a.tableNote}>
              <span>
                共 {ready ? filtered.length : '—'} 条样本（已选 {chosen.length}{' '}
                条） · 已加载 {rows.length} 行
              </span>
              <span>
                已加载 {summaries.length} 份提交{cursor ? ' · 仍有后续页' : ''}
              </span>
              {!inspectorOpen && (
                <Button size="small" onClick={() => setInspectorOpen(true)}>
                  显示样本详情
                </Button>
              )}
            </div>
            <Table<Item>
              className={a.table}
              rowKey="sampleId"
              dataSource={rows}
              loading={loading || listLoading}
              pagination={false}
              size="small"
              scroll={{ x: 740, y: 'calc(100vh - 570px)' }}
              rowSelection={{
                selectedRowKeys: chosen,
                onChange: setChosen,
                columnWidth: 38,
              }}
              rowClassName={(item) =>
                item.sampleId === selectedId ? a.selected : ''
              }
              onRow={(item) => ({
                tabIndex: 0,
                onClick: (event) => {
                  if (
                    !(event.target as HTMLElement).closest(
                      'a,button,input,.ant-checkbox-wrapper',
                    )
                  )
                    chooseSample(item.sampleId);
                },
                onKeyDown: (event) => {
                  if (
                    event.target === event.currentTarget &&
                    ['Enter', ' '].includes(event.key)
                  ) {
                    event.preventDefault();
                    chooseSample(item.sampleId);
                  }
                },
              })}
              columns={[
                {
                  title: '序号',
                  width: 54,
                  render: (_, item) =>
                    filtered.findIndex(
                      (row) => row.sampleId === item.sampleId,
                    ) + 1,
                },
                {
                  title: '缩略图',
                  width: 86,
                  render: (_, item) => (
                    <OriginalImage
                      key={`${project.projectId}:${project.status}:${item.sampleId}`}
                      project={project}
                      sampleId={item.sampleId}
                      thumbnail
                    />
                  ),
                },
                {
                  title: '样本编号',
                  width: 170,
                  render: (_, item) => (
                    <Typography.Text
                      ellipsis={{ tooltip: item.sampleId }}
                      className={a.sampleId}
                    >
                      {item.sampleId}
                    </Typography.Text>
                  ),
                },
                {
                  title: '候选标签（人工）',
                  width: 136,
                  render: (_, item) => (
                    <Tag
                      color={
                        label(item).includes('正常')
                          ? 'green'
                          : label(item).includes('缺陷')
                            ? 'red'
                            : 'purple'
                      }
                    >
                      {label(item)}
                    </Tag>
                  ),
                },
                { title: 'AI置信度', width: 96, render: () => '不适用' },
                {
                  title: '审核状态（人工）',
                  width: 136,
                  render: () => <Tag color="purple">待接入</Tag>,
                },
                {
                  title: '操作',
                  width: 90,
                  render: (_, item) => (
                    <Space>
                      <Button
                        size="small"
                        type="text"
                        icon={<EyeOutlined />}
                        aria-label={`查看样本 ${item.sampleId}`}
                        onClick={() => chooseSample(item.sampleId)}
                      />
                      <Link
                        aria-label={`查看 ${item.sampleId} 的只读提交`}
                        to={`/labeling/submissions/${encodeURIComponent(submissionId)}`}
                      >
                        <FileTextOutlined />
                      </Link>
                    </Space>
                  ),
                },
              ]}
              locale={{
                emptyText:
                  loading || listLoading
                    ? '正在读取真实提交'
                    : error || listError
                      ? '读取失败，请重试'
                      : submission
                        ? '当前筛选没有样本'
                        : '尚无已交回人工提交',
              }}
            />
            <div className={a.bulk}>
              <Checkbox
                checked={rows.length > 0 && chosen.length === rows.length}
                indeterminate={chosen.length > 0 && chosen.length < rows.length}
                disabled={!rows.length}
                onChange={(event) =>
                  setChosen(
                    event.target.checked
                      ? rows.map((item) => item.sampleId)
                      : [],
                  )
                }
              >
                已选 {chosen.length} / {rows.length} 条已加载样本
              </Checkbox>
              <div className={a.bulkButtons}>
                <span>批量操作：</span>
                <Button icon={<EditOutlined />} disabled>
                  标记待修正
                </Button>
                <Button type="primary" icon={<CheckOutlined />} disabled>
                  确认所选
                </Button>
                {rows.length < filtered.length && (
                  <Button onClick={() => setLoadedLimit((n) => n + 20)}>
                    加载更多样本
                  </Button>
                )}
              </div>
            </div>
            <p className={a.subtle}>
              统计仅限当前人工提交及筛选范围。审核状态、全项目统计与AI阈值待后端接入，未读取的已确认数量显示“—”。
            </p>
          </section>
        </div>
        {inspectorOpen && (
          <aside className={a.inspector} aria-label="样本详情">
            <div className={a.inspectorHeading}>
              <h2>样本详情</h2>
              <Button
                type="text"
                aria-label="关闭样本详情"
                icon={<CloseOutlined />}
                onClick={() => setInspectorOpen(false)}
              />
            </div>
            <div className={a.inspectorBody}>
              <section aria-label="原图预览">
                <OriginalImage
                  key={`${project.projectId}:${project.status}:${selected?.sampleId || ''}`}
                  project={project}
                  sampleId={selected?.sampleId}
                  onSize={(sampleId, width, height) =>
                    setImageSize({ sampleId, width, height })
                  }
                />
                <div className={a.imageNav}>
                  <Button
                    icon={<LeftOutlined aria-hidden="true" />}
                    disabled={index <= 0}
                    onClick={() => chooseSample(rows[index - 1].sampleId)}
                  >
                    上一张
                  </Button>
                  <span>
                    {selected ? `${index + 1} / ${rows.length}` : '未选择样本'}
                  </span>
                  <Button
                    disabled={index < 0 || index >= rows.length - 1}
                    onClick={() => chooseSample(rows[index + 1].sampleId)}
                  >
                    下一张 <RightOutlined aria-hidden="true" />
                  </Button>
                </div>
              </section>
              <section aria-label="审核详情">
                {selected && submission ? (
                  <>
                    <div className={a.section}>
                      <h3>
                        <RobotOutlined />
                        候选结果（人工交回）
                      </h3>
                      <dl className={a.details}>
                        <div>
                          <dt>候选标签</dt>
                          <dd>
                            <Tag
                              color={
                                label(selected).includes('缺陷')
                                  ? 'red'
                                  : 'green'
                              }
                            >
                              {label(selected)}
                            </Tag>
                          </dd>
                        </div>
                        <div>
                          <dt>AI置信度</dt>
                          <dd>不适用</dd>
                        </div>
                        <div>
                          <dt>模型版本</dt>
                          <dd>不适用</dd>
                        </div>
                      </dl>
                    </div>
                    <div className={a.section}>
                      <h3>
                        <UserOutlined />
                        人工审核
                      </h3>
                      <dl className={a.details}>
                        <div>
                          <dt>审核状态</dt>
                          <dd>
                            <Tag color="purple">待后端接入</Tag>
                          </dd>
                        </div>
                        <div>
                          <dt>人工标签</dt>
                          <dd>
                            <Select
                              aria-label="人工标签"
                              style={{ width: '100%' }}
                              disabled
                              value={selected.result.labelId}
                              placeholder="—"
                              options={(
                                spec?.labels ||
                                (selected.result.labelId
                                  ? [
                                      {
                                        id: selected.result.labelId,
                                        name: selected.result.labelId,
                                      },
                                    ]
                                  : [])
                              ).map((item) => ({
                                value: item.id,
                                label: item.name,
                              }))}
                            />
                          </dd>
                        </div>
                      </dl>
                    </div>
                    <div className={a.section}>
                      <h3>
                        <FileTextOutlined />
                        样本信息
                      </h3>
                      <dl className={a.details}>
                        {[
                          ['文件名', '当前提交未提供'],
                          ['样本 ID', selectedId],
                          ['提交版本', submission.submissionId],
                          [
                            '结果版本',
                            `人工草稿 revision ${selected.revision}`,
                          ],
                          [
                            '提交时间',
                            new Date(submission.submittedAt).toLocaleString(
                              'zh-CN',
                            ),
                          ],
                          ['图像尺寸', currentSize],
                          [
                            '冻结规范',
                            String(spec?.versionNo ?? submission.specVersionId),
                          ],
                        ].map(([name, value]) => (
                          <div key={name}>
                            <dt>{name}</dt>
                            <dd>
                              {name === '提交版本' ? (
                                <Link
                                  to={`/labeling/submissions/${encodeURIComponent(submission.submissionId)}`}
                                >
                                  {value}
                                </Link>
                              ) : (
                                value
                              )}
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </div>
                  </>
                ) : (
                  <div className={a.emptyDetail}>
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="选择样本后查看真实候选"
                    />
                  </div>
                )}
                <Button
                  type="link"
                  className={a.history}
                  disabled={!summaries.length}
                  onClick={() => setHistoryOpen(true)}
                >
                  查看来源历史
                </Button>
                <label className={a.reason} htmlFor={reasonId}>
                  <span>操作原因</span>
                  <Input.TextArea
                    id={reasonId}
                    aria-label="操作原因"
                    rows={2}
                    disabled
                    placeholder="审核与修订待后端接入"
                  />
                </label>
                <Space wrap>
                  <Button type="primary" disabled>
                    确认采用
                  </Button>
                  <Button disabled>标记待修正</Button>
                  <Button type="link" disabled>
                    修订为新候选
                  </Button>
                </Space>
                <p className={a.subtle}>
                  人工交回不等于需求方确认；当前不写入审核状态。
                </p>
              </section>
            </div>
          </aside>
        )}
      </section>
      <Drawer
        title="来源历史 · 已加载人工提交"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        width={600}
      >
        <Alert
          type="info"
          message="仅展示已加载的人工提交。完整跨来源候选与审核历史待后端接入，选择提交只切换预览。"
        />
        <Table<SubmissionSummary>
          rowKey="submissionId"
          dataSource={summaries}
          pagination={false}
          scroll={{ x: 450 }}
          columns={[
            {
              title: '提交',
              dataIndex: 'submissionId',
              render: (id: string) => (
                <Link to={`/labeling/submissions/${encodeURIComponent(id)}`}>
                  {id}
                </Link>
              ),
            },
            { title: '标注人', dataIndex: 'submittedBy' },
            {
              title: '操作',
              render: (_, item) => (
                <Button
                  onClick={() => {
                    setSubmissionId(item.submissionId);
                    setHistoryOpen(false);
                  }}
                >
                  查看该提交
                </Button>
              ),
            },
          ]}
        />
        {cursor && (
          <Button
            loading={listLoading}
            onClick={() => void loadSummaries(cursor)}
          >
            加载更多提交
          </Button>
        )}
      </Drawer>
    </>
  );
}
