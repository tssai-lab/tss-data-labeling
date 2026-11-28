import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  operationKey,
  type SpecVersion,
} from '@/services/labeling';
import {
  type AiResult,
  assertChosen,
  type ConfidencePolicy,
  executionTerminal,
  type HistoryRow,
  type LiveAgent,
  type LiveExecution,
  type LiveResult,
  ownerApi,
  selectionItem,
} from '@/services/labelingOwner';
import ProjectHistory from './ProjectHistory';
import ScopeSummary from './ScopeSummary';
import StatusBadge from './StatusBadge';
import useOwnerAction from './useOwnerAction';
import ui from './workspaceUi.less';

export default function LiveAi({
  projectId,
  spec,
  selected,
  active,
  onChanged,
  onBusyChange,
  writeBlocked,
  onFilter,
  initialExecutionId,
  onExecution,
}: {
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  active: boolean;
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
  initialExecutionId?: string;
  onExecution: (id: string) => void;
  onFilter: (
    execution: string,
    policy: string,
    band: string | undefined,
    spec: string,
  ) => void;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [agents, setAgents] = useState<LiveAgent[]>([]),
    [agentIds, setAgentIds] = useState<string[]>([]);
  const [openRounds, setOpenRounds] = useState<HistoryRow[]>([]),
    [humanRoundId, setHumanRoundId] = useState<string>();
  const [reference, setReference] = useState(false),
    [instructions, setInstructions] = useState('');
  const [known, setKnown] = useState(''),
    [execution, setExecution] = useState<LiveExecution>();
  const [items, setItems] = useState<AiResult[]>([]),
    [cursor, setCursor] = useState<string | null>(null),
    [provisional, setProvisional] = useState(false);
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0);
  const [policies, setPolicies] = useState<ConfidencePolicy[]>([]);
  const [groupExecutions, setGroupExecutions] = useState<LiveExecution[]>([]);
  const [lower, setLower] = useState<number | null>(null),
    [upper, setUpper] = useState<number | null>(null),
    [reason, setReason] = useState('');
  const version = useRef(0),
    pollLock = useRef(false),
    pageCursors = useRef(new Set<string>()),
    groupExecutionKeys = useRef(new Map<string, string>());
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi
      .agents(abort.signal, spec.taskType)
      .then((value) => {
        if (!abort.signal.aborted) setAgents(value);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, refresh, spec.taskType]);
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi
      .historyList(projectId, 'rounds', null, abort.signal)
      .then((page) => {
        if (!abort.signal.aborted)
          setOpenRounds(page.items.filter((row) =>
              row.roundId && row.projectId === projectId && row.specVersionId === spec.specVersionId
              && row.status === 'OPEN' && row.packageId
              && ['HUMAN_POOL', 'MIXED'].includes(row.mode || ''),
          ));
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => abort.abort();
  }, [projectId, spec.specVersionId, refresh]);
  useEffect(
    () => () => {
      ++version.current;
    },
    [projectId],
  );
  useEffect(() => {
    setAgentIds([]);
    setGroupExecutions([]);
    setHumanRoundId(undefined);
  }, [projectId, spec.specVersionId]);
  async function load(id: string, append = false) {
    if (pollLock.current || action.busy) return;
    pollLock.current = true;
    setLoading(true);
    setError('');
    const current = ++version.current;
    try {
      const value = await ownerApi.execution(id);
      if (value.projectId !== projectId)
        throw new Error('AI 执行不属于当前项目');
      const next = append ? cursor : null;
      const page = await ownerApi.executionResults(id, next);
      if (
        page.provisional !== !executionTerminal(value.status) ||
        (executionTerminal(value.status) &&
          page.snapshotId !== value.resultSnapshotId)
      )
        throw new Error('AI 执行正在变更，请重新读取一致的结果快照');
      if (current !== version.current) return;
      if (
        append &&
        (page.provisional || page.snapshotId !== execution?.resultSnapshotId)
      )
        throw new Error('执行结果已变化，请从第一页重新读取');
      const rules = executionTerminal(value.status)
        ? await ownerApi.policies(value.roundId)
        : [];
      if (current !== version.current) return;
      if (!append) pageCursors.current.clear();
      if (page.nextCursor && pageCursors.current.has(page.nextCursor))
        throw new Error('AI 结果分页游标重复，请重新读取');
      if (
        append &&
        page.items.some((item) =>
          items.some((old) => old.sampleId === item.sampleId),
        )
      )
        throw new Error('AI 结果样本重复，请重新读取');
      if (page.nextCursor) pageCursors.current.add(page.nextCursor);
      setExecution(value);
      setKnown(value.executionId);
      setProvisional(page.provisional);
      setCursor(page.provisional ? null : page.nextCursor);
      setPolicies(rules);
      setItems((old) => (append ? [...old, ...page.items] : page.items));
    } catch (e) {
      if (current === version.current) setError(describeLabelingError(e));
    } finally {
      pollLock.current = false;
      if (current === version.current) setLoading(false);
    }
  }
  useEffect(() => {
    if (initialExecutionId && !execution && !action.busy) {
      setKnown(initialExecutionId);
      void load(initialExecutionId);
    }
  }, [initialExecutionId, action.busy]);
  useEffect(() => {
    if (!active || !execution || executionTerminal(execution.status) || error)
      return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible')
        void load(execution.executionId);
    }, 5000);
    return () => clearInterval(timer);
  }, [active, execution?.executionId, execution?.status, error, action.busy]);
  const compatibleAgents = agents.filter(
    (item) =>
      item.enabled &&
      item.taskType === (spec.taskType || 'IMAGE_CLASSIFICATION'),
  );
  const selectedAgents = compatibleAgents.filter((item) =>
    agentIds.includes(item.agentId),
  );
  const humanRound = openRounds.find((row) => row.roundId === humanRoundId);
  function create() {
    if (!selectedAgents.length) return;
    if (!humanRound && !selected.length) return;
    if (reference && selectedAgents.some((item) => !item.supportsReference)) {
      action.setError('所选标注员中有不支持参考答案的 Agent');
      return;
    }
    modal.confirm({
      title: `让 ${selectedAgents.length} 个标注员分别处理 ${humanRound?.sampleCount ?? selected.length} 个样本？`,
      content:
        humanRound
          ? 'AI 将加入所选人工轮次，与人工共用冻结样本范围。轮次会等人工席位和 AI 执行全部结束后再关闭。'
          : '每个标注员会产生独立执行与结果快照，共用同一轮冻结范围；结果可在核查页比较或集成。已确认样本重新执行会撤销当前确认。',
      onOk: async () => {
        const value = await action.run(
          'ai-create',
          {
            selected: humanRound ? [] : selected.map(selectionItem),
            spec: spec.specVersionId,
            agentIds,
            roundId: humanRound?.roundId,
            reference,
            instructions,
          },
          async (key, prepare) => {
            let selectionId: string | undefined;
            if (!humanRound) {
              const selection = await prepare(async () => {
                assertChosen(selected, spec.specVersionId);
                return ownerApi.select(
                  projectId,
                  {
                    specVersionId: spec.specVersionId,
                    purpose: 'PUBLISH',
                    items: selected.map(selectionItem),
                  },
                  operationKey(),
                );
              });
              selectionId = selection.selectionId;
            }
            const executions: LiveExecution[] = [];
            let roundId: string | undefined = humanRound?.roundId;
            try {
              for (const [index, selectedAgent] of selectedAgents.entries()) {
                const fingerprint = JSON.stringify([
                  projectId,
                  selectionId || roundId,
                  selectedAgent.agentId,
                  reference,
                  instructions,
                ]);
                const executionKey = index === 0
                  ? key
                  : groupExecutionKeys.current.get(fingerprint) || operationKey();
                if (index > 0) groupExecutionKeys.current.set(fingerprint, executionKey);
                const execution = await ownerApi.executeSelection(
                  projectId,
                  {
                    specVersionId: spec.specVersionId,
                    agentId: selectedAgent.agentId,
                    ...(selectionId ? {
                      selectionId,
                      referenceMode: reference ? 'SELECTED' : 'NONE',
                      reopenAccepted: true,
                    } : {}),
                    instructions,
                    ...(roundId ? { roundId } : {}),
                  },
                  executionKey,
                );
                roundId = execution.roundId;
                executions.push(execution);
                setGroupExecutions([...executions]);
              }
              groupExecutionKeys.current.clear();
              return executions[0];
            } catch (e) {
              setGroupExecutions([...executions]);
              throw e;
            }
          },
          `已创建 ${selectedAgents.length} 个 AI 标注员执行单元`,
        );
        if (value) {
          setExecution(value);
          setKnown(value.executionId);
          setItems([]);
          setPolicies([]);
          setCursor(null);
          setProvisional(!executionTerminal(value.status));
          setError('');
          onExecution(value.executionId);
          onChanged();
          void load(value.executionId);
        }
      },
    });
  }
  function cancel() {
    if (!execution || !reason.trim()) {
      action.setError('请填写取消原因');
      return;
    }
    const captured = execution,
      body = { baseRevision: captured.revision, reason: reason.trim() };
    modal.confirm({
      title: '取消当前 AI 执行？',
      content: '已处理成功的终态快照仍会保留；未完成样本不会伪造答案。',
      onOk: async () => {
        const value = await action.run(
          `ai-cancel:${captured.executionId}`,
          body,
          (key) => ownerApi.cancelExecution(captured.executionId, body, key),
          'AI 执行已取消',
        );
        if (value) {
          setExecution(value);
          setItems([]);
          setCursor(null);
          setError('');
          onChanged();
          void load(value.executionId);
        }
      },
    });
  }
  async function savePolicy() {
    if (
      !execution?.resultSnapshotId ||
      lower === null ||
      upper === null ||
      lower < 0 ||
      upper > 1 ||
      lower >= upper
    ) {
      action.setError('请输入 0 ≤ 低阈值 < 高阈值 ≤ 1');
      return;
    }
    const body = {
      snapshotId: execution.resultSnapshotId,
      lowerThreshold: lower,
      upperThreshold: upper,
    };
    const value = await action.run(
      `policy:${execution.roundId}`,
      body,
      (key) => ownerApi.policy(execution.roundId, body, key),
      '置信度分档策略已保存，不会自动确认答案',
    );
    if (value) setPolicies((old) => [...old, value]);
  }
  const progressPercent = execution?.sampleCount
    ? Math.min(
        100,
        Math.round(
          ((execution.sampleCount - execution.pendingCount) /
            execution.sampleCount) *
            100,
        ),
      )
    : 0;
  const taskTypeName: Record<string, string> = {
    IMAGE_CLASSIFICATION: '图片分类',
    COMPUTER_VISION: '计算机视觉',
    TIME_SERIES: '时序分析',
    TIMESERIES_ANNOTATION: '时序标注',
  };
  const executionStatusName: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '处理中',
    SUCCEEDED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '执行失败',
    CANCELLING: '取消中',
    CANCELLED: '已取消',
  };
  return (
    <div className={ui.aiConsole}>
      <section className={ui.aiHero}>
        <div className={ui.aiHeroMain}>
          <span className={ui.aiEyebrow}>AI LABELING · HUMAN IN THE LOOP</span>
          <h2>
            让 AI 承担规模化标注
            <br />
            让专家把关关键结果
          </h2>
          <p>
            批量生成候选答案，按置信度组织审核范围；每次执行与结果快照可追溯，最终由需求方确认。
          </p>
          <div className={ui.aiFlow} aria-label="AI 标注处理流程">
            <span>选择样本</span>
            <i />
            <span>AI 处理</span>
            <i />
            <span>人工核查</span>
            <i />
            <span>成果导出</span>
          </div>
        </div>
        <div className={ui.aiHeroSide}>
          <div>
            <span>当前执行状态</span>
            <strong>
              {execution
                ? executionStatusName[execution.status] || execution.status
                : selected.length
                  ? '等待启动'
                  : '等待选择样本'}
            </strong>
          </div>
          <Progress
            type="circle"
            size={88}
            percent={progressPercent}
            strokeColor="#72d7c0"
            trailColor="#ffffff2e"
            format={(percent) => `${percent}%`}
          />
          <small>
            {execution
              ? `${execution.successCount} / ${execution.sampleCount} 个样本已完成`
              : '选择待处理样本后即可创建 AI 执行'}
          </small>
        </div>
      </section>
      <div className={ui.aiMetrics}>
        <div className={ui.aiMetric}>
          <span>{humanRound ? '本轮样本' : '本次选择样本'}</span>
          <strong>{humanRound?.sampleCount ?? selected.length}</strong>
          <small>{humanRound ? '人工与 AI 共用范围' : '执行前固定处理范围'}</small>
        </div>
        <div className={ui.aiMetric}>
          <span>可用 AI Agent</span>
          <strong>{compatibleAgents.length}</strong>
          <small>
            {taskTypeName[spec.taskType || ''] || spec.taskType || '图片分类'} 任务类型
          </small>
        </div>
        <div className={ui.aiMetric}>
          <span>AI 成功处理</span>
          <strong>{execution?.successCount ?? '—'}</strong>
          <small>{execution ? '当前执行结果' : '启动后显示真实结果'}</small>
        </div>
        <div className={ui.aiMetric}>
          <span>等待处理</span>
          <strong>{execution?.pendingCount ?? '—'}</strong>
          <small>{execution ? '尚未完成的样本' : '由执行状态实时更新'}</small>
        </div>
      </div>
      {(error || action.error) && (
        <Alert type="error" showIcon message={error || action.error} />
      )}
      {!selected.length && !humanRound && (
        <Alert
          className={ui.aiSelectionPrompt}
          type="info"
          showIcon
          message="选择 AI 处理范围"
          description="返回“结果与审核”勾选样本以新建 AI 轮次，或选择一个开放的人工轮次，让人工与 AI 共用同一轮样本。"
        />
      )}
      <div className={selected.length || humanRound ? ui.twoColumns : undefined}>
        {!humanRound && selected.length > 0 && <ScopeSummary
          projectId={projectId}
          spec={spec}
          selected={selected}
          disabled={action.busy || loading}
        />}
        <Card className={ui.aiSetupCard} title="配置 AI 执行">
          <Alert
            type="info"
            message={humanRound
              ? `加入第 ${humanRound.roundNo || '—'} 轮人工任务 · ${humanRound.sampleCount || 0} 个样本`
              : `当前明确勾选 ${selected.length} 个样本`}
            description={humanRound
              ? '人工领取与 AI 执行共用同一轮样本范围；双方都完成后才释放样本。'
              : '可选择多个标注员分别处理同一冻结范围。每个标注员都会生成独立执行与结果快照，结果不会自动通过审核。'}
          />
          <Space direction="vertical" style={{ marginTop: 14, width: '100%' }}>
            <Select
              allowClear
              aria-label="加入人工轮次"
              style={{ width: '100%' }}
              value={humanRoundId}
              disabled={action.busy}
              placeholder="单独创建 AI 轮次；或加入开放的人工轮次"
              onChange={(value) => {
                setHumanRoundId(value);
                if (value) setReference(false);
              }}
              options={openRounds.map((row) => ({
                value: row.roundId || '',
                label: `第 ${row.roundNo || '—'} 轮 · ${row.sampleCount || 0} 个样本 · 人工任务`,
              }))}
            />
            <Select
              aria-label="选择 AI 标注员"
              mode="multiple"
              maxCount={10}
              maxTagCount="responsive"
              style={{ width: '100%' }}
              value={agentIds}
              disabled={action.busy}
              placeholder={
                agents.length ? '选择一个或多个标注员' : '暂无可用 Agent'
              }
              onChange={(value) => {
                setAgentIds(value);
                if (value.some((id) => !compatibleAgents.find((item) => item.agentId === id)?.supportsReference))
                  setReference(false);
              }}
              options={compatibleAgents.map((item) => ({
                value: item.agentId,
                label: `${item.displayName} · ${item.annotatorType} · ${item.modelVersion}`,
              }))}
            />
            <Button
              disabled={action.busy}
              onClick={() => setRefresh((n) => n + 1)}
            >
              刷新 Agent
            </Button>
            <Checkbox
              disabled={!selectedAgents.length || selectedAgents.some((item) => !item.supportsReference) || action.busy}
              checked={reference}
              onChange={(e) => setReference(e.target.checked)}
            >
              附带所选答案作为参考
            </Checkbox>
            <Input
              aria-label="AI 执行补充说明"
              placeholder="本次执行说明"
              value={instructions}
              disabled={action.busy}
              maxLength={500}
              onChange={(e) => setInstructions(e.target.value)}
              style={{ width: '100%' }}
            />
            <Button
              type="primary"
              disabled={!selectedAgents.length || (!selected.length && !humanRound) || loading || action.busy}
              onClick={create}
            >
              {selectedAgents.length
                ? `启动 ${selectedAgents.length} 个 AI 标注员`
                : '启动 AI 标注'}
            </Button>
          </Space>
          {selectedAgents.length > 0 && (
            <p>
              {selectedAgents.map((item) => `${item.displayName}：${item.confidenceDescription || '未提供置信度语义说明'}`).join('；')}
            </p>
          )}
          {groupExecutions.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <Typography.Text strong>本轮独立执行</Typography.Text>
              <Space wrap style={{ display: 'flex', marginTop: 8 }}>
                {groupExecutions.map((item) => {
                  const source = agents.find((candidate) => candidate.agentId === item.agentId);
                  return (
                    <Button key={item.executionId} size="small" onClick={() => void load(item.executionId)}>
                      {source?.displayName || item.agentDisplayName || item.agentId} · {item.status}
                    </Button>
                  );
                })}
              </Space>
            </div>
          )}
        </Card>
      </div>
      <Card className={ui.aiExecutionCard} title="执行监控与结果">
        <details>
          <summary>服务器 AI 执行历史</summary>
          <ProjectHistory
            projectId={projectId}
            kind="ai-executions"
            refresh={refresh}
            onOpen={(row) => {
              if (row.executionId) {
                setExecution(undefined);
                setItems([]);
                setPolicies([]);
                void load(row.executionId);
              }
            }}
          />
        </details>
        <details open={!execution}>
          <summary>查询已有 AI 任务</summary>
          <Space wrap>
            <Input
              aria-label="已知 AI 执行编号"
              placeholder="粘贴已有 AI 任务编号"
              value={known}
              disabled={loading || action.busy}
              onChange={(e) => setKnown(e.target.value)}
              style={{ width: 340 }}
            />
            <Button
              loading={loading}
              disabled={!known.trim() || action.busy}
              onClick={() => {
                setExecution(undefined);
                setItems([]);
                setPolicies([]);
                void load(known.trim());
              }}
            >
              读取执行
            </Button>
          </Space>
        </details>
        {execution && (
          <>
            <p>
              <StatusBadge status={execution.status} />
              总计 {execution.sampleCount} · 成功 {execution.successCount} ·
              失败 {execution.failedCount} · 待处理 {execution.pendingCount} ·
              已取消 {execution.cancelledCount}
            </p>
            <Progress
              percent={progressPercent}
              status={executionTerminal(execution.status) ? 'normal' : 'active'}
            />
            <p>模型版本 {execution.modelVersion} · {provisional ? '运行中结果仅供查看，完成后进入人工审核' : '本次结果已形成快照，可进入人工审核'}</p>
            <details className={ui.aiTechnicalDetails}>
              <summary>执行与快照编号</summary>
              <Typography.Paragraph copyable>
                执行编号：{execution.executionId}
              </Typography.Paragraph>
              <Typography.Paragraph copyable>
                轮次编号：{execution.roundId}
              </Typography.Paragraph>
              {execution.resultSnapshotId && (
                <Typography.Paragraph copyable>
                  结果快照：{execution.resultSnapshotId}
                </Typography.Paragraph>
              )}
            </details>
            {execution.errorSummary?.byCode.map((item) => (
              <Tag key={item.errorCode} color="red">
                {item.errorCode}: {item.count}
              </Tag>
            ))}
            {!executionTerminal(execution.status) && (
              <Space wrap>
                <Input
                  aria-label="取消 AI 执行原因"
                  placeholder="取消原因"
                  maxLength={1000}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  disabled={action.busy}
                />
                <Button
                  danger
                  disabled={action.busy || loading}
                  onClick={cancel}
                >
                  取消执行
                </Button>
              </Space>
            )}
            {executionTerminal(execution.status) &&
              execution.failedCount + execution.cancelledCount > 0 && (
                <Button
                  disabled={loading || action.busy}
                  onClick={() =>
                    modal.confirm({
                      title: '重试本次执行的全部失败和未完成样本？',
                      content:
                        '创建新的子执行，复用原 Agent 和模型；成功结果与原快照保持不变。',
                      onOk: async () => {
                        const body = { baseRevision: execution.revision };
                        const value = await action.run(
                          `retry:${execution.executionId}`,
                          body,
                          (key) =>
                            ownerApi.retry(execution.executionId, body, key),
                          '重试执行已创建',
                        );
                        if (value) {
                          setExecution(value);
                          setKnown(value.executionId);
                          setItems([]);
                          setPolicies([]);
                          setError('');
                          onExecution(value.executionId);
                          onChanged();
                          void load(value.executionId);
                        }
                      },
                    })
                  }
                >
                  重试失败范围
                </Button>
              )}
            <Button
              style={{ margin: 12 }}
              disabled={loading || action.busy}
              onClick={() => void load(execution.executionId)}
            >
              刷新此执行
            </Button>
            <Table
              rowKey="sampleId"
              dataSource={items}
              pagination={false}
              scroll={{ x: 650 }}
              columns={[
                { title: '样本', dataIndex: 'sampleId' },
                {
                  title: '处理状态',
                  dataIndex: 'status',
                  render: (status: string) => <StatusBadge status={status} />,
                },
                {
                  title: '答案',
                  render: (_, item) => item.result?.labelId || '—',
                },
                {
                  title: '置信度',
                  render: (_, item) => item.confidence ?? '未知',
                },
                {
                  title: '审核资格',
                  render: (_, item) =>
                    !provisional && item.reviewable && item.resultId
                      ? '可审核'
                      : '不可审核',
                },
                { title: '错误', dataIndex: 'errorCode' },
              ]}
            />
            {cursor && (
              <Button
                disabled={loading || action.busy}
                onClick={() => void load(execution.executionId, true)}
              >
                更多执行结果
              </Button>
            )}
            {executionTerminal(execution.status) &&
              execution.resultSnapshotId && (
                <>
                  <h3>置信度分档策略</h3>
                  <Space wrap>
                    <InputNumber
                      aria-label="置信度低阈值"
                      placeholder="低阈值"
                      min={0}
                      max={1}
                      step={0.01}
                      value={lower}
                      onChange={setLower}
                      disabled={action.busy}
                    />
                    <InputNumber
                      aria-label="置信度高阈值"
                      placeholder="高阈值"
                      min={0}
                      max={1}
                      step={0.01}
                      value={upper}
                      onChange={setUpper}
                      disabled={action.busy}
                    />
                    <Button
                      disabled={action.busy}
                      onClick={() => void savePolicy()}
                    >
                      保存新分档策略
                    </Button>
                  </Space>
                  <Table
                    rowKey="policyId"
                    dataSource={policies}
                    pagination={{ pageSize: 5 }}
                    scroll={{ x: 650 }}
                    columns={[
                      { title: '策略编号', dataIndex: 'policyId' },
                      { title: '低阈值', dataIndex: 'lowerThreshold' },
                      { title: '高阈值', dataIndex: 'upperThreshold' },
                      {
                        title: '筛选审核',
                        render: (_, policy) => (
                          <Space wrap>
                            {['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].map(
                              (band) => (
                                <Button
                                  key={band}
                                  disabled={action.busy}
                                  onClick={() =>
                                    onFilter(
                                      execution.executionId,
                                      policy.policyId,
                                      band,
                                      execution.specVersionId,
                                    )
                                  }
                                >
                                  {
                                    (
                                      {
                                        HIGH: '高置信度',
                                        MEDIUM: '中置信度',
                                        LOW: '低置信度',
                                        UNKNOWN: '未知置信度',
                                      } as Record<string, string>
                                    )[band]
                                  }
                                </Button>
                              ),
                            )}
                          </Space>
                        ),
                      },
                    ]}
                  />
                </>
              )}
            <p>
              失败或取消的样本可使用“重试失败范围”创建新执行，原执行快照保持不变。
            </p>
          </>
        )}
      </Card>
    </div>
  );
}
