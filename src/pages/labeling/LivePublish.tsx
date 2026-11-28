import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { operationKey, type SpecVersion } from '@/services/labeling';
import {
  assertChosen,
  type LiveResult,
  ownerApi,
  selectionItem,
} from '@/services/labelingOwner';
import { type Plan, tasks } from '@/services/labelingTasks';
import ProjectHistory from './ProjectHistory';
import ScopeSummary from './ScopeSummary';
import useOwnerAction from './useOwnerAction';
import ui from './workspaceUi.less';

export default function LivePublish({
  projectId,
  spec,
  selected,
  onChanged,
  onBusyChange,
  writeBlocked,
}: {
  projectId: string;
  spec: SpecVersion;
  selected: LiveResult[];
  onChanged: () => void;
  onBusyChange?: (busy: boolean) => void;
  writeBlocked?: string;
}) {
  const { modal } = App.useApp(),
    action = useOwnerAction(onBusyChange, writeBlocked);
  const [size, setSize] = useState(50),
    [instructions, setInstructions] = useState(''),
    [reference, setReference] = useState(false);
  const [executionMode, setExecutionMode] = useState<'HUMAN_POOL' | 'AI_AGENT' | 'MIXED'>('HUMAN_POOL'),
    [people, setPeople] = useState(1),
    [agents, setAgents] = useState<import('@/services/labelingOwner').LiveAgent[]>([]),
    [agentIds, setAgentIds] = useState<string[]>([]);
  const [plan, setPlan] = useState<Plan>();
  const [receipt, setReceipt] = useState<Plan>();
  const context = JSON.stringify([
    selected.map(selectionItem),
    spec.specVersionId,
    size,
    instructions,
    reference,
    executionMode,
    people,
    agentIds,
  ]);
  const contextRef = useRef(context);
  contextRef.current = context;
  useEffect(
    () => setPlan(undefined),
    [
      JSON.stringify(selected.map(selectionItem)),
      spec.specVersionId,
      size,
      instructions,
      reference,
      executionMode,
      people,
      agentIds,
    ],
  );
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi.agents(abort.signal, spec.taskType).then((value) => {
      if (!abort.signal.aborted) setAgents(value.filter((agent) => agent.enabled));
    }).catch(() => {
      if (!abort.signal.aborted) setAgents([]);
    });
    return () => abort.abort();
  }, [projectId, spec.taskType]);
  async function preview() {
    const current = context;
    const result = await action.run(
      'sample-plan',
      { selected, spec: spec.specVersionId, size, reference, instructions, executionMode, people, agentIds },
      async (key, prepare) => {
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
        return ownerApi.planSelection(
          projectId,
          {
            specVersionId: spec.specVersionId,
            packageSize: size,
            annotatorsPerSample: executionMode === 'AI_AGENT' ? 0 : people,
            executionMode,
            aiAgentIds: executionMode === 'HUMAN_POOL' ? [] : agentIds,
            annotatorRoster: [
              ...(executionMode !== 'AI_AGENT'
                ? [{ providerId: 'human-pool', providerVersion: '1.0.0', count: people, agentIds: [] }]
                : []),
              ...(executionMode !== 'HUMAN_POOL'
                ? [{ providerId: 'ai-agent-worker', providerVersion: '1.0.0', count: agentIds.length, agentIds }]
                : []),
            ],
            selectionId: selection.selectionId,
            referenceMode: reference ? 'SELECTED' : 'NONE',
            reopenAccepted: true,
            instructions,
          },
          key,
        );
      },
      '已生成真实分包预览',
    );
    if (result && current === contextRef.current) setPlan(result);
  }
  function publish() {
    if (!plan) return;
    const captured = plan;
    modal.confirm({
      title: `发布 ${captured.sampleCount} 个样本？`,
      content:
        '按预览的固定成员和人工/AI配置发布。已确认样本再次发布后会撤销当前确认，历史记录和既有成果快照保留。',
      onOk: async () => {
        const result = await action.run(
          `publish:${captured.planId}`,
          { selectionHash: captured.selectionHash },
          (key) => tasks.publish(captured, key),
          '任务已发布，执行轮次已建立',
        );
        if (result) {
          setReceipt(result);
          setPlan(undefined);
          onChanged();
        }
      },
    });
  }
  return (
    <div className={ui.twoColumns}>
      <ScopeSummary
        projectId={projectId}
        spec={spec}
        selected={selected}
        disabled={action.busy}
      />
      <Card title="配置重新标注任务">
        <details>
          <summary>恢复服务器分包计划</summary>
          <ProjectHistory
            projectId={projectId}
            kind="package-plans"
            onOpen={(row) => {
              if (row.planId)
                void action.run(
                  `restore-plan:${row.planId}`,
                  {},
                  async () => {
                    const value = await tasks.getPlan(row.planId || '');
                    if (value.projectId !== projectId)
                      throw new Error('计划不属于当前项目');
                    if (value.status === 'PUBLISHED') setReceipt(value);
                    else if (value.specVersionId !== spec.specVersionId)
                      throw new Error('请切换到该计划的规范版本再恢复');
                    else setPlan(value);
                    return true;
                  },
                  '计划已读取',
                );
            }}
          />
        </details>
        <Alert
          type="info"
          message={`当前明确勾选 ${selected.length} 个样本；请先在“结果与审核”中选择范围。`}
          description="在发布前选择纯人工、纯 AI 或人工 + AI，并设置本轮每个样本的执行人数；重新发布会撤销所选样本当前的审核确认，历史结果保留。"
        />
        {action.error && <Alert type="error" message={action.error} />}
        <div hidden={!selected.length}>
          <Space direction="vertical" style={{ marginTop: 14, width: '100%' }}>
            <Radio.Group
              value={executionMode}
              disabled={action.busy || !!plan}
              onChange={(event) => setExecutionMode(event.target.value)}
            >
              <Radio value="HUMAN_POOL">纯人工</Radio>
              <Radio value="AI_AGENT">纯 AI</Radio>
              <Radio value="MIXED">人工 + AI</Radio>
            </Radio.Group>
            <span>每包样本数</span>
            <InputNumber
              aria-label="再次发布每包样本数"
              min={1}
              max={500}
              value={size}
              disabled={action.busy}
              onChange={(value) => setSize(value || 1)}
            />
            {(executionMode === 'HUMAN_POOL' || executionMode === 'MIXED') && (
              <label>
                每个样本的人工标注人数{' '}
                <InputNumber min={1} max={10} value={people} disabled={action.busy || !!plan}
                  onChange={(value) => setPeople(value || 1)} />
              </label>
            )}
            {(executionMode === 'AI_AGENT' || executionMode === 'MIXED') && (
              <Select
                aria-label="选择本轮 AI 标注员"
                mode="multiple"
                maxCount={10}
                value={agentIds}
                disabled={action.busy || !!plan}
                placeholder="选择 AI 标注员（最多 10 个）"
                onChange={setAgentIds}
                options={agents.map((agent) => ({ value: agent.agentId, label: `${agent.displayName} · ${agent.modelVersion}` }))}
              />
            )}
            <Checkbox
              checked={reference}
              disabled={action.busy}
              onChange={(e) => setReference(e.target.checked)}
            >
              附带所选答案作为参考
            </Checkbox>
            <Input
              aria-label="再次发布补充说明"
              placeholder="补充说明（可选）"
              maxLength={500}
              value={instructions}
              disabled={action.busy}
              onChange={(e) => setInstructions(e.target.value)}
              style={{ width: '100%' }}
            />
            <Button
              type="primary"
              loading={action.busy}
              disabled={!selected.length || (executionMode !== 'HUMAN_POOL' && !agentIds.length)}
              onClick={() => void preview()}
            >
              预览分包
            </Button>
          </Space>
        </div>
        {plan && (
          <>
            <p>
              当前预览：{plan.executionMode === 'AI_AGENT' ? '纯 AI' : plan.executionMode === 'MIXED' ? '人工 + AI' : '纯人工'}
              {' · '}{plan.sampleCount} 个样本 · {plan.packages.length} 个轮次 · 工作量{' '}
              {plan.totalWorkload} 次执行
            </p>
            <p>
              {plan.executionMode !== 'AI_AGENT' && `每个样本 ${plan.annotatorsPerSample ?? 1} 名人工作业`}
              {plan.executionMode === 'MIXED' && '；'}
              {plan.executionMode !== 'HUMAN_POOL' && `每个样本 ${plan.aiAgentIds?.length ?? 0} 个 AI 标注员`}
            </p>
            <p>各包样本数：{plan.packageSampleCounts.join('、')}</p>
            <Table
              rowKey="roundId"
              dataSource={plan.packages}
              pagination={false}
              scroll={{ x: 600 }}
              columns={[
                { title: '包序号', dataIndex: 'sequenceNo' },
                { title: '样本数', dataIndex: 'sampleCount' },
                { title: '每样本人工人数', dataIndex: 'requiredCount' },
              ]}
            />
            <Button
              type="primary"
              style={{ marginTop: 16 }}
              disabled={plan.status === 'PUBLISHED' || action.busy}
              onClick={publish}
            >
              确认发布此预览
            </Button>
          </>
        )}
        {receipt && (
          <Card
            size="small"
            title="上次重新标注任务已发布"
            style={{ marginTop: 16 }}
          >
            <p>
              已发布 {receipt.sampleCount} 个样本，共{' '}
              {receipt.packageSampleCounts.length}{' '}
              个包。这是上次操作的回执，不代表新的选择已发布。
            </p>
            <Table
              rowKey="packageId"
              dataSource={receipt.packages}
              pagination={false}
              columns={[
                { title: '包序号', dataIndex: 'sequenceNo' },
                { title: '样本数', dataIndex: 'sampleCount' },
              ]}
            />
            <details>
              <summary>查看包和轮次编号</summary>
              <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {JSON.stringify(receipt.packages, null, 2)}
              </pre>
            </details>
          </Card>
        )}
      </Card>
    </div>
  );
}
