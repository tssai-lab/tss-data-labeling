/**
 * 训练代码审核状态对普通用户的展示归一。
 *
 * - 自动审核（DIRECT_PASS）：系统可自动通过/拒绝，按后端 approvalStatus 展示
 * - 管理员审核（STANDARD_REVIEW）：系统无法自动通过时不要当成已拒绝；
 *   策略 BLOCK 在管理员动手前按 PENDING 展示
 */
import { isTrainingCodeAdminReviewEnabled } from '@/constants/trainingCode';
import { normalizeCodeApprovalStatus } from '@/services/code';

export type OwnerFacingApproval = {
  /** 用于逻辑判断的状态 */
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED' | string;
  /** 展示文案 */
  label: string;
  /** Tag 颜色语义 */
  tone: 'success' | 'warning' | 'error' | 'default';
};

/**
 * @param adminReviewMode 明确传入时优先；否则读本地配置（未同步时视为管理员审核）
 */
export function resolveOwnerFacingApproval(params: {
  approvalStatus?: string | null;
  reviewDisposition?: string | null;
  adminReviewMode?: boolean;
}): OwnerFacingApproval {
  const raw =
    normalizeCodeApprovalStatus(params.approvalStatus) ||
    String(params.approvalStatus || '')
      .trim()
      .toUpperCase() ||
    'PENDING';
  const disposition = String(params.reviewDisposition || '')
    .trim()
    .toUpperCase();
  const adminReviewMode =
    params.adminReviewMode ?? isTrainingCodeAdminReviewEnabled();

  // 管理员审核：系统 BLOCK 自动拒绝在管理员处理前按 PENDING 展示
  if (adminReviewMode && raw === 'REJECTED' && disposition === 'BLOCK') {
    return { status: 'PENDING', label: 'PENDING', tone: 'warning' };
  }

  if (raw === 'APPROVED') {
    return { status: 'APPROVED', label: 'APPROVED', tone: 'success' };
  }
  if (raw === 'PENDING' || !raw) {
    return { status: 'PENDING', label: 'PENDING', tone: 'warning' };
  }
  if (raw === 'REJECTED') {
    return { status: 'REJECTED', label: 'REJECTED', tone: 'error' };
  }
  if (raw === 'REVOKED') {
    return { status: 'REVOKED', label: 'REVOKED', tone: 'default' };
  }
  return { status: raw, label: raw, tone: 'default' };
}
