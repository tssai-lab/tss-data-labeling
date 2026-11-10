import { Link } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { strToU8, zipSync } from 'fflate';
import { useEffect, useMemo, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import {
  type Agent,
  type Execution,
  type ExportJob,
  type FinalSnapshot,
  type ResultRow,
  type Selection,
  type SelectionItem,
  type WorkflowCall,
  workflowClient,
} from '@/services/labelingWorkflow';
import {
  confidenceBand,
  createWorkflowMock,
} from '@/services/labelingWorkflowMock.mjs';
import styles from './index.less';

const enabled =
  process.env.NODE_ENV !== 'production' &&
  process.env.LABELING_GAP_MOCK === 'true';
const mock = createWorkflowMock({
  enabled,
  production: process.env.NODE_ENV === 'production',
});
type DemoState = {
  project: { status: string; revision: number };
  packages: {
    packageId: string;
    roundId: string;
    sampleCount: number;
    status: string;
  }[];
  audit: { operation: string; actorId: string; createdAt: string }[];
};
const specs = 'demo-spec-v1';
const labels = [
  { id: 'normal', name: '正常' },
  { id: 'defect', name: '缺陷' },
  { id: 'none', name: '无目标' },
];
export default function WorkflowDemo() {
  const { modal } = App.useApp();
  const [actor, setActor] = useState('demo-owner');
  const actorRef = useRef(actor);
  actorRef.current = actor;
  const call = useMemo<WorkflowCall>(
    () =>
      async <T,>(
        method: 'GET' | 'POST',
        path: string,
        body?: unknown,
        key?: string,
      ) =>
        mock.request(method, `/api/labeling/v1${path}`, {
          actor: actorRef.current,
          body,
          key,
        }).data as T,
    [],
  );
  const api = useMemo(() => workflowClient(call, 'demo-project'), [call]);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [stats, setStats] = useState<Record<string, number>>({});
  const [filter, setFilter] = useState<Record<string, string>>({});
  const [chosen, setChosen] = useState<React.Key[]>([]);
  const [purpose, setPurpose] = useState('REVIEW');
  const [reviewAction, setReviewAction] = useState('ACCEPT');
  const [excluded, setExcluded] = useState('');
  const [selection, setSelection] = useState<Selection>();
  const [preview, setPreview] = useState<SelectionItem[]>([]);
  const [previewCursor, setPreviewCursor] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentId, setAgentId] = useState('demo-agent');
  const [execution, setExecution] = useState<Execution>();
  const [knownExecutions, setKnownExecutions] = useState<string[]>([]);
  useEffect(() => {
    if (execution)
      setKnownExecutions((old) => [
        ...new Set([...old, execution.executionId]),
      ]);
  }, [execution?.executionId]);
  const [executionRows, setExecutionRows] = useState<ResultRow[]>([]);
  const [lower, setLower] = useState<number | null>(null);
  const [upper, setUpper] = useState<number | null>(null);
  const [policyId, setPolicyId] = useState('');
  const [policyThreshold, setPolicyThreshold] = useState<{
    lower: number;
    upper: number;
  }>();
  const [referenceMode, setReferenceMode] = useState('NONE');
  const [reopen, setReopen] = useState(false);
  const [packageSize, setPackageSize] = useState<number | null>(10);
  const [instructions, setInstructions] = useState('按固定规范处理所选范围');
  const [reason, setReason] = useState('');
  const [plan, setPlan] = useState<{
    planId: string;
    selectionHash: string;
    sampleCount: number;
    packageSampleCounts: number[];
    acceptedSampleCount: number;
  }>();
  const [finals, setFinals] = useState<FinalSnapshot[]>([]);
  const [selectedFinals, setSelectedFinals] = useState<React.Key[]>([]);
  const [includeOriginals, setIncludeOriginals] = useState(false);
  const [job, setJob] = useState<ExportJob>();
  const [history, setHistory] = useState<ResultRow[]>([]);
  const [historySample, setHistorySample] = useState<ResultRow>();
  const [voteIds, setVoteIds] = useState<React.Key[]>([]);
  const [aggregation, setAggregation] = useState<{
    aggregationId: string;
    items: {
      sampleId: string;
      suggestedResultId: string | null;
      status: string;
      voteCounts: Record<string, number>;
      validVoteCount: number;
      voteSupport: number | null;
    }[];
  }>();
  const [edit, setEdit] = useState<ResultRow>();
  const [editLabel, setEditLabel] = useState<string>();
  const [state, setState] = useState<DemoState>();
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const version = useRef(0);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [requestKey, setRequestKey] = useState<{
    operation: string;
    key: string;
  }>();

  async function refresh(query = filter) {
    const current = ++version.current;
    const [page, statistics, list, agentList] = await Promise.all([
      api.results(query),
      api.statistics(query),
      api.finals(),
      api.agents(),
    ]);
    if (current !== version.current) return;
    setRows(page.items);
    setCursor(page.nextCursor);
    setStats(statistics);
    setFinals(list.items);
    setAgents(agentList);
    setState(mock.snapshot() as DemoState);
  }
  useEffect(() => {
    setExecution(undefined);
    setExecutionRows([]);
    setPolicyId('');
    setState(undefined);
    setFinals([]);
    setSelectedFinals([]);
    setJob(undefined);
    setAgents([]);
    setStats({});
    setPlan(undefined);
  }, [actor]);
  useEffect(() => {
    setRows([]);
    setChosen([]);
    setSelection(undefined);
    setPreview([]);
    setHistory([]);
    setAggregation(undefined);
    setError('');
    if (enabled)
      void refresh().catch((e) => setError(describeLabelingError(e)));
    return () => {
      ++version.current;
    };
  }, [actor, JSON.stringify(filter)]);
  async function act(
    operation: string,
    action: (key: string) => Promise<void>,
    reload = true,
  ) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    const key =
      requestKey?.operation === operation ? requestKey.key : operationKey();
    setRequestKey({ operation, key });
    try {
      await action(key);
      if (reload) await refresh();
      setRequestKey(undefined);
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  function updateFilter(key: string, value?: string) {
    setFilter((old) => {
      const next = { ...old };
      if (value) next[key] = value;
      else delete next[key];
      return next;
    });
  }
  async function choose(all: boolean, key: string) {
    const body = all
      ? { filter, excludeSampleIds: excluded.split(/\s+/).filter(Boolean) }
      : {
          items: rows
            .filter((r) => chosen.includes(r.sampleId))
            .map((r) => ({
              sampleId: r.sampleId,
              resultId: r.resultId,
              reviewRevision: r.reviewRevision,
            })),
        };
    const frozen = await api.select(
      {
        purpose,
        ...(purpose === 'REVIEW' ? { reviewAction } : {}),
        specVersionId: specs,
        ...body,
      },
      key,
    );
    const page = await api.selectionItems(frozen.selectionId);
    const fixedStats = await api.selectionStatistics(frozen.selectionId);
    if (fixedStats.total !== frozen.count)
      throw new Error('选择清单与固定统计不一致，请重新预览');
    setSelection(frozen);
    setPreview(page.items);
    setPreviewCursor(page.nextCursor);
    setPreviewOpen(true);
    setNotice(
      `已固定 ${frozen.count} 个样本，用途 ${frozen.purpose}。后续新增数据不扩大本次范围。`,
    );
  }
  function confirmReview(action: 'ACCEPT' | 'NEEDS_REVISION') {
    if (selection?.purpose !== 'REVIEW' || selection.reviewAction !== action) {
      setError('请先创建并预览 REVIEW 选择快照');
      return;
    }
    if (action === 'NEEDS_REVISION' && !reason.trim()) {
      setError('标记待修正须填写原因');
      return;
    }
    modal.confirm({
      title: `${action === 'ACCEPT' ? '确认采用' : '标记待修正'}这 ${selection.count} 项？`,
      content: `固定选择 ${selection.selectionId}。阈值本身不会审核，确认后才写审核记录。`,
      okText: '确认操作',
      cancelText: '取消',
      onOk: () =>
        act(
          JSON.stringify({ action, selection, reason, filter }),
          async (key) => {
            const result = await api.review(
              {
                action,
                selectionId: selection.selectionId,
                confidencePolicyId: filter.confidencePolicyId || null,
                reason,
              },
              key,
            );
            setNotice(
              `已明确审核 ${result.affectedCount} 项；尚未形成最终成果。`,
            );
            setSelection(undefined);
          },
        ),
    });
  }
  async function loadExecution(id: string) {
    const e = await api.execution(id);
    const output = await api.executionResults(id);
    setExecution(e);
    setExecutionRows(output.items);
  }
  function publishBody() {
    if (selection?.purpose !== 'PUBLISH')
      throw new Error('先创建并预览 PUBLISH 选择快照');
    return {
      selectionId: selection.selectionId,
      specVersionId: specs,
      referenceMode,
      instructions,
      reopenAccepted: reopen,
    };
  }
  async function download() {
    if (!job) return;
    const files = await call<Record<string, string>>(
      'GET',
      `/exports/${encodeURIComponent(job.exportId)}/download`,
    );
    const bytes = zipSync(
      Object.fromEntries(
        Object.entries(files).map(([name, content]) => [
          name,
          strToU8(content),
        ]),
      ),
    );
    const url = URL.createObjectURL(
      new Blob([new Uint8Array(bytes)], { type: 'application/zip' }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = `图片分类合成项目-${job.exportId}.zip`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <header className={styles.workspaceHeader}>
          <div className={styles.brand}>
            TSS <span>/</span> <small>LOCAL WORKFLOW DEMO</small>
          </div>
        </header>
        <nav className={styles.navigation}>
          {enabled && (
            <Link className={styles.navTab} to="/labeling/demo/energy">
              能耗模板与质检合成试标
            </Link>
          )}
          <Link className={styles.navTab} to="/labeling">
            真实标注平台
          </Link>
          <Link className={styles.navTab} to="/platforms">
            切换平台
          </Link>
        </nav>
        <h1>需求方工作台 · 合成流程演示</h1>
        <Alert
          type="warning"
          showIcon
          message="全部为合成数据，不运行模型，不连接真实项目或账号"
          description="人工交回、AI批次结束、需求方确认、成果完成是不同状态。新版接口尚未在当前backend实现；这里仅验证前端契约和交互，不能作为真实AI验收。"
        />
        {enabled && (
          <Space wrap style={{ marginBottom: 16 }} aria-label="工作台步骤导航">
            {[
              '结果与审核',
              '范围发布',
              'AI进度与分档',
              '来源与集成',
              '成果导出',
            ].map((label, i) => (
              <Button
                key={label}
                onClick={() =>
                  document
                    .getElementById(`workflow-step-${i + 1}`)
                    ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                }
              >
                {i + 1}. {label}
              </Button>
            ))}
          </Space>
        )}
        {!enabled ? (
          <Alert message="开发演示默认关闭，生产构建不可开启。待后端接入。" />
        ) : (
          <>
            <Card>
              <Space wrap>
                <Select
                  aria-label="合成身份"
                  value={actor}
                  onChange={setActor}
                  options={['demo-owner', 'demo-admin', 'demo-outsider'].map(
                    (value) => ({ value, label: value }),
                  )}
                />
                <Button
                  disabled={busy}
                  onClick={() => {
                    mock.reset();
                    setExcluded('');
                    setPurpose('REVIEW');
                    setReviewAction('ACCEPT');
                    setReferenceMode('NONE');
                    setReopen(false);
                    setPackageSize(10);
                    setInstructions('按固定规范处理所选范围');
                    setReason('');
                    setAgentId('demo-agent');
                    setChosen([]);
                    setHistory([]);
                    setHistorySample(undefined);
                    setVoteIds([]);
                    setAggregation(undefined);
                    setRows([]);
                    setFinals([]);
                    setState(undefined);
                    setIncludeOriginals(false);
                    setPolicyThreshold(undefined);
                    setEdit(undefined);
                    setPreviewOpen(false);
                    setPreview([]);
                    setPreviewCursor(null);
                    setError('');
                    setNotice('已重置全部合成状态');
                    setKnownExecutions([]);
                    setFilter({});
                    setExecution(undefined);
                    setExecutionRows([]);
                    setSelection(undefined);
                    setPolicyId('');
                    setLower(null);
                    setUpper(null);
                    setPlan(undefined);
                    setJob(undefined);
                    setSelectedFinals([]);
                    setRequestKey(undefined);
                    void refresh({}).catch((e) =>
                      setError(describeLabelingError(e)),
                    );
                  }}
                >
                  重置合成种子
                </Button>
                <Button
                  loading={busy}
                  onClick={() => void act('refresh', async () => {})}
                >
                  刷新当前范围
                </Button>
              </Space>
            </Card>
            {error && <Alert type="error" showIcon message={error} />}
            {notice && <Alert type="info" showIcon message={notice} />}
            <Card id="workflow-step-1" title="1. 结果、范围与审核">
              <Typography.Paragraph>
                12个合成样本；初始AI批次为6成功/2失败，另有2项无答案及2份人工候选。所有候选初始待审核；表中展示当前选定结果，新候选从来源历史明确采用，不按时间猜答案。AI结果允许没有人工包或领取。统计仅为当前演示筛选范围。
              </Typography.Paragraph>
              <Space wrap>
                <Select
                  aria-label="来源筛选"
                  allowClear
                  placeholder="结果来源"
                  style={{ width: 160 }}
                  value={filter.sourceKind}
                  onChange={(v) => updateFilter('sourceKind', v)}
                  options={['AI', 'HUMAN', 'OWNER', 'AGGREGATION'].map(
                    (value) => ({ value, label: value }),
                  )}
                />
                <Select
                  aria-label="审核状态筛选"
                  allowClear
                  placeholder="审核状态"
                  style={{ width: 160 }}
                  value={filter.reviewStatus}
                  onChange={(v) => updateFilter('reviewStatus', v)}
                  options={['PENDING', 'NEEDS_REVISION', 'ACCEPTED'].map(
                    (value) => ({ value, label: value }),
                  )}
                />
                <Select
                  aria-label="答案筛选"
                  allowClear
                  placeholder="是否有答案"
                  style={{ width: 150 }}
                  value={filter.hasResult}
                  onChange={(v) => updateFilter('hasResult', v)}
                  options={[
                    { value: 'true', label: '有候选答案' },
                    { value: 'false', label: '没有答案' },
                  ]}
                />
                <Button onClick={() => setFilter({})}>清除筛选</Button>
              </Space>
              <Space wrap style={{ marginTop: 12 }}>
                {Object.entries(filter).map(([key, value]) => (
                  <Tag key={key}>
                    {key}: {value}
                  </Tag>
                ))}
              </Space>
              <Space wrap style={{ margin: '16px 0' }}>
                <Tag>范围 {stats.total ?? 0}</Tag>
                <Tag>已确认 {stats.acceptedCount ?? 0}</Tag>
                <Tag>待修正 {stats.needsRevisionCount ?? 0}</Tag>
                <Tag>没有答案 {stats.noResultCount ?? 0}</Tag>
                <Tag>活动处理 {stats.activeCount ?? 0}</Tag>
              </Space>
              <Table<ResultRow>
                rowKey="sampleId"
                size="small"
                pagination={false}
                dataSource={rows}
                loading={busy}
                scroll={{ x: 1550 }}
                rowSelection={{
                  selectedRowKeys: chosen,
                  onChange: setChosen,
                  getCheckboxProps: (r) => ({ disabled: r.activeProcessing }),
                }}
                columns={[
                  {
                    title: '样本 / 原路径',
                    width: 210,
                    dataIndex: 'sampleId',
                    render: (_, r) => (
                      <Typography.Paragraph
                        ellipsis={{ rows: 2, tooltip: r.sourcePath }}
                      >
                        {r.sampleId}
                        <br />
                        {r.sourcePath}
                      </Typography.Paragraph>
                    ),
                  },
                  {
                    title: '标签',
                    render: (_, r) =>
                      labels.find((l) => l.id === r.result?.labelId)?.name ||
                      '没有答案',
                  },
                  {
                    title: '来源 / 模型',
                    render: (_, r) =>
                      `${r.sourceKind || '—'} / ${r.modelVersion || '—'}`,
                  },
                  {
                    title: '执行 / 包',
                    render: (_, r) =>
                      r.executionId || r.packageId || '无人工包',
                  },
                  {
                    title: '置信度',
                    render: (_, r) =>
                      r.hasResult
                        ? (r.confidence ?? '未知')
                        : '失败/未完成，不计入未知组',
                  },
                  {
                    title: '审核 / revision',
                    render: (_, r) => `${r.reviewStatus} / ${r.reviewRevision}`,
                  },
                  {
                    title: '结果版本',
                    dataIndex: 'resultId',
                    ellipsis: true,
                    render: (id, row) =>
                      row.requiresResultSelection
                        ? '多份候选，须从来源历史明确选择'
                        : id || '无候选答案',
                  },
                  {
                    title: '活动 / 失败',
                    render: (_, r) =>
                      r.activeProcessing ? '处理中，禁选' : r.errorCode || '—',
                  },
                  {
                    title: '操作',
                    width: 280,
                    render: (_, r) => (
                      <Space wrap>
                        <Button
                          disabled={busy}
                          onClick={() =>
                            void act(`history:${r.sampleId}`, async () => {
                              const result = await api.history(r.sampleId);
                              setHistory(result.items);
                              setHistorySample(r);
                              setVoteIds([]);
                            })
                          }
                        >
                          来源历史
                        </Button>
                        <Button
                          disabled={
                            busy ||
                            !r.resultId ||
                            r.activeProcessing ||
                            !r.reviewable
                          }
                          onClick={() => {
                            setEdit(r);
                            setEditLabel(r.result?.labelId);
                          }}
                        >
                          修订为新候选
                        </Button>
                        <Button
                          disabled={busy || r.activeProcessing}
                          onClick={() => {
                            if (!reason.trim()) {
                              setError('请先填写待修正原因');
                              return;
                            }
                            modal.confirm({
                              title: '明确标记该样本待修正？',
                              onOk: () =>
                                act(
                                  `mark:${r.sampleId}:${r.reviewRevision}:${reason}`,
                                  async (key) => {
                                    await api.review(
                                      {
                                        action: 'NEEDS_REVISION',
                                        items: [
                                          {
                                            sampleId: r.sampleId,
                                            resultId: r.resultId,
                                            baseReviewRevision:
                                              r.reviewRevision,
                                          },
                                        ],
                                        reason,
                                      },
                                      key,
                                    );
                                  },
                                ),
                            });
                          }}
                        >
                          待修正
                        </Button>
                      </Space>
                    ),
                  },
                ]}
              />
              {cursor && (
                <Button
                  loading={busy}
                  onClick={() =>
                    void act(
                      `page:${cursor}`,
                      async () => {
                        const page = await api.results({ ...filter, cursor });
                        setRows((old) => [...old, ...page.items]);
                        setCursor(page.nextCursor);
                      },
                      false,
                    )
                  }
                >
                  加载后续结果
                </Button>
              )}
              <Space wrap style={{ marginTop: 16 }}>
                <Select
                  aria-label="固定选择用途"
                  value={purpose}
                  onChange={(v) => {
                    setPurpose(v);
                    setSelection(undefined);
                  }}
                  options={['REVIEW', 'PUBLISH', 'FINALIZE'].map((value) => ({
                    value,
                    label: value,
                  }))}
                />
                {purpose === 'REVIEW' && (
                  <Select
                    aria-label="快照审核动作"
                    value={reviewAction}
                    options={[
                      { value: 'ACCEPT', label: '确认结果' },
                      {
                        value: 'NEEDS_REVISION',
                        label: '标记待修正（允许无答案）',
                      },
                    ]}
                    onChange={(value) => {
                      setReviewAction(value);
                      setSelection(undefined);
                    }}
                  />
                )}
                <Button
                  disabled={busy || !chosen.length}
                  onClick={() =>
                    void act(
                      `select:${purpose}:${chosen.join(',')}:${JSON.stringify(rows)}`,
                      (key) => choose(false, key),
                    )
                  }
                >
                  固定勾选样本并预览
                </Button>
                <Button
                  disabled={busy}
                  onClick={() =>
                    void act(
                      `select-all:${purpose}:${JSON.stringify(filter)}:${excluded}`,
                      (key) => choose(true, key),
                    )
                  }
                >
                  固定全部筛选结果并预览
                </Button>
              </Space>
              <Input.TextArea
                aria-label="全部筛选结果排除样本"
                rows={2}
                value={excluded}
                onChange={(e) => setExcluded(e.target.value)}
                placeholder="选择全部筛选结果时排除的 sampleId，每行一个；变更范围需重新生成快照"
                style={{ marginTop: 16 }}
              />
              {selection && (
                <Descriptions
                  column={1}
                  style={{ marginTop: 16 }}
                  items={[
                    {
                      key: 'selection',
                      label: '固定选择',
                      children: `${selection.selectionId} · ${selection.purpose} · ${selection.count} 项`,
                    },
                    {
                      key: 'expiry',
                      label: '有效期',
                      children: selection.expiresAt,
                    },
                  ]}
                />
              )}
              <Input.TextArea
                aria-label="操作原因"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
                placeholder="审核、修订、取消或管理操作原因（待修正必填）"
                rows={2}
                style={{ margin: '16px 0' }}
              />
              <Space wrap>
                <Button
                  type="primary"
                  disabled={
                    busy ||
                    selection?.purpose !== 'REVIEW' ||
                    selection.reviewAction !== 'ACCEPT'
                  }
                  onClick={() => confirmReview('ACCEPT')}
                >
                  明确确认所选结果
                </Button>
                <Button
                  disabled={
                    busy ||
                    selection?.purpose !== 'REVIEW' ||
                    selection.reviewAction !== 'NEEDS_REVISION'
                  }
                  onClick={() => confirmReview('NEEDS_REVISION')}
                >
                  明确标记所选待修正
                </Button>
              </Space>
            </Card>
            <Card id="workflow-step-2" title="2. 人工发布或 AI 执行">
              <Alert
                type="info"
                message="先生成 PUBLISH 选择快照。人工按包自行领取、逐包完整交回；AI直接按样本范围执行，无需人工包。"
              />
              <Space wrap style={{ marginTop: 16 }}>
                <Select
                  aria-label="参考答案模式"
                  value={referenceMode}
                  onChange={setReferenceMode}
                  options={[
                    { value: 'NONE', label: '不展示已有答案' },
                    { value: 'SELECTED', label: '携带固定参考结果' },
                  ]}
                />
                <Checkbox
                  checked={reopen}
                  onChange={(e) => setReopen(e.target.checked)}
                >
                  允许重新处理已确认样本（发布成功才使当前确认失效）
                </Checkbox>
              </Space>
              <Input.TextArea
                aria-label="处理要求"
                rows={2}
                value={instructions}
                onChange={(e) => setInstructions(e.target.value)}
                style={{ margin: '16px 0' }}
              />
              <Space wrap>
                <InputNumber
                  aria-label="每包图片数"
                  min={1}
                  max={1000}
                  value={packageSize}
                  onChange={setPackageSize}
                />
                <Tag>每包1人；不指定某个标注员</Tag>
                <Button
                  disabled={busy || selection?.purpose !== 'PUBLISH'}
                  onClick={() =>
                    void act(
                      `plan:${JSON.stringify({ selection, referenceMode, instructions, reopen, packageSize })}`,
                      async (key) => {
                        const p = await api.plan(
                          {
                            ...publishBody(),
                            packageSize,
                            annotatorsPerSample: 1,
                          },
                          key,
                        );
                        setPlan(p);
                      },
                    )
                  }
                >
                  预览人工打包
                </Button>
              </Space>
              {plan && (
                <div style={{ marginTop: 16 }}>
                  <Typography.Paragraph>
                    所选 {plan.sampleCount} 张，各包{' '}
                    {plan.packageSampleCounts.join(' / ')}；涉及已确认{' '}
                    {plan.acceptedSampleCount} 项。旧包成员和历史答案保留。
                  </Typography.Paragraph>
                  <Button
                    disabled={busy}
                    onClick={() =>
                      modal.confirm({
                        title: `确认发布 ${plan.packageSampleCounts.length} 个新人工包？`,
                        content: `影响已确认样本 ${plan.acceptedSampleCount} 项；只操作本次固定范围。`,
                        onOk: () =>
                          act(`publish:${plan.planId}`, async (key) => {
                            await api.publish(
                              plan.planId,
                              plan.selectionHash,
                              key,
                            );
                            setPlan(undefined);
                            setSelection(undefined);
                            setNotice('合成人工轮次已发布，未自动领取或审核');
                          }),
                      })
                    }
                  >
                    确认发布人工包
                  </Button>
                </div>
              )}
              <Table
                rowKey="packageId"
                dataSource={state?.packages || []}
                pagination={false}
                scroll={{ x: 800 }}
                columns={[
                  { title: '新人工包', dataIndex: 'packageId' },
                  { title: '轮次', dataIndex: 'roundId' },
                  { title: '图片数', dataIndex: 'sampleCount' },
                  { title: '状态', dataIndex: 'status' },
                  {
                    title: '合成测试控制',
                    render: (_, p) => (
                      <Button
                        disabled={busy || p.status !== 'OPEN'}
                        onClick={() =>
                          void act(`deliver:${p.packageId}`, async () => {
                            mock.finishHumanPackage(p.packageId);
                            setNotice(
                              '仅模拟这个包全部完成并交回；其他包不等待，本包仍待需求方审核',
                            );
                          })
                        }
                      >
                        模拟本包全部完成并交回
                      </Button>
                    ),
                  },
                ]}
              />
              <Space wrap style={{ marginTop: 16 }}>
                <Select
                  aria-label="选择Agent"
                  value={agentId}
                  onChange={setAgentId}
                  style={{ width: 300, maxWidth: '100%' }}
                  options={agents.map((a) => ({
                    value: a.agentId,
                    label: `${a.displayName} · ${a.modelVersion}`,
                    disabled: !a.available,
                  }))}
                />
                <Button
                  disabled={busy || selection?.purpose !== 'PUBLISH'}
                  onClick={() =>
                    void act(
                      `execute:${JSON.stringify({ selection, referenceMode, reopen, agentId, instructions })}`,
                      async (key) => {
                        const e = await api.execute(
                          { ...publishBody(), agentId },
                          key,
                        );
                        setExecution(e);
                        setExecutionRows([]);
                        setPolicyId('');
                        setNotice('合成AI执行已排队；这不是实际模型运行');
                      },
                    )
                  }
                >
                  创建合成 AI 执行
                </Button>
                <Button
                  disabled={busy}
                  onClick={() =>
                    void act('seed-execution', async () => {
                      await loadExecution('demo-execution-seed');
                      setPolicyId('');
                    })
                  }
                >
                  查看种子 PARTIAL 批次
                </Button>
              </Space>
              {agents.find((a) => a.agentId === agentId) && (
                <Typography.Paragraph type="secondary">
                  {
                    agents.find((a) => a.agentId === agentId)
                      ?.confidenceDescription
                  }
                  ；此 Agent 不支持参考模式。
                </Typography.Paragraph>
              )}
            </Card>
            <Card id="workflow-step-3" title="3. AI 进度、失败清单与手动分档">
              {!execution ? (
                <Typography.Text>
                  选择已有批次或创建合成执行后查看。
                </Typography.Text>
              ) : (
                <>
                  <Space wrap>
                    <Typography.Text copyable>
                      {execution.executionId}
                    </Typography.Text>
                    <Select
                      aria-label="切换本会话已知执行"
                      value={execution.executionId}
                      style={{ width: 250, maxWidth: '100%' }}
                      options={knownExecutions.map((value) => ({
                        value,
                        label: value,
                      }))}
                      onChange={(id) =>
                        void act(`known-execution:${id}`, async () => {
                          await loadExecution(id);
                          setPolicyId('');
                        })
                      }
                    />
                    <Tag>
                      {execution.status} · revision {execution.revision}
                    </Tag>
                    <Tag>成功 {execution.successCount}</Tag>
                    <Tag>失败 {execution.failedCount}</Tag>
                    <Tag>未完成 {execution.pendingCount}</Tag>
                    <Tag>模型 {execution.modelVersion}</Tag>
                    <Button
                      disabled={busy}
                      onClick={() =>
                        void act(`poll:${execution.executionId}`, async () =>
                          loadExecution(execution.executionId),
                        )
                      }
                    >
                      查询执行进度
                    </Button>
                    <Button
                      disabled={
                        busy ||
                        !['QUEUED', 'RUNNING'].includes(execution.status)
                      }
                      onClick={() =>
                        modal.confirm({
                          title: '确认取消当前执行？',
                          onOk: () =>
                            act(
                              `cancel:${execution.executionId}:${execution.revision}:${reason}`,
                              async (key) => {
                                const e = await api.cancel(
                                  execution.executionId,
                                  execution.revision,
                                  reason,
                                  key,
                                );
                                setExecution(e);
                              },
                            ),
                        })
                      }
                    >
                      取消执行
                    </Button>
                    <Button
                      disabled={
                        busy ||
                        !['PARTIAL', 'FAILED', 'CANCELLED'].includes(
                          execution.status,
                        )
                      }
                      onClick={() =>
                        void act(
                          `retry:${execution.executionId}:${execution.revision}`,
                          async (key) => {
                            const items = executionRows
                              .filter((r) => !r.hasResult)
                              .map((r) => ({
                                sampleId: r.sampleId,
                                resultId: null,
                                reviewRevision: r.reviewRevision,
                              }));
                            const s = await api.select(
                              {
                                purpose: 'PUBLISH',
                                specVersionId: specs,
                                items,
                              },
                              `${key}-selection`,
                            );
                            setExecution(
                              await api.retry(
                                execution.executionId,
                                {
                                  selectionId: s.selectionId,
                                  baseRevision: execution.revision,
                                },
                                key,
                              ),
                            );
                            setExecutionRows([]);
                            setPolicyId('');
                          },
                        )
                      }
                    >
                      仅重试该批次失败/取消项
                    </Button>
                  </Space>
                  <Alert
                    type="info"
                    style={{ marginTop: 16 }}
                    message={
                      execution.resultSnapshotId
                        ? `批次已交回，固定快照 ${execution.resultSnapshotId}；成功项可核查，尚未审核通过。`
                        : '运行中结果仅供进度查看，不可审核。'
                    }
                  />
                  <Table<ResultRow>
                    rowKey="sampleId"
                    scroll={{ x: 750 }}
                    pagination={{ pageSize: 10 }}
                    dataSource={executionRows}
                    columns={[
                      { title: '样本', dataIndex: 'sampleId' },
                      {
                        title: '候选',
                        render: (_, r) =>
                          r.hasResult ? r.result?.labelId : '没有答案',
                      },
                      {
                        title: '置信度',
                        render: (_, r) =>
                          r.hasResult
                            ? (r.confidence ?? '未知')
                            : '失败/未完成',
                      },
                      { title: '失败原因', dataIndex: 'errorCode' },
                      {
                        title: '可审核',
                        render: (_, r) =>
                          r.reviewable ? '待需求方确认' : '否',
                      },
                    ]}
                  />
                  <Space wrap>
                    <InputNumber
                      aria-label="低边界L"
                      min={0}
                      max={1}
                      step={0.01}
                      placeholder="手动输入 L"
                      value={lower}
                      onChange={setLower}
                    />
                    <InputNumber
                      aria-label="高边界H"
                      min={0}
                      max={1}
                      step={0.01}
                      placeholder="手动输入 H"
                      value={upper}
                      onChange={setUpper}
                    />
                    <Button
                      disabled={
                        busy ||
                        !execution.resultSnapshotId ||
                        lower === null ||
                        upper === null
                      }
                      onClick={() =>
                        void act(
                          `policy:${execution.executionId}:${lower}:${upper}`,
                          async (key) => {
                            if (lower === null || upper === null)
                              throw new Error('手动设置两个阈值');
                            const p = await api.policy(
                              execution.roundId,
                              {
                                executionId: execution.executionId,
                                resultSnapshotId: execution.resultSnapshotId,
                                lowerThreshold: lower,
                                upperThreshold: upper,
                              },
                              key,
                            );
                            setPolicyId(p.policyId);
                            setPolicyThreshold({
                              lower,
                              upper,
                            });
                            setNotice('阈值策略已保存，没有产生任何审核决定');
                          },
                        )
                      }
                    >
                      保存阈值策略（不审核）
                    </Button>
                  </Space>
                  {policyId && policyThreshold && (
                    <>
                      <Space wrap style={{ marginTop: 16 }}>
                        {['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].map((band) => (
                          <Button
                            key={band}
                            disabled={busy}
                            onClick={() =>
                              setFilter({
                                executionId: execution.executionId,
                                confidencePolicyId: policyId,
                                band,
                              })
                            }
                          >
                            {band} · 已载成功项{' '}
                            {
                              executionRows.filter(
                                (r) =>
                                  r.hasResult &&
                                  confidenceBand(
                                    r.confidence,
                                    policyThreshold.lower,
                                    policyThreshold.upper,
                                  ) === band,
                              ).length
                            }
                          </Button>
                        ))}
                      </Space>
                      <Typography.Paragraph type="secondary">
                        分档仅对应此执行/模型快照，页面计数限已加载成功项；失败项不算未知分数。选择分档后回到结果区固定
                        REVIEW 范围、排除并显式确认。
                      </Typography.Paragraph>
                    </>
                  )}
                </>
              )}
            </Card>
            <Card id="workflow-step-4" title="4. 多数投票建议与来源历史">
              <Typography.Paragraph>
                先在结果区打开某样本“来源历史”，选择固定版本的独立答案。严格过半才给建议；重复作者或派生来源拒绝。票数支持比例不是模型置信度。
              </Typography.Paragraph>
              <Table<ResultRow>
                rowKey="resultId"
                dataSource={history}
                scroll={{ x: 950 }}
                pagination={false}
                rowSelection={{
                  selectedRowKeys: voteIds,
                  onChange: setVoteIds,
                }}
                columns={[
                  { title: '结果版本', dataIndex: 'resultId' },
                  { title: '来源', dataIndex: 'sourceKind' },
                  { title: '来源身份', dataIndex: 'sourceActor' },
                  { title: '标签', render: (_, r) => r.result?.labelId },
                  {
                    title: '置信度',
                    render: (_, r) => r.confidence ?? '不适用/未知',
                  },
                  {
                    title: '需求方明确采用',
                    render: (_, r) => (
                      <Button
                        disabled={busy || !r.resultId || !r.reviewable}
                        onClick={() =>
                          modal.confirm({
                            title: '明确采用此固定候选版本？',
                            onOk: () =>
                              act(
                                `accept-version:${r.resultId}`,
                                async (key) => {
                                  const current = rows.find(
                                    (v) => v.sampleId === r.sampleId,
                                  );
                                  if (!current || current.activeProcessing)
                                    throw new Error(
                                      '先刷新当前样本或结束活动处理',
                                    );
                                  await api.review(
                                    {
                                      action: 'ACCEPT',
                                      items: [
                                        {
                                          sampleId: r.sampleId,
                                          resultId: r.resultId,
                                          baseReviewRevision:
                                            current.reviewRevision,
                                        },
                                      ],
                                      reason:
                                        reason || '需求方明确选择此来源版本',
                                    },
                                    key,
                                  );
                                },
                              ),
                          })
                        }
                      >
                        明确采用此版本
                      </Button>
                    ),
                  },
                ]}
              />
              <Button
                disabled={busy || !historySample || !voteIds.length}
                onClick={() =>
                  void act(
                    `aggregate:${historySample?.sampleId}:${voteIds.join(',')}`,
                    async (key) => {
                      if (!historySample) return;
                      const created = await api.aggregate(
                        {
                          algorithmId: 'majority-vote-v1',
                          specVersionId: specs,
                          inputs: [
                            {
                              sampleId: historySample.sampleId,
                              resultIds: voteIds,
                            },
                          ],
                          parameters: {},
                        },
                        key,
                      );
                      await api.aggregation(created.aggregationId);
                      const output = await api.aggregationItems(
                        created.aggregationId,
                      );
                      setAggregation({
                        aggregationId: created.aggregationId,
                        items: output.items,
                      });
                    },
                  )
                }
              >
                生成集成建议（不审核）
              </Button>
              {aggregation && (
                <Table
                  rowKey="sampleId"
                  dataSource={aggregation.items}
                  scroll={{ x: 800 }}
                  pagination={false}
                  columns={[
                    { title: '状态', dataIndex: 'status' },
                    {
                      title: '各类票数',
                      render: (_, r) => JSON.stringify(r.voteCounts),
                    },
                    { title: '有效票数', dataIndex: 'validVoteCount' },
                    {
                      title: '投票支持比例',
                      render: (_, r) => r.voteSupport ?? '无共识/不足',
                    },
                    {
                      title: '需求方操作',
                      render: (_, r) => (
                        <Button
                          disabled={busy || !r.suggestedResultId}
                          onClick={() =>
                            modal.confirm({
                              title: '明确采用此集成建议？',
                              onOk: () =>
                                act(
                                  `adopt:${r.suggestedResultId}`,
                                  async (key) => {
                                    const current = rows.find(
                                      (v) => v.sampleId === r.sampleId,
                                    );
                                    if (!current)
                                      throw new Error(
                                        '请在当前结果范围重新读取该样本',
                                      );
                                    await api.review(
                                      {
                                        action: 'ACCEPT',
                                        items: [
                                          {
                                            sampleId: r.sampleId,
                                            resultId: r.suggestedResultId,
                                            baseReviewRevision:
                                              current.reviewRevision,
                                          },
                                        ],
                                        reason:
                                          reason || '需求方明确采用集成建议',
                                      },
                                      key,
                                    );
                                  },
                                ),
                            })
                          }
                        >
                          明确采用建议
                        </Button>
                      ),
                    },
                  ]}
                />
              )}
            </Card>
            <Card id="workflow-step-5" title="5. 固定成果范围与分类 CSV 导出">
              <Alert
                type="info"
                message="先固定 FINALIZE 范围；全部有效已确认且没有活动处理才可形成成果，缺项不能静默排除。范围成果不代表全项目完成。"
              />
              <Button
                style={{ marginTop: 16 }}
                disabled={busy || selection?.purpose !== 'FINALIZE'}
                onClick={() =>
                  modal.confirm({
                    title: `确认形成所选 ${selection?.count} 项的固定成果？`,
                    onOk: () =>
                      act(`final:${selection?.selectionId}`, async (key) => {
                        await api.finalize(
                          {
                            selectionId: selection?.selectionId,
                            name: '需求方确认的合成范围成果',
                          },
                          key,
                        );
                        setSelection(undefined);
                        setNotice('已固定范围成果，项目其他未完成项仍保留');
                      }),
                  })
                }
              >
                确认形成范围成果
              </Button>
              <Table<FinalSnapshot>
                rowKey="finalSnapshotId"
                dataSource={finals}
                scroll={{ x: 750 }}
                rowSelection={{
                  selectedRowKeys: selectedFinals,
                  onChange: setSelectedFinals,
                }}
                columns={[
                  { title: '成果快照', dataIndex: 'finalSnapshotId' },
                  { title: '名称', dataIndex: 'name' },
                  { title: '范围数量', dataIndex: 'sampleCount' },
                  { title: '固定规范', dataIndex: 'specVersionId' },
                ]}
              />
              <Space wrap>
                <Checkbox
                  checked={includeOriginals}
                  onChange={(e) => setIncludeOriginals(e.target.checked)}
                >
                  包含原图（当前未实现，会明确拒绝）
                </Checkbox>
                <Button
                  type="primary"
                  disabled={busy || !selectedFinals.length}
                  onClick={() =>
                    void act(
                      `export:${selectedFinals.join(',')}:${includeOriginals}`,
                      async (key) => {
                        setJob(
                          await api.export(
                            {
                              finalSnapshotIds: selectedFinals.map(String),
                              format: 'classification-csv-zip',
                              includeOriginals,
                            },
                            key,
                          ),
                        );
                      },
                    )
                  }
                >
                  创建成果导出
                </Button>
              </Space>
              {job && (
                <Space wrap style={{ marginTop: 16 }}>
                  <Tag>
                    {job.exportId} · {job.status} · {job.sampleCount} 项
                  </Tag>
                  {job.errorCode && <Tag color="red">{job.errorCode}</Tag>}
                  <Button
                    disabled={busy}
                    onClick={() =>
                      void act(`export-status:${job.exportId}`, async () =>
                        setJob(await api.exportStatus(job.exportId)),
                      )
                    }
                  >
                    查询导出状态
                  </Button>
                  <Button
                    disabled={busy}
                    onClick={() =>
                      void act(`download:${job.exportId}`, download)
                    }
                  >
                    下载分类 ZIP
                  </Button>
                  <Button
                    onClick={() =>
                      mock.setExportFault(job.exportId, { failed: true })
                    }
                  >
                    注入导出失败
                  </Button>
                  <Button
                    onClick={() =>
                      mock.setExportFault(job.exportId, { expired: true })
                    }
                  >
                    注入导出过期
                  </Button>
                </Space>
              )}
            </Card>
            <Card title="合成项目状态与审计">
              <Space wrap>
                <Tag>{state?.project.status}</Tag>
                {['pause', 'resume', 'archive'].map((action) => (
                  <Button
                    key={action}
                    disabled={busy}
                    onClick={() =>
                      modal.confirm({
                        title: `确认合成项目 ${action}？`,
                        onOk: () =>
                          act(
                            `${action}:${state?.project.revision}:${reason}`,
                            async (key) => {
                              await call(
                                'POST',
                                `/projects/demo-project/${action}`,
                                {
                                  baseRevision: state?.project.revision,
                                  reason,
                                },
                                key,
                              );
                            },
                          ),
                      })
                    }
                  >
                    {
                      (
                        {
                          pause: '暂停项目',
                          resume: '恢复项目',
                          archive: '归档项目',
                        } as Record<string, string>
                      )[action]
                    }
                  </Button>
                ))}
              </Space>
              <Table
                rowKey={(_, index) => String(index)}
                dataSource={state?.audit || []}
                pagination={{ pageSize: 5 }}
                scroll={{ x: 800 }}
                columns={[
                  { title: '操作', dataIndex: 'operation' },
                  { title: '身份', dataIndex: 'actorId' },
                  { title: '时间', dataIndex: 'createdAt' },
                ]}
              />
            </Card>
          </>
        )}
        <Modal
          title={`固定选择预览 · ${selection?.count || 0} 项`}
          open={previewOpen}
          onCancel={() => setPreviewOpen(false)}
          footer={
            <Space wrap>
              {previewCursor && (
                <Button
                  disabled={busy}
                  onClick={() =>
                    void act(`preview:${previewCursor}`, async () => {
                      if (!selection) return;
                      const p = await api.selectionItems(
                        selection.selectionId,
                        previewCursor,
                      );
                      setPreview((old) => [...old, ...p.items]);
                      setPreviewCursor(p.nextCursor);
                    })
                  }
                >
                  加载后续选择
                </Button>
              )}
              <Button onClick={() => setPreviewOpen(false)}>
                完成范围预览
              </Button>
            </Space>
          }
        >
          <Typography.Paragraph>
            选择已冻结，审核/发布/成果仍须各自确认。排除项在创建快照时生效，不会在操作时动态扩张。
          </Typography.Paragraph>
          <Table
            rowKey="sampleId"
            dataSource={preview}
            scroll={{ x: 500 }}
            pagination={{ pageSize: 10 }}
            columns={[
              { title: '样本', dataIndex: 'sampleId' },
              { title: '结果', dataIndex: 'resultId' },
              { title: '审核版本', dataIndex: 'reviewRevision' },
            ]}
          />
        </Modal>
        <Modal
          title="需求方修订为新候选（不自动确认）"
          open={Boolean(edit)}
          confirmLoading={busy}
          onCancel={() => {
            if (!busy) setEdit(undefined);
          }}
          onOk={() =>
            void act(
              `revise:${edit?.resultId}:${edit?.reviewRevision}:${editLabel}:${reason}`,
              async (key) => {
                if (!edit?.resultId) return;
                await api.revise(
                  edit.resultId,
                  {
                    baseReviewRevision: edit.reviewRevision,
                    resultFormat: 'image-classification-v1',
                    result: { labelId: editLabel },
                    reason,
                  },
                  key,
                );
                setEdit(undefined);
                setNotice('新 OWNER 候选待审核，原答案保留且不继承 AI 分数');
              },
            )
          }
          okText="创建新修订"
          cancelText="取消"
        >
          <Typography.Paragraph>
            {edit?.sampleId} · 当前审核 revision {edit?.reviewRevision}
          </Typography.Paragraph>
          <Select
            aria-label="需求方修订标签"
            style={{ width: '100%' }}
            value={editLabel}
            onChange={setEditLabel}
            options={labels.map((l) => ({ value: l.id, label: l.name }))}
          />
          <Typography.Paragraph>
            原因使用结果区填写的“操作原因”；保存不会自动确认。
          </Typography.Paragraph>
        </Modal>
      </section>
    </main>
  );
}
