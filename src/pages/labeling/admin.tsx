import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import { describeLabelingError } from '@/services/labeling';
import { type LabelingUser, labelingAdmin } from '@/services/labelingAdmin';
import { type LabelingIdentity, tasks } from '@/services/labelingTasks';
import WorkspaceLayout from './WorkspaceLayout';

export default function Users() {
  const [me, setMe] = useState<LabelingIdentity>();
  const [rows, setRows] = useState<LabelingUser[]>([]);
  const [offset, setOffset] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [target, setTarget] = useState<LabelingUser>();
  const [retry, setRetry] = useState(0);
  const [form] = Form.useForm();
  useEffect(() => {
    const abort = new AbortController();
    setBusy(true);
    setError('');
    setRows([]);
    setMe(undefined);
    void tasks
      .me(abort.signal)
      .then(async (identity) => {
        if (abort.signal.aborted) return;
        setMe(identity);
        if (!identity.labelingAdmin)
          throw new Error('没有标注管理员权限，无法访问用户管理。');
        const users = await labelingAdmin.users(offset, abort.signal);
        if (!Array.isArray(users)) throw new Error('用户列表响应格式异常');
        if (!abort.signal.aborted) setRows(users);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [offset, retry]);
  async function save() {
    const values = await form.validateFields().catch(() => null);
    if (!values || !target || !me?.labelingAdmin || busy) return;
    if (
      target.actorId === me.actorId &&
      (values.status === 'DISABLED' || !values.labelingAdmin)
    ) {
      setError('不能停用自己或撤销自己的管理员权限');
      return;
    }
    setBusy(true);
    setError('');
    try {
      // Recheck the real identity; training roles never authorize this operation.
      if (!(await tasks.me()).labelingAdmin)
        throw new Error('标注管理员权限已变化');
      await labelingAdmin.update(target.actorId, {
        ...values,
        reason: values.reason.trim(),
      });
      setTarget(undefined);
      setRetry((n) => n + 1);
    } catch (e) {
      setError(describeLabelingError(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <WorkspaceLayout>
      <h1>标注用户管理</h1>
      <Alert
        type="info"
        showIcon
        message="仅管理已建立标注身份映射的用户"
        description="标注资格与训练角色独立；管理员身份不授予他人项目或原始图片读取权限。停用保留管理员授予记录，重新启用会恢复其有效权限。"
      />
      {error && <Alert type="error" showIcon message={error} />}
      <Card
        extra={
          <Button loading={busy} onClick={() => setRetry((n) => n + 1)}>
            重新读取权限与列表
          </Button>
        }
      >
        {me?.labelingAdmin && (
          <>
            <Table<LabelingUser>
              rowKey="actorId"
              loading={busy}
              dataSource={rows}
              pagination={false}
              scroll={{ x: 850 }}
              columns={[
                { title: '名称', dataIndex: 'displayName' },
                {
                  title: '标注人编号',
                  dataIndex: 'actorId',
                  render: (id) => (
                    <Typography.Text copyable>{id}</Typography.Text>
                  ),
                },
                { title: '统一身份编号', dataIndex: 'identityUserId' },
                { title: '资格', dataIndex: 'status' },
                {
                  title: '标注管理员',
                  dataIndex: 'labelingAdmin',
                  render: (v) => (v ? '是' : '否'),
                },
                {
                  title: '操作',
                  render: (_, row) => (
                    <Button
                      disabled={busy || row.actorId === me.actorId}
                      onClick={() => {
                        setTarget(row);
                        form.setFieldsValue({ ...row, reason: '' });
                      }}
                    >
                      修改权限
                    </Button>
                  ),
                },
              ]}
            />
            <Space wrap style={{ marginTop: 16 }}>
              <Button
                disabled={busy || offset === 0}
                onClick={() => setOffset((n) => Math.max(0, n - 20))}
              >
                上一页
              </Button>
              <span>第 {offset / 20 + 1} 页</span>
              <Button
                disabled={busy || rows.length < 20}
                onClick={() => setOffset((n) => n + 20)}
              >
                下一页
              </Button>
            </Space>
          </>
        )}
      </Card>
      <Modal
        title={`确认修改标注权限：${target?.displayName || target?.actorId || ''}`}
        open={Boolean(target)}
        onOk={() => void save()}
        onCancel={() => {
          if (!busy) setTarget(undefined);
        }}
        confirmLoading={busy}
        okText="确认修改"
        cancelText="取消"
        maskClosable={!busy}
        closable={!busy}
      >
        <Form form={form} layout="vertical" disabled={busy}>
          <Form.Item
            name="status"
            label="标注资格"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: 'ENABLED', label: '启用' },
                { value: 'DISABLED', label: '停用' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="labelingAdmin"
            label="标注管理员权限"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: true, label: '授予' },
                { value: false, label: '撤销' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因（1–500 字）"
            rules={[{ required: true, whitespace: true }, { max: 500 }]}
          >
            <Input.TextArea maxLength={500} showCount rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </WorkspaceLayout>
  );
}
