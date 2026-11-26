import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Select,
  Space,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  post,
} from '@/services/labeling';
import {
  type LiveAgent,
  type LiveExecution,
  type LiveResult,
  ownerApi,
} from '@/services/labelingOwner';
import type { Selection, SelectionItem } from '@/services/labelingWorkflow';
import {
  collectPages,
  type NativeProject,
  type NativeSample,
  type NativeSpec,
} from '@/services/nativeLabeling';
import ui from './workspaceUi.less';

type GroupExecution = LiveExecution & { agentConfigId: string };
type Receipt = {
  publicationGroupId: string;
  selectionId: string;
  specVersionId: string;
  sampleCount: number;
  sampleIds?: string[];
  humanStatus?: string;
  humanPlan: { packages: { packageId: string; sampleCount: number }[] } | null;
  executions: GroupExecution[];
};
type Props = {
  project: NativeProject;
  samples: NativeSample[];
  version?: NativeSpec;
  specVersions: NativeSpec[];
  reviews: any[];
  initialSelection?: string[];
  onVersionChange: (id: string) => void;
  refresh: () => void;
  onExecutions: (
    executions: {
      execution: GroupExecution;
      samples: NativeSample[];
      version: NativeSpec;
      model: string;
    }[],
  ) => void;
  onOpenExecution: (
    execution: LiveExecution,
    samples: NativeSample[],
    version: NativeSpec,
    model: string,
  ) => void;
};

export default function ParallelPublish({
  project,
  samples,
  version,
  specVersions,
  reviews,
  initialSelection,
  onVersionChange,
  refresh,
  onExecutions,
  onOpenExecution,
}: Props) {
  const [selected, setSelected] = useState<string[]>(initialSelection || []);
  const [human, setHuman] = useState(true),
    [ai, setAi] = useState(false),
    [reopen, setReopen] = useState(false);
  const [query, setQuery] = useState(''),
    [status, setStatus] = useState('ALL'),
    [pageSize, setPageSize] = useState(5);
  const [size, setSize] = useState(5),
    [people, setPeople] = useState(1),
    [instructions, setInstructions] = useState('');
  const [agents, setAgents] = useState<LiveAgent[]>([]),
    [chosenAgents, setChosenAgents] = useState<string[]>([]);
  const [step, setStep] = useState(0),
    [snapshot, setSnapshot] = useState<Selection>(),
    [frozen, setFrozen] = useState<SelectionItem[]>([]);
  const [frozenAcceptedCount, setFrozenAcceptedCount] = useState(0);
  const [receipt, setReceipt] = useState<Receipt>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const [history, setHistory] = useState<
    { publicationGroupId: string; specVersionId: string; createdAt: string }[]
  >([]);
  const selectionAttempt = useRef<
      { signature: string; key: string } | undefined
    >(undefined),
    publishAttempt = useRef<{ signature: string; key: string } | undefined>(
      undefined,
    );
  const retryKeys = useRef<Record<string, string>>({});
  const locked = !['DRAFT', 'OPEN'].includes(project.status);
  const reviewMap = useMemo(
    () =>
      new Map(
        reviews
          .filter((r) => r.specVersionId === version?.specVersionId)
          .map((r) => [r.sampleId, r.status]),
      ),
    [reviews, version?.specVersionId],
  );
  const filtered = samples.filter(
    (sample) =>
      [sample.metadata.displayName || '', sample.sourcePath].some((value) =>
        value.toLowerCase().includes(query.trim().toLowerCase()),
      ) &&
      (status === 'ALL' ||
        (reviewMap.get(sample.sampleId) || 'PENDING') === status),
  );
  const eligible = filtered
    .filter((sample) => reopen || reviewMap.get(sample.sampleId) !== 'ACCEPTED')
    .map((sample) => sample.sampleId);
  const selectedSet = new Set(selected),
    all = eligible.length > 0 && eligible.every((id) => selectedSet.has(id));
  const selectedSamples = selected
    .map((id) => samples.find((sample) => sample.sampleId === id))
    .filter((sample): sample is NativeSample => !!sample);
  const packageCounts = Array.from(
    { length: Math.ceil(selected.length / size) },
    (_, index) => Math.min(size, selected.length - index * size),
  );
  useEffect(() => {
    setSelected(initialSelection || []);
    setSnapshot(undefined);
    setReceipt(undefined);
    setStep(0);
  }, [project.projectId, version?.specVersionId, initialSelection]);
  useEffect(() => {
    const controller = new AbortController();
    labelingJson<typeof history>(
      `/projects/${encodeURIComponent(project.projectId)}/publication-groups`,
      { signal: controller.signal },
    )
      .then(setHistory)
      .catch(() => {});
    return () => controller.abort();
  }, [project.projectId]);
  useEffect(() => {
    if (!ai) return;
    const controller = new AbortController();
    ownerApi
      .agents(controller.signal, project.taskType)
      .then((data) => {
        setAgents(data.filter((agent) => agent.enabled));
        setChosenAgents((ids) =>
          ids.filter((id) =>
            data.some((agent) => agent.enabled && agent.agentId === id),
          ),
        );
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(describeLabelingError(e));
      });
    return () => controller.abort();
  }, [ai, project.taskType]);
  const execute = async (action: () => Promise<void>, operation = '操作') => {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (e) {
      const problem = e as { status?: number; code?: string; message?: string };
      if (
        operation === '联合发布' &&
        problem?.status === 404 &&
        problem?.code === 'RESOURCE_NOT_FOUND' &&
        problem?.message === '请求的资源不存在'
      ) {
        setError(
          `联合发布接口或所需资源未找到。请确认当前连接的标注服务已部署联合发布接口和 V28 数据库迁移；如已部署，请使用请求编号核查项目、模型及固定范围。${describeLabelingError(e)}`,
        );
      } else {
        setError(`${operation}失败：${describeLabelingError(e)}`);
      }
    } finally {
      setBusy(false);
    }
  };
  const freeze = () =>
    void execute(async () => {
      if (!version) return;
      const rows = await collectPages<LiveResult>(
        `/projects/${encodeURIComponent(project.projectId)}/results?specVersionId=${encodeURIComponent(version.specVersionId)}`,
      );
      const byId = new Map(rows.map((row) => [row.sampleId, row]));
      if (selected.some((id) => !byId.has(id)))
        throw new Error('部分样本已发生变化，请刷新后重新选择');
      const items = selected.map((sampleId) => {
        const row = byId.get(sampleId);
        if (!row) throw new Error('部分样本已发生变化，请刷新后重新选择');
        return { sampleId, resultId: null, reviewRevision: row.reviewRevision };
      });
      const body = {
        purpose: 'PUBLISH' as const,
        specVersionId: version.specVersionId,
        items,
      };
      const signature = JSON.stringify([project.projectId, body]);
      if (selectionAttempt.current?.signature !== signature)
        selectionAttempt.current = { signature, key: operationKey() };
      const scope = await ownerApi.select(
        project.projectId,
        body,
        selectionAttempt.current.key,
      );
      const fixed = await collectPages<SelectionItem>(
        `/result-selections/${encodeURIComponent(scope.selectionId)}/items`,
      );
      if (
        fixed.length !== selected.length ||
        fixed.some((item) => !selectedSet.has(item.sampleId))
      )
        throw new Error('固定范围与选择不一致，请重新选择');
      setFrozenAcceptedCount(
        selected.filter((id) => byId.get(id)?.reviewStatus === 'ACCEPTED')
          .length,
      );
      setFrozen(fixed);
      setSnapshot(scope);
      setStep(2);
    }, '固定发布范围');
  const publish = () =>
    void execute(async () => {
      if (!version || !snapshot) return;
      const body = {
        specVersionId: version.specVersionId,
        selectionId: snapshot.selectionId,
        human,
        packageSize: size,
        annotatorsPerSample: people,
        agentIds: ai ? chosenAgents : [],
        reopenAccepted: reopen,
        instructions,
      };
      const signature = JSON.stringify([project.projectId, body]);
      if (publishAttempt.current?.signature !== signature)
        publishAttempt.current = { signature, key: operationKey() };
      const result = await post<Receipt>(
        `/projects/${encodeURIComponent(project.projectId)}/publication-groups`,
        body,
        publishAttempt.current.key,
      );
      setReceipt(result);
      setHistory((values) => [
        {
          publicationGroupId: result.publicationGroupId,
          specVersionId: result.specVersionId,
          createdAt: new Date().toISOString(),
        },
        ...values,
      ]);
      onExecutions(
        result.executions.map((execution) => ({
          execution,
          samples: selectedSamples,
          version,
          model:
            agents.find((agent) => agent.agentId === execution.agentConfigId)
              ?.displayName || execution.modelVersion,
        })),
      );
      setStep(3);
      refresh();
    }, '联合发布');
  const reload = () =>
    void execute(async () => {
      if (!receipt || !version) return;
      const value = await labelingJson<Receipt>(
        `/projects/${encodeURIComponent(project.projectId)}/publication-groups/${encodeURIComponent(receipt.publicationGroupId)}`,
      );
      setReceipt({ ...receipt, ...value });
      onExecutions(
        value.executions.map((execution) => ({
          execution,
          samples: selectedSamples,
          version,
          model:
            agents.find((agent) => agent.agentId === execution.agentConfigId)
              ?.displayName || execution.modelVersion,
        })),
      );
    });
  const retry = (execution: GroupExecution) =>
    void execute(async () => {
      if (!receipt || !version) return;
      const fingerprint = `${execution.executionId}:${execution.revision}`;
      retryKeys.current[fingerprint] ||= operationKey();
      await post(
        `/ai-executions/${encodeURIComponent(execution.executionId)}/retries`,
        { baseRevision: execution.revision },
        retryKeys.current[fingerprint],
      );
      const value = await labelingJson<Receipt>(
        `/projects/${encodeURIComponent(project.projectId)}/publication-groups/${encodeURIComponent(receipt.publicationGroupId)}`,
      );
      setReceipt({ ...receipt, ...value });
      onExecutions(
        value.executions.map((item) => ({
          execution: item,
          samples: selectedSamples,
          version,
          model:
            agents.find((agent) => agent.agentId === item.agentConfigId)
              ?.displayName || item.modelVersion,
        })),
      );
    });
  const openHistory = (groupId: string) =>
    void execute(async () => {
      if (!version) return;
      const value = await labelingJson<Receipt>(
        `/projects/${encodeURIComponent(project.projectId)}/publication-groups/${encodeURIComponent(groupId)}`,
      );
      if (value.specVersionId !== version.specVersionId)
        throw new Error('请先选择该发布记录对应的规范版本');
      const modelList = await ownerApi.agents(undefined, project.taskType);
      const fixedSamples = (value.sampleIds || [])
        .map((id) => samples.find((sample) => sample.sampleId === id))
        .filter((sample): sample is NativeSample => !!sample);
      setAgents(modelList);
      setSelected(value.sampleIds || []);
      setReceipt(value);
      setStep(3);
      onExecutions(
        value.executions.map((execution) => ({
          execution,
          samples: fixedSamples,
          version,
          model:
            modelList.find((agent) => agent.agentId === execution.agentConfigId)
              ?.displayName || execution.modelVersion,
        })),
      );
    });
  const names = new Map(
    agents.map((agent) => [agent.agentId, agent.displayName]),
  );
  return (
    <div className={ui.publishFlow}>
      <div className={ui.publishHeading}>
        <div>
          <h2>发布任务</h2>
          {step > 0 && (
            <span>
              {selected.length} 个样本 · 规范 v{version?.versionNo || '—'}
              {human ? ' · 人工' : ''}
              {ai ? ' · AI' : ''}
            </span>
          )}
        </div>
        <Steps
          size="small"
          current={Math.min(step, 2)}
          onChange={(target) => {
            if (busy || step === 3 || target >= step || target > 1) return;
            selectionAttempt.current = undefined;
            setSnapshot(undefined);
            setError('');
            setStep(target);
          }}
          items={[
            { title: '选择样本', disabled: busy || step === 3 },
            { title: '配置任务', disabled: busy || step < 1 || step === 3 },
            { title: '确认发布', disabled: busy || step < 2 || step === 3 },
          ]}
        />
      </div>
      {error && <Alert type="error" showIcon message={error} />}
      {step === 0 && (
        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            {!version && <Alert type="warning" message="请先保存标注设置" />}
            <Space wrap>
              <span>本轮规范</span>
              <Select
                aria-label="选择本轮规范"
                value={version?.specVersionId}
                disabled={!specVersions.length}
                onChange={onVersionChange}
                options={specVersions.map((item) => ({
                  value: item.specVersionId,
                  label: `第 ${item.versionNo} 版`,
                }))}
              />
              <Checkbox
                checked={human}
                onChange={(e) => setHuman(e.target.checked)}
              >
                人工标注
              </Checkbox>
              <Checkbox checked={ai} onChange={(e) => setAi(e.target.checked)}>
                AI 预标注
              </Checkbox>
              <Checkbox
                checked={reopen}
                onChange={(e) => {
                  setReopen(e.target.checked);
                  if (!e.target.checked)
                    setSelected((ids) =>
                      ids.filter((id) => reviewMap.get(id) !== 'ACCEPTED'),
                    );
                }}
              >
                重新处理已确认样本
              </Checkbox>
            </Space>
            {history.filter(
              (item) => item.specVersionId === version?.specVersionId,
            ).length > 0 && (
              <Select
                aria-label="查看本规范发布记录"
                placeholder="查看本规范发布记录"
                style={{ minWidth: 220 }}
                value={undefined}
                onChange={openHistory}
                options={history
                  .filter(
                    (item) => item.specVersionId === version?.specVersionId,
                  )
                  .map((item) => ({
                    value: item.publicationGroupId,
                    label: item.createdAt,
                  }))}
              />
            )}
            <Space wrap>
              <Input.Search
                aria-label="搜索样本"
                placeholder="搜索样本名称或来源"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
              <Select
                aria-label="审核状态"
                value={status}
                onChange={setStatus}
                options={[
                  { value: 'ALL', label: '全部状态' },
                  { value: 'PENDING', label: '待处理' },
                  { value: 'NEEDS_REVISION', label: '需重标' },
                  { value: 'ACCEPTED', label: '已确认' },
                ]}
              />
            </Space>
            {selected.length > 10000 && (
              <Alert
                type="warning"
                showIcon
                message="一次最多发布 10000 个样本，请减少选择"
              />
            )}
            <Table<NativeSample>
              size="small"
              rowKey="sampleId"
              dataSource={filtered}
              scroll={{ x: 600 }}
              pagination={{
                pageSize,
                showSizeChanger: true,
                pageSizeOptions: [5, 10, 20, 50],
                onShowSizeChange: (_, value) => setPageSize(value),
                showTotal: (total) =>
                  `已选 ${selected.length} 个 · 共 ${total} 个样本 · 共 ${Math.ceil(total / pageSize)} 页`,
              }}
              rowSelection={{
                hideSelectAll: true,
                selectedRowKeys: selected,
                preserveSelectedRowKeys: true,
                columnTitle: (
                  <Checkbox
                    aria-label="全选筛选结果"
                    checked={all}
                    indeterminate={
                      !all && eligible.some((id) => selectedSet.has(id))
                    }
                    disabled={locked || !eligible.length}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...new Set([...selected, ...eligible])]
                          : selected.filter((id) => !eligible.includes(id)),
                      )
                    }
                  />
                ),
                onChange: (ids) => setSelected(ids.map(String)),
                getCheckboxProps: (item) => ({
                  disabled:
                    locked ||
                    (!reopen && reviewMap.get(item.sampleId) === 'ACCEPTED'),
                }),
              }}
              columns={[
                {
                  title: '样本',
                  render: (_, row) =>
                    row.metadata.displayName || row.sourcePath,
                },
                { title: '来源', render: (_, row) => row.sourcePath || '—' },
                {
                  title: '审核状态',
                  render: (_, row) =>
                    (
                      ({
                        ACCEPTED: '已确认',
                        NEEDS_REVISION: '需重标',
                      }) as Record<string, string>
                    )[reviewMap.get(row.sampleId) || ''] || '待处理',
                },
              ]}
            />
            <div className={ui.publishActions}>
              <Button
                type="primary"
                disabled={
                  locked ||
                  !version ||
                  !selected.length ||
                  selected.length > 10000 ||
                  (!human && !ai)
                }
                onClick={() => setStep(1)}
              >
                下一步：配置任务
              </Button>
            </div>
          </Space>
        </Card>
      )}
      {step === 1 && (
        <>
          <div className={ui.publishConfig}>
            {human && (
              <Card title="人工标注">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <label htmlFor="publish-package-size">
                    每包样本数{' '}
                    <InputNumber
                      id="publish-package-size"
                      min={1}
                      max={1000}
                      precision={0}
                      value={size}
                      onChange={(value) => setSize(value || 1)}
                    />
                  </label>
                  <label htmlFor="publish-annotator-count">
                    每个样本标注人数{' '}
                    <InputNumber
                      id="publish-annotator-count"
                      min={1}
                      max={10}
                      precision={0}
                      value={people}
                      onChange={(value) => setPeople(value || 1)}
                    />
                  </label>
                  <Alert
                    type="info"
                    message={`预计生成 ${packageCounts.length} 个任务包`}
                    description={`${packageCounts.join(' + ')} 个样本`}
                  />
                </Space>
              </Card>
            )}
            {ai && (
              <Card title="AI 预标注" extra={`已选 ${chosenAgents.length} 个`}>
                <Table<LiveAgent>
                  rowKey="agentId"
                  size="small"
                  pagination={false}
                  dataSource={agents}
                  rowSelection={{
                    selectedRowKeys: chosenAgents,
                    onChange: (keys) => setChosenAgents(keys.map(String)),
                  }}
                  columns={[
                    { title: '模型', dataIndex: 'displayName' },
                    { title: '版本', dataIndex: 'modelVersion' },
                    {
                      title: '状态',
                      render: () => <Tag color="green">可选</Tag>,
                    },
                  ]}
                  locale={{ emptyText: '当前任务类型没有已启用的模型' }}
                />
                <p>
                  同一批样本分别执行：{selected.length} 个样本 ×{' '}
                  {chosenAgents.length} 个模型 ={' '}
                  {selected.length * chosenAgents.length} 次样本推理
                </p>
                <Input.TextArea
                  value={instructions}
                  maxLength={500}
                  onChange={(e) => setInstructions(e.target.value)}
                  placeholder="任务说明（选填）"
                />
              </Card>
            )}
          </div>
          <div className={ui.publishActions}>
            <Button onClick={() => setStep(0)}>返回选样</Button>
            <Button
              type="primary"
              loading={busy}
              disabled={ai && !chosenAgents.length}
              onClick={freeze}
            >
              下一步：确认发布
            </Button>
          </div>
        </>
      )}
      {step === 2 && snapshot && (
        <>
          <Alert
            type="info"
            showIcon
            message={`${frozen.length} 个样本 · 规范 v${version?.versionNo} · 范围已固定`}
          />
          <div className={ui.publishConfig}>
            <Card title="发布预览" className={ui.publishPreview}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Table
                  size="small"
                  pagination={false}
                  rowKey="key"
                  dataSource={[
                    ...(human
                      ? [
                          {
                            key: 'human',
                            source: '人工标注',
                            scope: `${frozen.length} 个样本`,
                            content: `${packageCounts.length} 个任务包（${packageCounts.join(' + ')}）`,
                          },
                        ]
                      : []),
                    ...(ai
                      ? chosenAgents.map((id) => ({
                          key: id,
                          source: names.get(id) || id,
                          scope: `${frozen.length} 个样本`,
                          content: '1 个 AI 批次',
                        }))
                      : []),
                  ]}
                  columns={[
                    { title: '任务来源', dataIndex: 'source' },
                    { title: '执行范围', dataIndex: 'scope' },
                    { title: '发布内容', dataIndex: 'content' },
                  ]}
                />
                <Alert
                  type="success"
                  message="样本范围已固定，结果按来源保留"
                />
                <Alert
                  type={frozenAcceptedCount ? 'warning' : 'success'}
                  message={
                    frozenAcceptedCount
                      ? `包含 ${frozenAcceptedCount} 个已确认样本，发布后将取消其确认状态`
                      : '本轮未纳入已确认样本'
                  }
                />
                <Typography.Text type="secondary">
                  范围有效至 {snapshot.expiresAt}；发布时会再次检查样本状态
                </Typography.Text>
              </Space>
            </Card>
            <Card title="发布摘要">
              <p>人工任务包：{human ? packageCounts.length : 0} 个</p>
              <p>AI 执行批次：{ai ? chosenAgents.length : 0} 个</p>
              <p>
                预计样本推理：{ai ? frozen.length * chosenAgents.length : 0} 次
              </p>
              <Button type="primary" block loading={busy} onClick={publish}>
                确认发布
              </Button>
              <Button
                block
                style={{ marginTop: 8 }}
                disabled={busy}
                onClick={() => {
                  selectionAttempt.current = undefined;
                  setSnapshot(undefined);
                  setStep(1);
                }}
              >
                返回修改
              </Button>
            </Card>
          </div>
        </>
      )}
      {step === 3 && receipt && (
        <>
          <Alert
            type="success"
            showIcon
            message="任务已发布"
            description={`${receipt.sampleCount} 个样本 · 规范 v${version?.versionNo || '—'}`}
          />
          <Card
            title="本轮任务"
            extra={
              <Space>
                <Button onClick={reload} loading={busy}>
                  刷新状态
                </Button>
                <Button
                  onClick={() => {
                    setReceipt(undefined);
                    setSnapshot(undefined);
                    setSelected([]);
                    setStep(0);
                  }}
                >
                  发布新任务
                </Button>
              </Space>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }}>
              {receipt.humanPlan && (
                <div>
                  人工标注 · {receipt.humanPlan.packages.length} 个任务包 ·{' '}
                  {receipt.humanStatus || '已发布'}
                  <details>
                    <summary>查看任务包</summary>
                    {receipt.humanPlan.packages.map((item, index) => (
                      <p key={item.packageId}>
                        第 {index + 1} 包 · {item.sampleCount} 个样本 ·{' '}
                        {item.packageId}
                      </p>
                    ))}
                  </details>
                </div>
              )}
              {receipt.executions.map((execution) => (
                <div key={execution.executionId}>
                  <Space wrap>
                    <strong>
                      {names.get(execution.agentConfigId) ||
                        execution.modelVersion}
                    </strong>
                    <Tag>
                      {(
                        {
                          QUEUED: '排队中',
                          RUNNING: '执行中',
                          SUCCEEDED: '已完成',
                          PARTIAL: '部分完成',
                          FAILED: '失败',
                          CANCELLED: '已取消',
                        } as Record<string, string>
                      )[execution.status] || execution.status}
                    </Tag>
                    <span>
                      {execution.successCount} / {execution.sampleCount} 已完成
                    </span>
                    <Button
                      type="link"
                      onClick={() =>
                        version &&
                        onOpenExecution(
                          execution,
                          selectedSamples,
                          version,
                          names.get(execution.agentConfigId) ||
                            execution.modelVersion,
                        )
                      }
                    >
                      查看进度与结果
                    </Button>
                    {['PARTIAL', 'FAILED', 'CANCELLED'].includes(
                      execution.status,
                    ) && (
                      <Button disabled={busy} onClick={() => retry(execution)}>
                        重试失败样本
                      </Button>
                    )}
                  </Space>
                </div>
              ))}
              <Typography.Text type="secondary">
                候选结果生成后可在核查与重标中按来源查看
              </Typography.Text>
            </Space>
          </Card>
        </>
      )}
    </div>
  );
}
