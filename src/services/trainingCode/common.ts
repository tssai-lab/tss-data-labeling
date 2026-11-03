/** 状态与版本标签基础规则。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import { normalizeV2ApprovalStatus } from '../codeV2';


/** 归一化审批状态；决策动词 APPROVE/REJECT/REVOKE 也映射到状态枚举 */
export function normalizeCodeApprovalStatus(
  status?: string | null,
): string | undefined {
  return normalizeV2ApprovalStatus(status);
}

export function suggestNextCodeVersionLabel(current?: string): string {
  const v = (current || 'v1').trim();
  const plain = v.match(/^v?(\d+)$/i);
  if (plain) return `v${Number(plain[1]) + 1}`;
  const sem = v.match(/^v?(\d+)\.(\d+)\.(\d+)$/i);
  if (sem) return `v${sem[1]}.${sem[2]}.${Number(sem[3]) + 1}`;
  return `${v}-edit`;
}

export function isOpenWorkspace(ws?: {
  status?: string;
  readOnly?: boolean;
  closedAt?: string;
  closedVersionId?: string;
}) {
  if (!ws) return false;
  if (ws.readOnly) return false;
  // 已关闭 / 已发布的工作区不应再当作草稿
  if (ws.closedAt || ws.closedVersionId) return false;
  const status = String(ws.status || '').toUpperCase();
  if (
    status === 'CLOSED' ||
    status === 'ABANDONED' ||
    status === 'PUBLISHED' ||
    status === 'COMMITTED'
  ) {
    return false;
  }
  // 无 status 时保守视为打开（兼容旧接口）；有 status 则仅 OPEN/ACTIVE
  if (!status) return true;
  return status === 'OPEN' || status === 'ACTIVE';
}

export function mapCodeWorkspaceConflictMessage(raw?: string): string | undefined {
  if (!raw) return undefined;
  if (/ASSET_REVISION_CONFLICT|资产已变更|已被.*更新/i.test(raw)) {
    return '代码资产版本已变化。请刷新页面后从最新版本打开工作区再发布；勿在过期的旧版详情上继续提交。';
  }
  if (/WORKSPACE_REVISION_CONFLICT|workspaceRevision/i.test(raw)) {
    return '工作区内容已被更新，请刷新后重试，或放弃当前工作区后重新打开。';
  }
  return undefined;
}
