/**
 * 待审核训练代码本地登记（兜底）。
 *
 * 正式待审队列以 GET /api/v2/admin/code-review-tasks 为准；
 * 本机登记仅用于手工录入 codeVersionId 等运维场景。
 */

export type PendingCodeVersionRecord = {
  codeVersionId: string;
  /** 有则删除/列表展示可直接用，避免「缺少 codeAssetId」 */
  codeAssetId?: string;
  codeAssetName?: string;
  fileName?: string;
  trainingProfile?: string;
  approvalStatus?: string;
  storagePath?: string;
  sizeBytes?: number;
  uploadedAt?: string;
  source?: 'upload' | 'manual' | 'api' | 'publish';
};

const STORAGE_KEY = 'tssai.pendingCodeVersions';

function readAll(): PendingCodeVersionRecord[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as PendingCodeVersionRecord[]) : [];
  } catch {
    return [];
  }
}

function writeAll(list: PendingCodeVersionRecord[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
}

export function listPendingCodeVersions(): PendingCodeVersionRecord[] {
  return readAll().sort((a, b) =>
    String(b.uploadedAt || '').localeCompare(String(a.uploadedAt || '')),
  );
}

export function upsertPendingCodeVersion(
  record: PendingCodeVersionRecord,
): PendingCodeVersionRecord[] {
  const id = record.codeVersionId?.trim();
  if (!id) return listPendingCodeVersions();
  const next = readAll().filter((item) => item.codeVersionId !== id);
  next.unshift({
    ...record,
    codeVersionId: id,
    uploadedAt: record.uploadedAt || new Date().toISOString(),
    approvalStatus: record.approvalStatus || 'PENDING',
  });
  writeAll(next);
  return listPendingCodeVersions();
}

export function removePendingCodeVersion(codeVersionId: string) {
  const id = codeVersionId?.trim();
  if (!id) return listPendingCodeVersions();
  writeAll(readAll().filter((item) => item.codeVersionId !== id));
  return listPendingCodeVersions();
}

export function markPendingCodeApproved(codeVersionId: string) {
  removePendingCodeVersion(codeVersionId);
}

/** 拒绝/撤销后仍保留本地记录，便于列表展示审核状态 */
export function markPendingCodeStatus(
  codeVersionId: string,
  approvalStatus: string,
): PendingCodeVersionRecord[] {
  const id = codeVersionId?.trim();
  if (!id) return listPendingCodeVersions();
  const existing = readAll().find((item) => item.codeVersionId === id);
  // 管理员误写空壳时不要凭空新建；仅更新已有上传登记
  if (!existing) return listPendingCodeVersions();
  return upsertPendingCodeVersion({
    ...existing,
    codeVersionId: id,
    approvalStatus,
  });
}
