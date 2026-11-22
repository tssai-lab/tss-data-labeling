import { Tag } from 'antd';
import { statusName } from '@/services/labelingWorkspace';
import ui from './workspaceUi.less';

const tones: Record<string, string> = {
  OPEN: 'success',
  ENABLED: 'success',
  DISABLED: 'neutral',
  ACCEPTED: 'success',
  SUCCEEDED: 'success',
  PUBLISHED: 'success',
  SUBMITTED: 'info',
  DRAFT: 'warning',
  NEEDS_REVISION: 'warning',
  CORRECTION: 'warning',
  PARTIAL: 'warning',
  PAUSED: 'warning',
  UNREVIEWED: 'info',
  PENDING: 'info',
  QUEUED: 'info',
  RUNNING: 'info',
  CLAIMED: 'info',
  IN_PROGRESS: 'info',
  UPLOADING: 'info',
  PROCESSING: 'info',
  FAILED: 'danger',
  REJECTED: 'danger',
};
const labels: Record<string, string> = {
  DRAFT: '草稿',
  OPEN: '开放',
  PAUSED: '暂停',
  ARCHIVED: '已归档',
  PUBLISHED: '已发布',
  SUBMITTED: '已交回',
  CLAIMED: '已领取',
  IN_PROGRESS: '标注中',
  RELEASED: '已放弃',
  REVOKED: '已撤销',
  UPLOADING: '上传中',
  PROCESSING: '解析中',
  READY: '已就绪',
  PENDING: '待审核',
};

/** Semantic status styling shared by real project, task and result views. */
export default function StatusBadge({
  status,
  children,
}: {
  status: string;
  children?: React.ReactNode;
}) {
  return (
    <Tag className={`${ui.statusTag} ${ui[tones[status] || 'neutral']}`}>
      {children || labels[status] || statusName(status)}
    </Tag>
  );
}
