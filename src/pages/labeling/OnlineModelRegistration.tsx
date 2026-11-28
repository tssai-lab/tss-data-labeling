import { Alert, Button, Card, Form, Input, Select, Space, Table } from 'antd';
import { useEffect, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  operationKey,
  post,
} from '@/services/labeling';

type ConnectionSummary = {
  connectionId: string;
  displayName: string;
  modelVersion: string;
};

type ConnectionInput = {
  displayName: string;
  protocol: 'openai-chat' | 'prediction-v1';
  endpoint: string;
  model: string;
  apiKeyEnv?: string;
  enableThinking?: boolean;
};

export default function OnlineModelRegistration() {
  const [form] = Form.useForm<ConnectionInput>();
  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void labelingJson<ConnectionSummary[]>('/model-connections', {
      signal: controller.signal,
    })
      .then(setConnections)
      .catch((failure) => {
        if (!controller.signal.aborted)
          setError(describeLabelingError(failure));
      });
    return () => controller.abort();
  }, []);

  async function save(values: ConnectionInput) {
    setBusy(true);
    setError('');
    try {
      const created = await post<ConnectionSummary>(
        '/admin/model-connections',
        {
          ...values,
          apiKeyEnv: values.apiKeyEnv?.trim() || null,
          enableThinking:
            values.protocol === 'openai-chat' ? values.enableThinking : null,
        },
        operationKey(),
      );
      setConnections((list) => [...list, created]);
      form.resetFields();
    } catch (failure) {
      setError(describeLabelingError(failure));
    } finally {
      setBusy(false);
    }
  }

  async function disable(id: string) {
    setBusy(true);
    setError('');
    try {
      await labelingJson(`/admin/model-connections/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      });
      setConnections((list) => list.filter((item) => item.connectionId !== id));
    } catch (failure) {
      setError(describeLabelingError(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="在线模型连接" style={{ marginTop: 16 }}>
      {error && <Alert type="error" message={error} showIcon />}
      <Form
        form={form}
        layout="vertical"
        initialValues={{ protocol: 'openai-chat' }}
        onFinish={save}
      >
        <Space wrap align="start">
          <Form.Item
            name="displayName"
            label="显示名称"
            rules={[{ required: true, whitespace: true }]}
          >
            <Input maxLength={200} />
          </Form.Item>
          <Form.Item
            name="protocol"
            label="接口协议"
            rules={[{ required: true }]}
          >
            <Select
              style={{ minWidth: 188 }}
              options={[
                { value: 'openai-chat', label: 'OpenAI 兼容 Chat' },
                { value: 'prediction-v1', label: '自定义预测接口' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="model"
            label="模型 ID"
            rules={[{ required: true, whitespace: true }]}
          >
            <Input maxLength={120} placeholder="qwen3.6-plus" />
          </Form.Item>
          <Form.Item
            name="endpoint"
            label="完整 HTTPS 预测地址"
            rules={[{ required: true }]}
          >
            <Input placeholder="https://…/chat/completions" />
          </Form.Item>
          <Form.Item name="apiKeyEnv" label="Worker 密钥环境变量名">
            <Input placeholder="DASHSCOPE_API_KEY" />
          </Form.Item>
          <Form.Item name="enableThinking" label="思考模式">
            <Select
              allowClear
              style={{ minWidth: 135 }}
              options={[
                { value: false, label: '关闭' },
                { value: true, label: '开启' },
              ]}
            />
          </Form.Item>
        </Space>
        <Button type="primary" htmlType="submit" loading={busy}>
          新增模型连接
        </Button>
      </Form>
      <Table<ConnectionSummary>
        style={{ marginTop: 16 }}
        rowKey="connectionId"
        size="small"
        dataSource={connections}
        columns={[
          { title: '模型', dataIndex: 'displayName' },
          { title: '模型 ID', dataIndex: 'modelVersion' },
          {
            title: '操作',
            render: (_, row) => (
              <Button
                disabled={busy}
                onClick={() => void disable(row.connectionId)}
              >
                停用
              </Button>
            ),
          },
        ]}
      />
    </Card>
  );
}
