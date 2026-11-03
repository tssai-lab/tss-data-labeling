import { Tag } from 'antd';
import React from 'react';
export function approvalTag(status?: string) {
  if (status === 'APPROVED') {
    return <Tag color="success">已通过</Tag>;
  }
  if (status === 'PENDING') {
    return <Tag color="warning">待审核</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

export function statusTag(status?: string) {
  if (status === 'READY') {
    return <Tag color="success">可用</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

export function formatBytes(bytes?: number) {
  if (bytes == null || Number.isNaN(bytes)) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export type EditNameFormValues = {
  name: string;
};

export type NewFileFormValues = {
  path: string;
  content?: string;
};

export type RenameFileFormValues = {
  targetPath: string;
};
