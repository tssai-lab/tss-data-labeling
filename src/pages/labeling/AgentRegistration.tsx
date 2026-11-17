import { Alert, Button, Card, Checkbox, Input, Select, Space } from 'antd';
import { useState } from 'react';
import { ownerApi } from '@/services/labelingOwner';
import useOwnerAction from './useOwnerAction';

export default function AgentRegistration() {
  const action = useOwnerAction();
  const [taskType, setTaskType] = useState<
    'IMAGE_CLASSIFICATION' | 'TIMESERIES_ANNOTATION'
  >('IMAGE_CLASSIFICATION');
  const [name, setName] = useState(''),
    [model, setModel] = useState(''),
    [workerKey, setWorkerKey] = useState('');
  const [supportsReference, setReference] = useState(false),
    [description, setDescription] = useState('');
  const [agent, setAgent] = useState<{ agentId: string; actorId: string }>();
  async function register() {
    const body = {
      taskType,
      displayName: name.trim(),
      modelVersion: model.trim(),
      supportsReference,
      confidenceDescription: description.trim(),
      workerKey,
    };
    const value = await action.run(
      'register-agent',
      body,
      (key) => ownerApi.registerAgent(body, key),
      'Agent 已注册，实际处理需由对应 Worker 接入',
    );
    if (value) {
      setAgent(value);
      setWorkerKey('');
    }
  }
  return (
    <Card title="注册标注 Agent" style={{ marginTop: 16 }}>
      <Alert
        type="info"
        message="仅标注管理员可注册 Agent"
        description="填写已部署 Worker 使用的密钥；页面不保存密钥到浏览器存储，也不从浏览器调用 Worker 内部接口。注册本身不会运行模型。"
      />
      {action.error && <Alert type="error" message={action.error} />}
      <Space wrap style={{ marginTop: 16 }}>
        <Select
          aria-label="Agent 任务类型"
          value={taskType}
          onChange={setTaskType}
          disabled={action.busy}
          options={[
            { value: 'IMAGE_CLASSIFICATION', label: '图片分类' },
            { value: 'TIMESERIES_ANNOTATION', label: '站点日能耗' },
          ]}
        />
        <Input
          aria-label="Agent 显示名称"
          placeholder="显示名称"
          maxLength={100}
          value={name}
          disabled={action.busy}
          onChange={(e) => setName(e.target.value)}
        />
        <Input
          aria-label="Agent 模型版本"
          placeholder="模型版本"
          maxLength={200}
          value={model}
          disabled={action.busy}
          onChange={(e) => setModel(e.target.value)}
        />
        <Input.Password
          aria-label="Agent Worker 密钥"
          placeholder="Worker 密钥"
          autoComplete="new-password"
          value={workerKey}
          disabled={action.busy}
          onChange={(e) => setWorkerKey(e.target.value)}
        />
        <Checkbox
          checked={supportsReference}
          disabled={action.busy}
          onChange={(e) => setReference(e.target.checked)}
        >
          支持固定候选参考
        </Checkbox>
        <Input.TextArea
          aria-label="Agent 置信度说明"
          placeholder="置信度含义，无法提供可信分数时说明使用未知值"
          maxLength={1000}
          value={description}
          disabled={action.busy}
          onChange={(e) => setDescription(e.target.value)}
        />
        <Button
          type="primary"
          loading={action.busy}
          disabled={!name.trim() || !model.trim() || !workerKey}
          onClick={() => void register()}
        >
          注册 Agent
        </Button>
      </Space>
      {agent && (
        <p>
          已注册 Agent：{agent.agentId} · Worker 身份：{agent.actorId}
        </p>
      )}
    </Card>
  );
}
