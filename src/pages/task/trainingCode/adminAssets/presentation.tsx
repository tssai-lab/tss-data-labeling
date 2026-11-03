/** 页面内部的类型、展示及计算规则；不承载页面状态或写操作。 */

import { Tag } from 'antd';
import React from 'react';
import type { V2CodeVersion } from '@/services/platform';

export type BrowseMode = 'workspace' | 'version';

export type BrowseState = {
  mode: BrowseMode;
  title: string;
  subtitle?: string;
  /** 工作区 id 或版本 id */
  targetId: string;
  assetId?: string;
  baseVersionId?: string;
  workspaceRevision?: number;
  workspaceReadOnly?: boolean;
  currentVersionLabel?: string;
  fileEditable?: boolean;
  contentHash?: string;
  codeAssetName?: string;
  trainingProfile?: string;
};

/** 按发布时间取最新版本；无时间戳时取列表第一项 */
export function pickLatestCodeVersion(
  versions: V2CodeVersion[],
): V2CodeVersion | undefined {
  if (!versions.length) return undefined;
  const scored = versions.map((v, index) => {
    const time = Date.parse(
      String(v.publishedAt || v.createdAt || v.updatedAt || ''),
    );
    return { v, index, time: Number.isFinite(time) ? time : 0 };
  });
  scored.sort((a, b) => {
    if (b.time !== a.time) return b.time - a.time;
    return a.index - b.index;
  });
  return scored[0]?.v;
}

export function formatBytes(bytes?: number): string {
  if (bytes == null || Number.isNaN(bytes)) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function riskLevelTag(level?: string) {
  const v = String(level || '').toUpperCase();
  if (v === 'HIGH') return <Tag color="error">HIGH</Tag>;
  if (v === 'MEDIUM') return <Tag color="warning">MEDIUM</Tag>;
  if (v === 'LOW') return <Tag color="success">LOW</Tag>;
  if (v === 'UNKNOWN') return <Tag>UNKNOWN</Tag>;
  return <Tag>{level || '-'}</Tag>;
}

/** 有可展示值才返回 true（0 / false 算有值） */
export function hasDisplayValue(
  value?: string | number | boolean | null,
): boolean {
  if (value == null) return false;
  if (typeof value === 'string') return value.trim().length > 0;
  return true;
}

export function displayMetaValue(
  value?: string | number | boolean | null,
): string {
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}

/** 版本列表中是否至少有一行该字段有值 */
export function versionsHaveField(
  rows: V2CodeVersion[],
  pick: (row: V2CodeVersion) => string | number | boolean | null | undefined,
): boolean {
  return rows.some((row) => hasDisplayValue(pick(row)));
}

export function buildPublishPendingMeta(state: BrowseState) {
  return {
    codeAssetName: state.codeAssetName,
    trainingProfile: state.trainingProfile,
    fileName: state.codeAssetName,
  };
}
