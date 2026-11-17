import { Alert, App, Button, Input, Modal, Space } from 'antd';
import { useState } from 'react';
import type { Project } from '@/services/labeling';
import { ownerApi } from '@/services/labelingOwner';
import { deleteDraftProject } from '@/services/labelingProjectActions';
import useOwnerAction from './useOwnerAction';

export default function ProjectManagement({
  project,
  onChanged,
  onDeleted,
}: {
  project: Project;
  onChanged: () => void;
  onDeleted?: () => void;
}) {
  const { modal } = App.useApp();
  const action = useOwnerAction();
  const [operation, setOperation] = useState<
    'pause' | 'resume' | 'archive' | 'delete'
  >();
  const [reason, setReason] = useState('');
  const names = {
    pause: '暂停项目',
    resume: '恢复项目',
    archive: '归档项目',
    delete: '删除草稿项目',
  };
  function send() {
    if (!operation || !reason.trim()) return;
    const captured = operation;
    const body = { baseRevision: project.revision, reason: reason.trim() };
    modal.confirm({
      title: `确认${names[captured]}？`,
      content:
        captured === 'delete'
          ? '删除后项目将从列表移除，后端保留审计和原始数据。仅草稿可删除。'
          : captured === 'archive'
            ? '归档后结果只读，仍可导出已有成果；有活动轮次或导入时无法归档。'
            : '按当前项目版本提交；版本变化时需刷新。',
      onOk: async () => {
        const value = await action.run(
          `${captured}:${project.projectId}`,
          body,
          (key) =>
            captured === 'delete'
              ? deleteDraftProject(project, body.reason, key)
              : ownerApi.state(project.projectId, captured, body, key),
          `${names[captured]}成功`,
        );
        if (value) {
          setOperation(undefined);
          setReason('');
          if (value.deleted) onDeleted?.();
          else onChanged();
        }
      },
    });
  }
  return (
    <>
      <Space wrap>
        {project.status === 'OPEN' && (
          <Button disabled={action.busy} onClick={() => setOperation('pause')}>
            暂停
          </Button>
        )}
        {project.status === 'PAUSED' && (
          <Button disabled={action.busy} onClick={() => setOperation('resume')}>
            恢复
          </Button>
        )}
        {project.status !== 'ARCHIVED' && (
          <Button
            disabled={action.busy}
            onClick={() => setOperation('archive')}
          >
            归档
          </Button>
        )}
        {project.status === 'DRAFT' && (
          <Button
            type="link"
            danger
            disabled={action.busy}
            onClick={() => setOperation('delete')}
          >
            删除
          </Button>
        )}
      </Space>
      <Modal
        title={operation ? names[operation] : ''}
        open={Boolean(operation)}
        onCancel={() => {
          if (!action.busy) setOperation(undefined);
        }}
        onOk={send}
        confirmLoading={action.busy}
        okButtonProps={{ disabled: !reason.trim() }}
        destroyOnHidden
      >
        <Input.TextArea
          aria-label="项目管理原因"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={1000}
          placeholder="填写操作原因（必填）"
          disabled={action.busy}
        />
        {action.error && <Alert type="error" message={action.error} />}
      </Modal>
    </>
  );
}
