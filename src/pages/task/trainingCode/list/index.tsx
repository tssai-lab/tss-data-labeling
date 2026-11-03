import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useAccess } from '@umijs/max';
import { Alert, Button, message, Popconfirm, Space, Tag, Typography } from 'antd';
import React, { useCallback, useMemo, useRef, useState } from 'react';
import { isTrainingCodeAutoApproveEnabled } from '@/constants/trainingCode';
import ResizableTitle from '@/pages/dataset/components/ResizableTitle';
import type { CodeVersionListItem } from '@/services/code';
import {
  deleteCodeAsset,
  downloadCodeVersionZip,
  fetchOwnerCodeVersionInventory,
  getCodeUserDisplayName,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import { resolveOwnerFacingApproval } from '@/utils/codeApprovalDisplay';
import { beginDownloadProgress } from '@/utils/downloadProgressToast';
import { formatDisplayDateTime } from '@/utils/formatDateTime';

const FIXED_COLUMNS_SCROLL_X = 930;

type ResizableColumnKey = 'codeAssetName' | 'fileName' | 'trainingProfile';

const DEFAULT_COLUMN_WIDTHS: Record<ResizableColumnKey, number> = {
  codeAssetName: 160,
  fileName: 180,
  trainingProfile: 220,
};

/** 上传时间：createdAt / submittedAt 同源 */
function getCodeUploadedAt(record: CodeVersionListItem): string {
  return String(record.createdAt || record.submittedAt || '').trim();
}

function compareUploadedAtDesc(
  a: CodeVersionListItem,
  b: CodeVersionListItem,
): number {
  const ta = getCodeUploadedAt(a);
  const tb = getCodeUploadedAt(b);
  if (ta && tb) return tb.localeCompare(ta);
  if (ta) return -1;
  if (tb) return 1;
  return String(b.codeVersionId || '').localeCompare(
    String(a.codeVersionId || ''),
  );
}

function approvalTag(
  status?: string,
  reviewDisposition?: string,
  adminReviewMode?: boolean,
) {
  const facing = resolveOwnerFacingApproval({
    approvalStatus: status,
    reviewDisposition,
    adminReviewMode,
  });
  const color =
    facing.tone === 'success'
      ? 'success'
      : facing.tone === 'error'
        ? 'error'
        : facing.tone === 'warning'
          ? 'warning'
          : 'default';
  return <Tag color={color}>{facing.label}</Tag>;
}

function statusTag(status?: string) {
  if (status === 'READY') {
    return <Tag color="success">可用</Tag>;
  }
  return <Tag>{status || '-'}</Tag>;
}

const TrainingCodeList: React.FC = () => {
  const access = useAccess();
  const actionRef = useRef<ActionType | null>(null);
  const [columnWidths, setColumnWidths] = useState(DEFAULT_COLUMN_WIDTHS);
  const [listNotice, setListNotice] = useState<{
    type: 'error' | 'warning';
    text: string;
  }>();
  const listRequestSequence = useRef(0);

  const handleColumnResize = useCallback(
    (key: ResizableColumnKey) => (width: number) => {
      setColumnWidths((prev) =>
        prev[key] === width ? prev : { ...prev, [key]: width },
      );
    },
    [],
  );

  const tableScrollX = useMemo(
    () =>
      FIXED_COLUMNS_SCROLL_X +
      columnWidths.codeAssetName +
      columnWidths.fileName +
      columnWidths.trainingProfile,
    [columnWidths],
  );

  const requestList = async (params: {
    codeAssetName?: string;
    current?: number;
    pageSize?: number;
  }) => {
    const sequence = ++listRequestSequence.current;
    try {
      const res = await fetchOwnerCodeVersionInventory({
        skipErrorHandler: true,
      });
      if (res?.success === false) {
        throw new Error(res.errorMessage || '训练代码列表加载失败');
      }
      if (sequence === listRequestSequence.current) {
        setListNotice(
          res.incomplete
            ? { type: 'warning', text: res.warningMessage || '训练代码列表未完整加载，请刷新重试' }
            : undefined,
        );
      }
      let list = Array.isArray(res?.data) ? res.data : [];
      const keyword = params.codeAssetName?.trim()?.toLowerCase();
      if (keyword) {
        list = list.filter((item) => {
          const name = getCodeUserDisplayName(item).toLowerCase();
          const fileName = String(item.fileName || '').toLowerCase();
          return name.includes(keyword) || fileName.includes(keyword);
        });
      }
      // 默认最新上传在前
      list = [...list].sort(compareUploadedAtDesc);
      return {
        data: list,
        success: true,
        // 本页在完整清单上筛选并由表格分页；不能沿用筛选前的服务层总数。
        total: list.length,
      };
    } catch (error: any) {
      if (sequence === listRequestSequence.current) {
        setListNotice({
          type: 'error',
          text: getApiErrorMessage(error, '训练代码列表加载失败'),
        });
      }
      return { data: [], success: false, total: 0 };
    }
  };

  const handleDelete = async (record: CodeVersionListItem) => {
    try {
      const res = await deleteCodeAsset(record.codeAssetId, {
        skipErrorHandler: true,
        codeVersionId: record.codeVersionId,
      });
      if (res?.data?.localOnly) {
        message.success('已从列表移除（本地待审记录，服务端无对应资产）');
      } else {
        message.success('已删除训练代码资产');
      }
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(getApiErrorMessage(error, '删除训练代码失败'));
    }
  };

  const handleDownload = async (record: CodeVersionListItem) => {
    const progress = beginDownloadProgress();
    try {
      await downloadCodeVersionZip(
        record.codeVersionId,
        record.fileName || `${record.codeVersionId}.zip`,
        { skipErrorHandler: true, onProgress: progress.update },
      );
      progress.close();
      message.success('已交给浏览器下载');
    } catch (error: any) {
      progress.close();
      const tip = getApiErrorMessage(error, '下载失败');
      if (tip === '已取消下载') return;
      message.error(tip);
    }
  };

  const columns: ProColumns<CodeVersionListItem>[] = useMemo(
    () => [
      {
        title: '代码名称',
        dataIndex: 'codeAssetName',
        key: 'codeAssetName',
        width: columnWidths.codeAssetName,
        ellipsis: true,
        sorter: (a, b) =>
          getCodeUserDisplayName(a).localeCompare(getCodeUserDisplayName(b)),
        onHeaderCell: () => ({
          width: columnWidths.codeAssetName,
          minWidth: 96,
          onResize: handleColumnResize('codeAssetName'),
        }),
        render: (_, record) => getCodeUserDisplayName(record),
      },
      {
        title: '文件名',
        dataIndex: 'fileName',
        key: 'fileName',
        width: columnWidths.fileName,
        ellipsis: true,
        hideInSearch: true,
        sorter: (a, b) =>
          String(a.fileName || '').localeCompare(String(b.fileName || '')),
        onHeaderCell: () => ({
          width: columnWidths.fileName,
          minWidth: 96,
          onResize: handleColumnResize('fileName'),
        }),
        render: (_, record) => record.fileName?.trim() || '-',
      },
      {
        title: '训练方案',
        dataIndex: 'trainingProfile',
        key: 'trainingProfile',
        ellipsis: true,
        hideInSearch: true,
        width: columnWidths.trainingProfile,
        sorter: (a, b) =>
          String(a.trainingProfile || '').localeCompare(
            String(b.trainingProfile || ''),
          ),
        onHeaderCell: () => ({
          width: columnWidths.trainingProfile,
          minWidth: 120,
          onResize: handleColumnResize('trainingProfile'),
        }),
      },
      {
        title: '审核状态',
        dataIndex: 'approvalStatus',
        key: 'approvalStatus',
        width: 110,
        hideInSearch: true,
        sorter: (a, b) =>
          String(a.approvalStatus || '').localeCompare(
            String(b.approvalStatus || ''),
          ),
        render: (_, record) =>
          approvalTag(
            record.approvalStatus,
            record.reviewDisposition,
            !access.isAdmin || !isTrainingCodeAutoApproveEnabled(),
          ),
      },
      {
        title: '校验状态',
        dataIndex: 'validationStatus',
        key: 'validationStatus',
        width: 110,
        hideInSearch: true,
        render: (_, record) => record.validationStatus || '-',
      },
      {
        title: '风险等级',
        dataIndex: 'riskLevel',
        key: 'riskLevel',
        width: 100,
        hideInSearch: true,
        render: (_, record) => record.riskLevel || '-',
      },
      {
        title: '就绪状态',
        dataIndex: 'status',
        key: 'status',
        width: 100,
        hideInSearch: true,
        render: (_, record) => statusTag(record.status),
      },
      {
        title: '上传时间',
        dataIndex: 'createdAt',
        key: 'createdAt',
        width: 170,
        hideInSearch: true,
        defaultSortOrder: 'descend',
        sorter: (a, b) => {
          const ta = getCodeUploadedAt(a);
          const tb = getCodeUploadedAt(b);
          if (ta && tb) return ta.localeCompare(tb);
          if (ta) return 1;
          if (tb) return -1;
          return String(a.codeVersionId || '').localeCompare(
            String(b.codeVersionId || ''),
          );
        },
        render: (_, record) => formatDisplayDateTime(getCodeUploadedAt(record)),
      },
      {
        title: '版本编号',
        dataIndex: 'codeVersionId',
        key: 'codeVersionId',
        hideInSearch: true,
        ellipsis: true,
        width: 180,
        render: (_, record) => (
          <Typography.Text copyable code style={{ fontSize: 12 }}>
            {record.codeVersionId}
          </Typography.Text>
        ),
      },
      {
        title: '操作',
        key: 'action',
        width: 180,
        hideInSearch: true,
        render: (_, record) => (
          <Space size={0} wrap>
            <Button
              type="link"
              style={{ paddingLeft: 0 }}
              onClick={() =>
                history.push(
                  `/task/code/detail/${encodeURIComponent(record.codeVersionId)}`,
                  { record, from: 'list' },
                )
              }
            >
              查看
            </Button>
            <Button type="link" onClick={() => handleDownload(record)}>
              下载
            </Button>
            <Popconfirm
              title="删除训练代码资产？"
              description="将软删除整个代码资产（含其下版本）。若已被训练引用或存在打开工作区，删除会失败。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(record)}
            >
              <Button type="link" danger>
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [access.isAdmin, columnWidths, handleColumnResize],
  );

  return (
    <PageContainer
      title="训练代码"
      subTitle={
        isTrainingCodeAutoApproveEnabled()
          ? '上传后由平台自动校验和审核'
          : '上传后由平台校验；需要人工处理时显示“待审核”，通过后才可用于训练'
      }
      extra={[
        access.isAdmin && !isTrainingCodeAutoApproveEnabled() ? (
          <Button
            key="pending"
            onClick={() => history.push('/task/code/pending')}
          >
            待审核
          </Button>
        ) : null,
        access.isAdmin ? (
          <Button
            key="admin-assets"
            onClick={() => history.push('/task/code/admin-assets')}
          >
            代码资产管理
          </Button>
        ) : null,
        <Button
          key="upload"
          type="primary"
          onClick={() => history.push('/task/code/upload')}
        >
          + 上传训练代码
        </Button>,
      ]}
    >
      {listNotice && (
        <Alert
          showIcon
          type={listNotice.type}
          message={listNotice.text}
          description={
            listNotice.type === 'error'
              ? '本次查询未成功；若表格中仍有记录，它们是上一次的结果，不能据此判断代码已丢失。'
              : undefined
          }
          action={
            <Button size="small" onClick={() => actionRef.current?.reload()}>
              重试加载
            </Button>
          }
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable<CodeVersionListItem>
        actionRef={actionRef}
        columns={columns}
        components={{
          header: {
            cell: ResizableTitle,
          },
        }}
        request={requestList}
        rowKey="codeVersionId"
        search={{ labelWidth: 'auto' }}
        pagination={{ pageSize: 10 }}
        // 无 width 的 ellipsis 列在窄屏会被挤成 0 宽（列设置仍显示已勾选）
        scroll={{ x: tableScrollX }}
      />
    </PageContainer>
  );
};

export default TrainingCodeList;
