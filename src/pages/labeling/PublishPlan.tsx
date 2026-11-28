import { Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Form,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import type { Plan } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import { type LiveAgent, ownerApi } from '@/services/labelingOwner';
import ui from './workspaceUi.less';

export default function PublishPlan({
  projectId,
  batchId,
  taskType,
  specVersionId,
  writable,
  onPublished,
}: {
  projectId: string;
  batchId: string;
  taskType: string;
  specVersionId?: string;
  writable: boolean;
  onPublished: () => void;
}) {
  const [plan, setPlan] = useState<Plan>();
  const [previewSpecId, setPreviewSpecId] = useState<string>();
  const busyRef = useRef(false);
  const draftSpecChanged =
    plan?.status === 'DRAFT' && previewSpecId !== specVersionId;
  const incompatibleDraft =
    plan?.status === 'DRAFT' && !plan.executionMode && plan.totalWorkload !== plan.sampleCount;
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [executionMode, setExecutionMode] = useState<'HUMAN_POOL' | 'AI_AGENT' | 'MIXED'>('HUMAN_POOL');
  const [agents, setAgents] = useState<LiveAgent[]>([]);
  const [aiAgentIds, setAiAgentIds] = useState<string[]>([]);
  const [form] = Form.useForm<{
    packageSize: number;
    annotatorsPerSample: number;
  }>();
  const attempt = useRef<{ payload: string; key: string } | undefined>(
    undefined,
  );
  const alive = useRef(true);
  const bookmark = `labeling:plan:${projectId}:${batchId}`;
  useEffect(() => {
    const abort = new AbortController();
    void ownerApi.agents(abort.signal, taskType).then((value) => {
      if (!abort.signal.aborted) setAgents(value.filter((agent) => agent.enabled));
    }).catch((e) => {
      if (!abort.signal.aborted) setError(describeLabelingError(e));
    });
    return () => abort.abort();
  }, [projectId, taskType]);
  useEffect(() => {
    alive.current = true;
    const abort = new AbortController();
    let id: string | null = null;
    try {
      id = localStorage.getItem(bookmark);
    } catch {
      /* Optional convenience link. */
    }
    if (id) {
      setBusy(true);
      tasks
        .getPlan(id, abort.signal)
        .then((value) => {
          if (!abort.signal.aborted) {
            if (value.projectId !== projectId)
              throw new Error('计划不属于当前项目');
            setPlan(value);
            setExecutionMode(value.executionMode || 'HUMAN_POOL');
            setAiAgentIds(value.aiAgentIds || []);
            form.setFieldsValue({
              annotatorsPerSample: value.annotatorsPerSample ?? 1,
            });
            if (value.status === 'PUBLISHED') onPublished();
            try {
              setPreviewSpecId(value.specVersionId);
            } catch {
              /* Re-preview when version is unknown. */
            }
          }
        })
        .catch((e: unknown) => {
          if (!abort.signal.aborted) setError(describeLabelingError(e));
        })
        .finally(() => {
          if (!abort.signal.aborted) setBusy(false);
        });
    }
    return () => {
      alive.current = false;
      abort.abort();
    };
  }, [bookmark, form]);

  async function preview() {
    if (!specVersionId || busyRef.current || busy || !writable) return;
    busyRef.current = true;
    const values = await form.validateFields().catch(() => null);
    if (!values) {
      busyRef.current = false;
      return;
    }
    if (executionMode !== 'HUMAN_POOL' && !aiAgentIds.length) {
      setError('请选择至少一个 AI 标注员。');
      busyRef.current = false;
      return;
    }
    const body = {
      ...values,
      annotatorsPerSample: executionMode === 'AI_AGENT' ? 0 : values.annotatorsPerSample,
      executionMode,
      aiAgentIds: executionMode === 'HUMAN_POOL' ? [] : aiAgentIds,
      annotatorRoster: [
        ...(executionMode !== 'AI_AGENT'
          ? [{ providerId: 'human-pool', providerVersion: '1.0.0', count: values.annotatorsPerSample, agentIds: [] }]
          : []),
        ...(executionMode !== 'HUMAN_POOL'
          ? [{ providerId: 'ai-agent-worker', providerVersion: '1.0.0', count: aiAgentIds.length, agentIds: aiAgentIds }]
          : []),
      ],
      batchId,
      specVersionId,
    };
    const payload = JSON.stringify(body);
    if (attempt.current?.payload !== payload)
      attempt.current = { payload, key: operationKey() };
    setBusy(true);
    setError('');
    try {
      const value = await tasks.plan(projectId, body, attempt.current.key);
      if (!alive.current) return;
      setPlan(value);
      setPreviewSpecId(specVersionId);
      try {
        localStorage.setItem(bookmark, value.planId);
        localStorage.setItem(`${bookmark}:spec`, specVersionId);
      } catch {
        /* Plan remains on this page. */
      }
    } catch (e) {
      if (alive.current) setError(describeLabelingError(e));
    } finally {
      busyRef.current = false;
      if (alive.current) setBusy(false);
    }
  }
  async function publish() {
    if (
      !plan ||
      busyRef.current ||
      busy ||
      !writable ||
      draftSpecChanged ||
      incompatibleDraft
    )
      return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    try {
      const value = await tasks.publish(plan, `publish-${plan.planId}`);
      if (!alive.current) return;
      setPlan(value);
      onPublished();
      try {
        const current = await tasks.getPlan(value.planId);
        if (alive.current) setPlan(current);
      } catch (e) {
        if (alive.current)
          setError(
            `任务已发布，读取最新包列表失败，可刷新页面核对：${describeLabelingError(e)}`,
          );
      }
    } catch (e) {
      if (alive.current) setError(describeLabelingError(e));
    } finally {
      busyRef.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return (
    <Card title="3. 发布标注任务包" style={{ marginBottom: 20 }}>
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          style={{ marginBottom: 16 }}
        />
      )}
      {!specVersionId && (
        <Alert type="info" message="请先保存分类规范，再预览任务包。" />
      )}
      <Typography.Paragraph type="secondary">
        任务包使用生成预览时选择的规范。更换规范后请重新预览；已发布任务包继续使用原冻结版本。
      </Typography.Paragraph>
      <Alert
        type="info"
        message="发布前选择纯人工、纯 AI 或人工 + AI，并设置每个样本的执行人数。"
      />
      <Radio.Group
        value={executionMode}
        disabled={busy || !!plan}
        onChange={(event) => {
          setExecutionMode(event.target.value);
          setError('');
        }}
      >
        <Radio value="HUMAN_POOL">纯人工</Radio>
        <Radio value="AI_AGENT">纯 AI</Radio>
        <Radio value="MIXED">人工 + AI</Radio>
      </Radio.Group>
      <div className={ui.twoColumns}>
        <section className={ui.section}>
          <h3>打包参数</h3>
          {plan && <p>参数已随预览固定。需要更改时，选择“调整打包参数”。</p>}
          {!plan && (
            <Form
              form={form}
              layout="vertical"
              initialValues={{ packageSize: 10, annotatorsPerSample: 1 }}
              onFinish={() => void preview()}
            >
              <Form.Item
                name="packageSize"
                label="每包样本数"
                rules={[{ required: true }]}
              >
                <InputNumber
                  min={1}
                  max={1000}
                  precision={0}
                  disabled={busy || !writable}
                />
              </Form.Item>
              {executionMode !== 'AI_AGENT' && <Form.Item
                name="annotatorsPerSample"
                label="每个样本的人工标注人数"
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={10} precision={0} disabled={busy || !writable} />
              </Form.Item>}
              {(executionMode === 'AI_AGENT' || executionMode === 'MIXED') && (
                <Form.Item label="本轮 AI 标注员">
                  <Select
                    mode="multiple"
                    maxCount={10}
                    value={aiAgentIds}
                    disabled={busy || !!plan}
                    placeholder="选择 AI 标注员（最多 10 个）"
                    onChange={setAiAgentIds}
                    options={agents.map((agent) => ({
                      value: agent.agentId,
                      label: `${agent.displayName} · ${agent.modelVersion}`,
                    }))}
                  />
                </Form.Item>
              )}
              <Button
                htmlType="submit"
                loading={busy}
                disabled={!specVersionId || !writable || busy}
              >
                生成打包预览
              </Button>
            </Form>
          )}
        </section>
        <section className={ui.section}>
          <h3>发布预览</h3>
          {!plan && (
            <p className={ui.emptyNote}>
              设置每包样本数，生成预览后核对样本、包数与工作量，再确认发布。
            </p>
          )}
          {incompatibleDraft && (
            <Alert
              type="warning"
              message="本机旧预览不是本轮每包1人的规则，请先调整参数重新生成。"
            />
          )}
          {draftSpecChanged && (
            <Alert
              type="warning"
              showIcon
              message="规范已更换或旧预览规范未知，请调整打包参数并重新生成预览后发布。"
            />
          )}
          {plan && (
            <>
              <Typography.Paragraph style={{ marginTop: 20 }}>
                本轮方式：{plan.executionMode === 'AI_AGENT' ? '纯 AI' : plan.executionMode === 'MIXED' ? '人工 + AI' : '纯人工'}；
                共 {plan.sampleCount} 个样本，{plan.packages.length}{' '}
                个任务轮次，总工作量 {plan.totalWorkload} 次执行。各轮样本数：
                {plan.packageSampleCounts.join(' / ')}。
              </Typography.Paragraph>
              <Typography.Paragraph type="secondary">
                {plan.executionMode !== 'AI_AGENT' && `每个样本 ${plan.annotatorsPerSample ?? 1} 名人工作业`}
                {plan.executionMode === 'MIXED' && '；'}
                {plan.executionMode !== 'HUMAN_POOL' && `每个样本由 ${plan.aiAgentIds?.length ?? 0} 个 AI 标注员处理`}
              </Typography.Paragraph>
              {!!plan.aiExecutions?.length && (
                <Typography.Paragraph type="secondary">
                  已创建 {plan.aiExecutions.length} 个 AI 执行单元。
                </Typography.Paragraph>
              )}
              {plan.status === 'PUBLISHED' ? (
                <>
                  <Alert
                    type="success"
                    showIcon
                    message="任务已发布，标注员和分类规范已冻结"
                  />
                  <Table
                    size="small"
                    pagination={false}
                    rowKey="roundId"
                    scroll={{ x: 450 }}
                    dataSource={plan.packages}
                    columns={[
                      { title: '轮次序号', dataIndex: 'sequenceNo' },
                      { title: '样本数', dataIndex: 'sampleCount' },
                      { title: '每样本人工人数', dataIndex: 'requiredCount' },
                    ]}
                  />
                  <Link to="/labeling/tasks">前往任务广场领取</Link>
                </>
              ) : (
                <Space>
                  <Button
                    type="primary"
                    loading={busy}
                    disabled={
                      !writable || incompatibleDraft || draftSpecChanged
                    }
                    onClick={() => void publish()}
                  >
                    确认发布此预览
                  </Button>
                  <Button
                    disabled={busy || !writable}
                    onClick={() => {
                      setPlan(undefined);
                      try {
                        localStorage.removeItem(bookmark);
                        localStorage.removeItem(`${bookmark}:spec`);
                      } catch {
                        /* Optional bookmark. */
                      }
                    }}
                  >
                    调整打包参数
                  </Button>
                  <Typography.Text type="secondary">
                    发布后不可修改包成员、规范和人数。
                  </Typography.Text>
                </Space>
              )}
            </>
          )}
        </section>
      </div>
    </Card>
  );
}
