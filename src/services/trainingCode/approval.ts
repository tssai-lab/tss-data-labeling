/** 审核决策保留既有权限与版本 CAS；完整训练准入不使用包校验替代，也不自动重放。 */
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import { isLegacyEndpointUnavailable } from '@/utils/apiCompatibility.mjs';
import { request } from '@umijs/max';
import {
  approveV2CodeVersion,
  buildV2ApprovalRequest,
  getAdminCodeReviewTaskDetail,
  getV2CodeVersion,
  hasV2ApprovalEvidence,
} from '../codeV2';
import { normalizeCodeApprovalStatus } from './common';
import type {
  CodeVersionApprovalResult,
  CodeVersionTrainingCheckResult,
} from './types';


/** 管理员审批训练代码版本（APPROVE / REJECT / REVOKE） */
export async function decideCodeVersion(
  codeVersionId: string,
  decision: 'APPROVE' | 'REJECT' | 'REVOKE',
  options?: { [key: string]: unknown } & { reason?: string },
) {
  const reason = options?.reason;
  if ((decision === 'REJECT' || decision === 'REVOKE') && !reason?.trim()) {
    throw new Error('拒绝或撤销时必须填写原因');
  }

  let detail: Awaited<ReturnType<typeof getAdminCodeReviewTaskDetail>> | undefined;
  let detailError: unknown;
  try {
    detail = await getAdminCodeReviewTaskDetail(codeVersionId, options);
  } catch (error) {
    detailError = error;
  }

  const mapApprovalResult = (data: Record<string, unknown>) => {
    const raw =
      (typeof data?.approvalStatus === 'string' && data.approvalStatus) ||
      (typeof data?.decision === 'string' && data.decision) ||
      '';
    const normalized = normalizeCodeApprovalStatus(raw) ||
      (decision === 'APPROVE'
        ? 'APPROVED'
        : decision === 'REJECT'
          ? 'REJECTED'
          : 'REVOKED');
    return {
      success: true as const,
      data: {
        codeVersionId,
        approvalStatus: normalized,
        decisionSource:
          typeof data?.decisionSource === 'string'
            ? data.decisionSource
            : undefined,
      } as CodeVersionApprovalResult,
    };
  };

  if (detail) {
    if (decision === 'REVOKE' || hasV2ApprovalEvidence(detail)) {
      const data = await approveV2CodeVersion(
        codeVersionId,
        buildV2ApprovalRequest(detail, decision, reason),
        options,
      );
      return mapApprovalResult(data as Record<string, unknown>);
    }
    if (decision === 'APPROVE' || decision === 'REJECT') {
      const risk = detail.riskAssessment;
      const riskHint = risk?.status
        ? `风险扫描状态：${risk.status}`
        : '风险证据尚未生成';
      throw new Error(
        `审批证据未就绪，无法${decision === 'APPROVE' ? '通过' : '拒绝'}。${riskHint}；请等待扫描 COMPLETED 或在「更多」中触发重扫。`,
      );
    }
  }

  if (decision === 'REJECT' || decision === 'REVOKE') {
    if (detailError) throw detailError;
    throw new Error('无法获取审核任务详情，请确认版本仍在待审队列中');
  }

  // 只有旧后端不提供详情接口时才走兼容路径；不能绕过权限、风险证据或服务故障。
  if (detailError && !isLegacyEndpointUnavailable(detailError)) throw detailError;
  if (!detailError) throw new Error('审批详情响应为空，请确认服务端状态后再操作');
  try {
    const body: { decision: 'APPROVE'; reason?: string } = { decision: 'APPROVE' };
    if (reason?.trim()) body.reason = reason.trim();
    const data = await approveV2CodeVersion(codeVersionId, body, options);
    return mapApprovalResult(data as Record<string, unknown>);
  } catch (error) {
    if (!isLegacyEndpointUnavailable(error)) throw error;
    return request<{
      success: boolean;
      data: CodeVersionApprovalResult;
      errorMessage?: string;
    }>(`/code/version/${encodeURIComponent(codeVersionId)}/approve`, {
      method: 'POST',
      ...(options || {}),
    });
  }
}

/** 管理员审核通过训练代码版本（优先 V2 审批证据，仅接口不支持时兼容旧审批）。 */
export async function approveCodeVersion(
  codeVersionId: string,
  options?: { [key: string]: unknown },
) {
  return decideCodeVersion(codeVersionId, 'APPROVE', options);
}

/** 读取版本当前审批状态（失败时返回 undefined，不抛错） */
async function peekCodeApprovalStatus(
  codeVersionId: string,
  options?: { [key: string]: unknown },
): Promise<string | undefined> {
  try {
    const detail = await getV2CodeVersion(codeVersionId, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    return normalizeCodeApprovalStatus(detail?.approvalStatus) || undefined;
  } catch {
    return undefined;
  }
}

/**
 * 自动审核通过（管理员审核开关关闭时生效）。
 * 开启管理员审核后为 no-op，人工审核路径保持不变。
 *
 * 说明：现网上传侧常已直接 APPROVED；普通用户再调管理员审批接口会失败
 * （如「代码版本审批失败」）。此时若版本实际已是 APPROVED，视为成功，避免误入待审。
 */
export async function autoApproveCodeVersionIfEnabled(
  codeVersionId: string,
  options?: { [key: string]: unknown } & { trainingProfile?: string },
): Promise<CodeVersionApprovalResult | undefined> {
  if (!isTrainingCodeAutoApproveEnabled()) return undefined;
  const id = codeVersionId?.trim();
  if (!id) return undefined;

  const { trainingProfile, ...rest } = options || {};
  const opts = { skipErrorHandler: true, ...rest };

  const already = await peekCodeApprovalStatus(id, opts);
  if (already === 'APPROVED') {
    return {
      codeVersionId: id,
      approvalStatus: 'APPROVED',
      decisionSource: 'already-approved',
    };
  }

  const profile = trainingProfile?.trim();
  if (profile) {
    try {
      await checkCodeVersionForTraining(id, profile, opts);
    } catch {
      // 校验失败时仍尝试审批，由审批接口返回明确错误
    }
  }

  try {
    const res = await approveCodeVersion(id, opts);
    if (res?.success === false) {
      throw new Error(res?.errorMessage || '自动审核通过失败');
    }
    return (
      res?.data || {
        codeVersionId: id,
        approvalStatus: 'APPROVED',
      }
    );
  } catch (error) {
    const after = await peekCodeApprovalStatus(id, opts);
    if (after === 'APPROVED') {
      return {
        codeVersionId: id,
        approvalStatus: 'APPROVED',
        decisionSource: 'reconciled',
      };
    }
    throw error;
  }
}

/** 管理员拒绝训练代码版本（reason 必填） */
export async function rejectCodeVersion(
  codeVersionId: string,
  reason: string,
  options?: { [key: string]: unknown },
) {
  return decideCodeVersion(codeVersionId, 'REJECT', {
    ...(options || {}),
    reason,
  });
}

/** 管理员撤销已批准的训练代码版本（reason 必填） */
export async function revokeCodeVersion(
  codeVersionId: string,
  reason: string,
  options?: { [key: string]: unknown },
) {
  return decideCodeVersion(codeVersionId, 'REVOKE', {
    ...(options || {}),
    reason,
  });
}

/**
 * 完整训练准入由后端 training-check 判定：代码包 + 方案启用/匹配。
 * V2 validate 仅校验代码包，不是等价替代。任何失败都不换接口或自动重放；
 * 即使这里是 GET，后端也会刷新校验证据，不能按普通只读请求反复重试。
 */
export async function checkCodeVersionForTraining(
  codeVersionId: string,
  trainingProfile: string,
  options?: { [key: string]: unknown },
) {
  let result: {
    success: boolean;
    data: CodeVersionTrainingCheckResult;
    errorMessage?: string;
    errorCode?: string;
    code?: number | string;
  };
  try {
    result = await request<typeof result>(
      `/code/version/${encodeURIComponent(codeVersionId)}/training-check?trainingProfile=${encodeURIComponent(trainingProfile)}`,
      { ...(options || {}), method: 'GET' },
    );
  } catch (error) {
    if (isLegacyEndpointUnavailable(error)) {
      throw new Error('当前后端不支持完整训练准入检查，请确认后端版本后再试');
    }
    throw error;
  }
  const data = result?.data;
  if (result?.success !== true || result.errorCode ||
      (result.code != null && ![0,200,'0','200'].includes(result.code))) {
    throw new Error(result?.errorMessage || '训练准入接口返回失败回执');
  }
  if (!data || typeof data.passed !== 'boolean' || data.codeVersionId !== codeVersionId ||
      data.trainingProfile !== trainingProfile || ('errorCode' in data && data.errorCode) ||
      ('success' in data && data.success === false)) {
    throw new Error('训练准入回执不完整或与当前代码、方案不匹配');
  }
  return result;
}
