import { Link } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Drawer,
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
import ReviewWorkbench, {
  confidenceText,
  ReviewStatus,
  sourceNames,
} from './ReviewWorkbench';
import wb from './reviewWorkbench.less';

const enabled =
  process.env.NODE_ENV !== 'production' &&
  process.env.LABELING_GAP_MOCK === 'true';
const mock = createWorkflowMock({
  enabled,
  production: process.env.NODE_ENV === 'production',
});
type DemoState = {
  project: { name: string; status: string; revision: number };
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
  const loadedLimit = useRef(6);
  const [activeTab, setActiveTab] = useState('review');
  const [selectedId, setSelectedId] = useState('');
  const [readLoading, setReadLoading] = useState(false);
  const [reviewReasons, setReviewReasons] = useState<Record<string, string>>(
    {},
  );
  const [candidateOverrides, setCandidateOverrides] = useState<
    Record<string, ResultRow>
  >({});
  const [historyOpen, setHistoryOpen] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
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
  const [editReason, setEditReason] = useState('');
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
    setReadLoading(true);
    try {
      const [page, statistics, list, agentList] = await Promise.all([
        api.results({ limit: String(loadedLimit.current), ...query }),
        api.statistics(query),
        api.finals(),
        api.agents(),
      ]);
      if (current !== version.current) return;
      setRows(page.items);
      setSelectedId((previous) =>
        page.items.some((item) => item.sampleId === previous)
          ? previous
          : page.items[0]?.sampleId || '',
      );
      setCandidateOverrides({});
      setCursor(page.nextCursor);
      setStats(statistics);
      setFinals(list.items);
      setAgents(agentList);
      setState(mock.snapshot() as DemoState);
    } finally {
      if (current === version.current) setReadLoading(false);
    }
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
    loadedLimit.current = 6;
    setSelectedId('');
    setCandidateOverrides({});
    setHistoryOpen(false);
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
      const conflict =
        e instanceof Error &&
        (('status' in e && e.status === 409) ||
          ('code' in e && e.code === 'REVIEW_CONFLICT'));
      setError(
        `${describeLabelingError(e)}${conflict ? '。输入已保留，请刷新当前范围后重新核对候选版本并确认。' : ''}`,
      );
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
  const displayRows = rows.map(
    (row) => candidateOverrides[row.sampleId] || row,
  );
  function openHistory(row: ResultRow) {
    void act(
      `history:${row.sampleId}`,
      async () => {
        const page = await api.history(row.sampleId);
        setHistory(page.items);
        setHistorySample(row);
        setVoteIds([]);
        setHistoryOpen(true);
      },
      false,
    );
  }
  function openRevision(row: ResultRow) {
    setError('');
    setEdit({ ...row });
    setEditLabel(row.result?.labelId);
    setEditReason(reviewReasons[row.sampleId] || '');
  }
  function singleReview(row: ResultRow, action: 'ACCEPT' | 'NEEDS_REVISION') {
    if (
      lock.current ||
      readLoading ||
      row.activeProcessing ||
      (row.resultId && !row.reviewable)
    )
      return;
    if (action === 'ACCEPT' && (!row.resultId || row.requiresResultSelection)) {
      setError('请从来源历史明确选择可审核的候选版本');
      return;
    }
    const operationReason = reviewReasons[row.sampleId] || '';
    if (action === 'NEEDS_REVISION' && !operationReason.trim()) {
      setError('标记待修正前请填写操作原因，输入会保留');
      return;
    }
    const target = {
      sampleId: row.sampleId,
      resultId: row.resultId,
      baseReviewRevision: row.reviewRevision,
    };
    modal.confirm({
      title:
        action === 'ACCEPT' ? '确认采用此候选？' : '确认标记此样本待修正？',
      content: (
        <Descriptions
          column={1}
          size="small"
          items={[
            { key: 'sample', label: '样本', children: target.sampleId },
            {
              key: 'result',
              label: '候选版本',
              children: target.resultId || '无答案样本',
            },
            {
              key: 'revision',
              label: '审核版本',
              children: target.baseReviewRevision,
            },
            {
              key: 'reason',
              label: '操作原因',
              children: operationReason || '明确采用当前候选',
            },
          ]}
        />
      ),
      okText: '确认操作',
      cancelText: '取消',
      maskClosable: false,
      onOk: () =>
        act(
          `single:${action}:${JSON.stringify(target)}:${operationReason}`,
          async (key) => {
            await api.review(
              { action, items: [target], reason: operationReason },
              key,
            );
            setSelection(undefined);
            setNotice(
              action === 'ACCEPT'
                ? `已确认采用 ${target.sampleId} · ${target.resultId}，尚未形成最终成果。`
                : `已标记 ${target.sampleId} 待修正。`,
            );
          },
        ),
    });
  }
  async function choose(all: boolean, key: string) {
    const body = all
      ? { filter, excludeSampleIds: excluded.split(/\s+/).filter(Boolean) }
      : {
          items: displayRows
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
    <main className={wb.page}>
      <header className={wb.topbar}>
        <div className={wb.topbarInner}>
          <div className={wb.brand}>
            TSS <span>/</span>
            <small>DATA ANNOTATION</small>
          </div>
          <div className={wb.headerActions}>
            <Link to="/labeling">真实标注平台</Link>
            <Link to="/platforms">切换平台</Link>
            {enabled && (
              <Select
                aria-label="合成身份"
                value={actor}
                disabled={busy || readLoading}
                onChange={setActor}
                options={['demo-owner', 'demo-admin', 'demo-outsider'].map(
                  (value) => ({ value, label: value }),
                )}
              />
            )}
          </div>
        </div>
      </header>
      <section className={wb.content}>
        <div className={wb.breadcrumb}>
          <span>
            <Link to="/labeling/demo">本地能力演示</Link> / 图片分类
          </span>
          {enabled && (
            <Button
              size="small"
              disabled={busy || readLoading}
              onClick={() => void act('refresh', async () => {})}
            >
              刷新当前范围
            </Button>
          )}
        </div>
        <div className={wb.heading}>
          <h1>需求方审核工作台</h1>
          <Tag color="gold">合成演示</Tag>
          <span className={wb.projectName}>
            {state?.project.name || '图片分类合成项目'}
          </span>
        </div>
        <Alert
          className={wb.notice}
          type="warning"
          showIcon
          message="当前为合成数据，审核与导出接口待接入"
        />
        {enabled && (
          <div
            className={wb.tabs}
            role="tablist"
            aria-label="工作台页签"
            onKeyDown={(event) => {
              const tabs = Array.from(
                event.currentTarget.querySelectorAll<HTMLButtonElement>(
                  '[role="tab"]:not(:disabled)',
                ),
              );
              const index = tabs.indexOf(event.target as HTMLButtonElement);
              if (index < 0) return;
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % tabs.length
                  : event.key === 'ArrowLeft'
                    ? (index + tabs.length - 1) % tabs.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? tabs.length - 1
                        : -1;
              if (next < 0) return;
              event.preventDefault();
              tabs[next].click();
              tabs[next].focus();
            }}
          >
            {[
              { key: 'review', label: '结果与审核' },
              { key: 'publish', label: '范围发布' },
              { key: 'ai', label: 'AI进度与分档' },
              { key: 'sources', label: '来源与集成' },
              { key: 'export', label: '成果导出' },
            ].map((tab, index) => (
              <Button
                key={tab.key}
                id={`review-tab-${tab.key}`}
                type="text"
                role="tab"
                aria-selected={activeTab === tab.key}
                aria-controls={`workflow-step-${index + 1}`}
                tabIndex={activeTab === tab.key ? 0 : -1}
                disabled={busy || readLoading}
                onClick={() => setActiveTab(tab.key)}
              >
                {tab.label}
              </Button>
            ))}
          </div>
        )}
        {!enabled ? (
          <Alert message="开发演示默认关闭，生产构建不可开启。待后端接入。" />
        ) : (
          <>
            {error && (
              <Alert
                className={wb.error}
                type="error"
                showIcon
                message={error}
              />
            )}
            {notice && (
              <Alert
                className={wb.error}
                type="info"
                showIcon
                message={notice}
              />
            )}
            <section className={wb.statistics} aria-label="当前筛选范围统计">
              {[
                ['样本范围', stats.total],
                ['有候选答案', stats.hasResultCount],
                ['无候选答案', stats.noResultCount],
                ['已确认', stats.acceptedCount],
              ].map(([label, value]) => (
                <div className={wb.stat} key={label}>
                  <span>{label}</span>
                  <strong>{value ?? '—'}</strong>
                </div>
              ))}
            </section>
            {activeTab !== 'review' && (
              <Input.TextArea
                aria-label="流程操作原因"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                maxLength={500}
                rows={2}
                placeholder="取消、审核或管理操作原因（需要原因时必填）"
                style={{ marginBottom: 16 }}
              />
            )}
            <section
              id="workflow-step-1"
              role="tabpanel"
              aria-labelledby="review-tab-review"
              hidden={activeTab !== 'review'}
            >
              <ReviewWorkbench
                rows={displayRows}
                selectedId={selectedId}
                onSelect={setSelectedId}
                chosen={chosen}
                onChoose={setChosen}
                filter={filter}
                onFilter={updateFilter}
                onClear={() => setFilter({})}
                busy={busy}
                loading={readLoading}
                total={stats.total}
                cursor={cursor}
                onMore={() =>
                  void act(
                    `page:${cursor}`,
                    async () => {
                      const page = await api.results({
                        ...filter,
                        limit: '6',
                        ...(cursor ? { cursor } : {}),
                      });
                      setRows((old) => [...old, ...page.items]);
                      loadedLimit.current = Math.min(
                        100,
                        loadedLimit.current + page.items.length,
                      );
                      setCursor(page.nextCursor);
                    },
                    false,
                  )
                }
                reason={reviewReasons[selectedId] || ''}
                onReason={(value) =>
                  setReviewReasons((old) => ({ ...old, [selectedId]: value }))
                }
                onReview={singleReview}
                onHistory={openHistory}
                onRevise={openRevision}
                labels={labels}
              />
              <Collapse
                className={wb.advanced}
                activeKey={batchOpen ? ['batch'] : []}
                onChange={(keys) => setBatchOpen(keys.includes('batch'))}
                items={[
                  {
                    key: 'batch',
                    label: '批量操作与固定范围预览',
                    children: (
                      <>
                        <Space wrap style={{ marginTop: 16 }}>
                          <Select
                            aria-label="固定选择用途"
                            value={purpose}
                            onChange={(v) => {
                              setPurpose(v);
                              setSelection(undefined);
                            }}
                            options={['REVIEW', 'PUBLISH', 'FINALIZE'].map(
                              (value) => ({
                                value,
                                label: value,
                              }),
                            )}
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
                          aria-label="批量操作原因"
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
                      </>
                    ),
                  },
                ]}
              />
            </section>
            <section
              className={wb.workflowPanel}
              hidden={activeTab !== 'publish'}
            >
              {' '}
              <Card
                id="workflow-step-2"
                role="tabpanel"
                aria-labelledby="review-tab-publish"
                title="人工发布或 AI 执行"
              >
                <Button
                  style={{ marginBottom: 16 }}
                  onClick={() => {
                    setPurpose('PUBLISH');
                    setActiveTab('review');
                    setBatchOpen(true);
                  }}
                >
                  选择发布范围
                </Button>
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
            </section>
            <section className={wb.workflowPanel} hidden={activeTab !== 'ai'}>
              {' '}
              <Card
                id="workflow-step-3"
                role="tabpanel"
                aria-labelledby="review-tab-ai"
                title="3. AI 进度、失败清单与手动分档"
              >
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
            </section>
            <section
              className={wb.workflowPanel}
              hidden={activeTab !== 'sources'}
            >
              {' '}
              <Card
                id="workflow-step-4"
                role="tabpanel"
                aria-labelledby="review-tab-sources"
                title="4. 多数投票建议与来源历史"
              >
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
            </section>
            <section
              className={wb.workflowPanel}
              hidden={activeTab !== 'export'}
            >
              {' '}
              <Card
                id="workflow-step-5"
                role="tabpanel"
                aria-labelledby="review-tab-export"
                title="固定成果范围与分类 CSV 导出"
              >
                <Button
                  style={{ marginBottom: 16 }}
                  onClick={() => {
                    setPurpose('FINALIZE');
                    setActiveTab('review');
                    setBatchOpen(true);
                  }}
                >
                  选择成果范围
                </Button>
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
            </section>
            <Collapse
              className={wb.advanced}
              items={[
                {
                  key: 'controls',
                  label: '演示控制与项目管理',
                  children: (
                    <>
                      <Card>
                        <Space wrap>
                          <Button
                            disabled={busy}
                            onClick={() => {
                              mock.reset();
                              loadedLimit.current = 6;
                              setSelectedId('');
                              setCandidateOverrides({});
                              setReviewReasons({});
                              setHistoryOpen(false);
                              setBatchOpen(false);
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
                                            baseRevision:
                                              state?.project.revision,
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
                      <Link to="/labeling/demo/energy">
                        能耗模板与质检合成试标
                      </Link>
                    </>
                  ),
                },
              ]}
            />
          </>
        )}
        <Drawer
          title={`${historySample?.sampleId || ''} · 来源历史`}
          open={historyOpen}
          onClose={() => setHistoryOpen(false)}
          width={720}
          extra={
            <Button
              onClick={() => {
                setHistoryOpen(false);
                setActiveTab('sources');
              }}
            >
              查看来源与集成页签
            </Button>
          }
        >
          <Alert
            type="info"
            showIcon
            message="选择只更新当前预览；点击确认采用后才写入审核记录。旧候选与原AI分数保留。"
            style={{ marginBottom: 16 }}
          />
          <Table<ResultRow>
            rowKey="resultId"
            dataSource={history}
            pagination={false}
            scroll={{ x: 620 }}
            columns={[
              { title: '候选版本', dataIndex: 'resultId', ellipsis: true },
              {
                title: '标签',
                render: (_, row) =>
                  labels.find((label) => label.id === row.result?.labelId)
                    ?.name || '—',
              },
              {
                title: '来源',
                render: (_, row) => sourceNames[row.sourceKind || ''] || '—',
              },
              { title: '审核', render: (_, row) => <ReviewStatus row={row} /> },
              { title: 'AI置信度', render: (_, row) => confidenceText(row) },
              {
                title: '操作',
                render: (_, candidate) => (
                  <Button
                    disabled={
                      busy ||
                      readLoading ||
                      !candidate.reviewable ||
                      rows.find((row) => row.sampleId === candidate.sampleId)
                        ?.activeProcessing
                    }
                    onClick={() => {
                      const current = rows.find(
                        (row) => row.sampleId === candidate.sampleId,
                      );
                      if (!current) {
                        setError('此样本已不在当前筛选范围，请刷新后重新选择');
                        return;
                      }
                      setCandidateOverrides((old) => ({
                        ...old,
                        [candidate.sampleId]: {
                          ...current,
                          ...candidate,
                          selectedResultId: current.selectedResultId,
                          reviewRevision: current.reviewRevision,
                          activeProcessing: current.activeProcessing,
                          requiresResultSelection: false,
                          reviewStatus:
                            candidate.resultId === current.selectedResultId
                              ? current.reviewStatus
                              : 'PENDING',
                        },
                      }));
                      setSelectedId(candidate.sampleId);
                      setHistoryOpen(false);
                      setNotice(
                        `正在预览固定历史候选 ${candidate.resultId}，尚未确认采用。`,
                      );
                    }}
                  >
                    选择此候选
                  </Button>
                ),
              },
            ]}
          />
        </Drawer>
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
          maskClosable={false}
          closable={!busy}
          cancelButtonProps={{ disabled: busy }}
          okButtonProps={{ disabled: busy || !editLabel || !editReason.trim() }}
          onCancel={() => {
            if (!busy) setEdit(undefined);
          }}
          onOk={() =>
            void act(
              `revise:${edit?.resultId}:${edit?.reviewRevision}:${editLabel}:${editReason}`,
              async (key) => {
                if (!edit?.resultId) return;
                await api.revise(
                  edit.resultId,
                  {
                    baseReviewRevision: edit.reviewRevision,
                    resultFormat: 'image-classification-v1',
                    result: { labelId: editLabel },
                    reason: editReason,
                  },
                  key,
                );
                setEdit(undefined);
                setReviewReasons((old) => ({
                  ...old,
                  [edit.sampleId]: editReason,
                }));
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
          {error && (
            <Alert type="error" message={error} style={{ marginBottom: 16 }} />
          )}
          <Select
            aria-label="需求方修订标签"
            style={{ width: '100%' }}
            value={editLabel}
            onChange={setEditLabel}
            options={labels.map((l) => ({ value: l.id, label: l.name }))}
          />
          <Input.TextArea
            aria-label="需求方修订原因"
            value={editReason}
            onChange={(event) => setEditReason(event.target.value)}
            rows={3}
            maxLength={500}
            placeholder="填写修订原因，保存不会自动确认"
            style={{ marginTop: 16 }}
            disabled={busy}
          />
        </Modal>
      </section>
    </main>
  );
}
