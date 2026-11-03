/** 资产版本表的列与按钮展示。业务操作由页面回调注入，不在列定义中调用 API。 */

import { Button, Popconfirm, Space, Tag, Typography } from 'antd';
import React from 'react';
import type { V2CodeVersion } from '@/services/platform';
import { formatDisplayDateTime } from '@/utils/formatDateTime';
import { formatBytes, riskLevelTag, versionsHaveField } from './presentation';
export function buildVersionColumns({
  versions,
  versionActionLoading,
  openVersionBrowse,
  handleValidateVersion,
  handleDownloadVersionZip,
  handleOpenWorkspaceFromVersion,
  handleDeprecateVersion,
  handleArchiveVersion,
}: {
  versions: V2CodeVersion[];
  versionActionLoading?: string;
  openVersionBrowse: (row: V2CodeVersion) => unknown;
  handleValidateVersion: (row: V2CodeVersion) => unknown;
  handleDownloadVersionZip: (row: V2CodeVersion) => unknown;
  handleOpenWorkspaceFromVersion: (row: V2CodeVersion) => unknown;
  handleDeprecateVersion: (row: V2CodeVersion) => unknown;
  handleArchiveVersion: (row: V2CodeVersion) => unknown;
}) {
  return (() => {
    const cols: Array<Record<string, unknown>> = [
      {
        title: '版本标签',
        dataIndex: 'versionLabel',
        width: 120,
        ellipsis: true,
        fixed: 'left' as const,
        render: (_: unknown, r: V2CodeVersion) =>
          r.versionLabel || r.version || '-',
      },
      {
        title: '生命周期',
        dataIndex: 'status',
        width: 100,
        render: (v: string | undefined) => <Tag>{v || '-'}</Tag>,
      },
      {
        title: '审核',
        dataIndex: 'approvalStatus',
        width: 110,
        render: (v: string | undefined) => {
          const s = String(v || '').toUpperCase();
          if (s === 'APPROVED') return <Tag color="success">已通过</Tag>;
          if (s === 'PENDING') return <Tag color="warning">待审核</Tag>;
          if (s === 'REJECTED') return <Tag color="error">已拒绝</Tag>;
          if (s === 'REVOKED') return <Tag>已撤销</Tag>;
          return <Tag>{v || '-'}</Tag>;
        },
      },
      {
        title: '校验',
        dataIndex: 'validationStatus',
        width: 100,
        render: (v: string | undefined) => v || '-',
      },
    ];

    const optional: Array<{
      when: boolean;
      col: Record<string, unknown>;
    }> = [
      {
        when: versionsHaveField(versions, (r) => r.trainingProfile),
        col: {
          title: 'trainingProfile',
          dataIndex: 'trainingProfile',
          width: 140,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.entryScript),
        col: {
          title: 'entryScript',
          dataIndex: 'entryScript',
          width: 140,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.sizeBytes),
        col: {
          title: '大小',
          dataIndex: 'sizeBytes',
          width: 100,
          render: (v: number | undefined) => formatBytes(v),
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.riskLevel),
        col: {
          title: '风险等级',
          dataIndex: 'riskLevel',
          width: 100,
          render: (v: string | undefined) => riskLevelTag(v),
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.riskStatus),
        col: {
          title: '风险状态',
          dataIndex: 'riskStatus',
          width: 110,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.reviewDisposition),
        col: {
          title: '分流结论',
          dataIndex: 'reviewDisposition',
          width: 140,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.fileName),
        col: {
          title: 'fileName',
          dataIndex: 'fileName',
          width: 140,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.remark),
        col: {
          title: '备注',
          dataIndex: 'remark',
          width: 140,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.publishedAt),
        col: {
          title: 'publishedAt',
          dataIndex: 'publishedAt',
          width: 170,
          render: (v: string | undefined) => formatDisplayDateTime(v) || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.createdAt),
        col: {
          title: 'createdAt',
          dataIndex: 'createdAt',
          width: 170,
          render: (v: string | undefined) => formatDisplayDateTime(v) || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.updatedAt),
        col: {
          title: 'updatedAt',
          dataIndex: 'updatedAt',
          width: 170,
          render: (v: string | undefined) => formatDisplayDateTime(v) || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.artifactSha256),
        col: {
          title: 'artifactSha256',
          dataIndex: 'artifactSha256',
          width: 220,
          ellipsis: true,
          render: (v: string | undefined) =>
            v ? (
              <Typography.Text
                copyable={{ text: v }}
                ellipsis
                style={{ maxWidth: 200 }}
              >
                {v}
              </Typography.Text>
            ) : (
              '-'
            ),
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.validationPolicyVersion),
        col: {
          title: 'validationPolicyVersion',
          dataIndex: 'validationPolicyVersion',
          width: 180,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.riskPolicyVersion),
        col: {
          title: 'riskPolicyVersion',
          dataIndex: 'riskPolicyVersion',
          width: 160,
          ellipsis: true,
          render: (v: string | undefined) => v || '-',
        },
      },
      {
        when: versionsHaveField(versions, (r) => r.riskAssessmentId),
        col: {
          title: 'riskAssessmentId',
          dataIndex: 'riskAssessmentId',
          width: 200,
          ellipsis: true,
          render: (v: string | undefined) =>
            v ? (
              <Typography.Text
                copyable={{ text: v }}
                ellipsis
                style={{ maxWidth: 180 }}
              >
                {v}
              </Typography.Text>
            ) : (
              '-'
            ),
        },
      },
    ];

    for (const item of optional) {
      if (item.when) cols.push(item.col);
    }

    cols.push(
      {
        title: 'versionId',
        dataIndex: 'versionId',
        width: 220,
        ellipsis: true,
        render: (_: unknown, r: V2CodeVersion) => {
          const id = r.versionId || r.id || r.codeVersionId;
          return id ? (
            <Typography.Text
              copyable={{ text: id }}
              ellipsis
              style={{ maxWidth: 200 }}
            >
              {id}
            </Typography.Text>
          ) : (
            '-'
          );
        },
      },
      {
        title: '操作',
        key: 'action',
        width: 380,
        fixed: 'right' as const,
        render: (_: unknown, r: V2CodeVersion) => {
          const versionId = r.versionId || r.id || r.codeVersionId || '';
          const loading = versionActionLoading === versionId;
          return (
            <Space size={0} wrap>
              <Button type="link" onClick={() => void openVersionBrowse(r)}>
                查看文件
              </Button>
              <Button
                type="link"
                loading={loading}
                onClick={() => void handleValidateVersion(r)}
              >
                校验
              </Button>
              <Button
                type="link"
                loading={loading}
                onClick={() => void handleDownloadVersionZip(r)}
              >
                下载
              </Button>
              <Button
                type="link"
                loading={loading}
                onClick={() => void handleOpenWorkspaceFromVersion(r)}
              >
                编辑
              </Button>{' '}
              <Popconfirm
                title="弃用该代码版本？"
                description="弃用后不可再用于新训练。"
                onConfirm={() => void handleDeprecateVersion(r)}
              >
                <Button type="link" danger loading={loading}>
                  弃用
                </Button>
              </Popconfirm>
              <Popconfirm
                title="归档该代码版本？"
                onConfirm={() => void handleArchiveVersion(r)}
              >
                <Button type="link" loading={loading}>
                  归档
                </Button>
              </Popconfirm>
            </Space>
          );
        },
      },
    );

    return cols;
  })();
}
