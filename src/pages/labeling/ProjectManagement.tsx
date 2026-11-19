import { MoreOutlined } from '@ant-design/icons';
import { Alert, App, Button, Dropdown, Input, Modal } from 'antd';
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
  const actions = [
    ...(project.status === 'OPEN'
      ? [{ key: 'pause', label: names.pause }]
      : []),
    ...(project.status === 'PAUSED'
      ? [{ key: 'resume', label: names.resume }]
      : []),
    ...(project.status !== 'ARCHIVED'
      ? [{ key: 'archive', label: names.archive, danger: true }]
      : []),
    ...(project.status === 'DRAFT'
      ? [{ key: 'delete', label: names.delete, danger: true }]
      : []),
  ];
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
      <Dropdown
        trigger={['click']}
        menu={{
          items: actions,
          onClick: ({ key }) =>
            setOperation(key as 'pause' | 'resume' | 'archive' | 'delete'),
        }}
        disabled={action.busy || actions.length === 0}
      >
        <Button
          type="text"
          aria-label={`管理项目 ${project.name}`}
          icon={<MoreOutlined />}
          disabled={action.busy || actions.length === 0}
        />
      </Dropdown>
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
