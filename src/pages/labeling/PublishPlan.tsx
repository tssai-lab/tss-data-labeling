import { Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Form,
  InputNumber,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { describeLabelingError, operationKey } from '@/services/labeling';
import type { Plan } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';

export default function PublishPlan({
  projectId,
  batchId,
  specVersionId,
  writable,
  onPublished,
}: {
  projectId: string;
  batchId: string;
  specVersionId?: string;
  writable: boolean;
  onPublished: () => void;
}) {
  const [plan, setPlan] = useState<Plan>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
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
          if (!abort.signal.aborted) setPlan(value);
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
  }, [bookmark]);

  async function preview() {
    if (!specVersionId || busy) return;
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    const body = { ...values, batchId, specVersionId };
    const payload = JSON.stringify(body);
    if (attempt.current?.payload !== payload)
      attempt.current = { payload, key: operationKey() };
    setBusy(true);
    setError('');
    try {
      const value = await tasks.plan(projectId, body, attempt.current.key);
      if (!alive.current) return;
      setPlan(value);
      try {
        localStorage.setItem(bookmark, value.planId);
      } catch {
        /* Plan remains on this page. */
      }
    } catch (e) {
      if (alive.current) setError(describeLabelingError(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }
  async function publish() {
    if (!plan || busy) return;
    setBusy(true);
    setError('');
    try {
      const value = await tasks.publish(plan, `publish-${plan.planId}`);
      const current = await tasks.getPlan(value.planId);
      if (!alive.current) return;
      setPlan(current);
      onPublished();
    } catch (e) {
      if (alive.current) setError(describeLabelingError(e));
    } finally {
      if (alive.current) setBusy(false);
    }
  }
  return (
    <Card title="发布图片分类任务包" style={{ marginBottom: 20 }}>
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
        当前选择规范：{specVersionId || '尚未选择'}
        。已生成的预览及已发布任务包保留创建时的冻结规范；换版本请先调整打包参数重新预览。
      </Typography.Paragraph>
      {!plan && (
        <Form
          form={form}
          layout="inline"
          initialValues={{ packageSize: 10, annotatorsPerSample: 1 }}
          onFinish={() => void preview()}
        >
          <Form.Item
            name="packageSize"
            label="每包图片数"
            rules={[{ required: true }]}
          >
            <InputNumber
              min={1}
              max={1000}
              precision={0}
              disabled={busy || !writable}
            />
          </Form.Item>
          <Form.Item
            name="annotatorsPerSample"
            label="每包标注人数"
            rules={[{ required: true }]}
          >
            <InputNumber
              min={1}
              max={10}
              precision={0}
              disabled={busy || !writable}
            />
          </Form.Item>
          <Button
            htmlType="submit"
            loading={busy}
            disabled={!specVersionId || !writable || busy}
          >
            生成打包预览
          </Button>
        </Form>
      )}
      {plan && (
        <>
          <Typography.Paragraph style={{ marginTop: 20 }}>
            共 {plan.sampleCount} 张图片，{plan.packageSampleCounts.length}{' '}
            个任务包，总工作量 {plan.totalWorkload} 次分类。各包图片数：
            {plan.packageSampleCounts.join(' / ')}。
          </Typography.Paragraph>
          {plan.status === 'PUBLISHED' ? (
            <>
              <Alert
                type="success"
                showIcon
                message="任务包已发布，包成员和分类规范已冻结"
              />
              <Table
                size="small"
                pagination={false}
                rowKey="packageId"
                scroll={{ x: 450 }}
                dataSource={plan.packages}
                columns={[
                  { title: '包序号', dataIndex: 'sequenceNo' },
                  { title: '图片数', dataIndex: 'sampleCount' },
                  { title: '标注人数', dataIndex: 'requiredCount' },
                ]}
              />
              <Link to="/labeling/tasks">前往任务广场领取</Link>
            </>
          ) : (
            <Space>
              <Button
                type="primary"
                loading={busy}
                disabled={!writable}
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
    </Card>
  );
}
