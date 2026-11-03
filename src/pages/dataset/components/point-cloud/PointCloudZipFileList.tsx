import { Input, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import React, { useMemo, useState } from 'react';
import type { PointCloudZipEntry } from '@/services/pointcloud';

function formatBytes(sizeBytes?: number | null) {
  if (
    sizeBytes === undefined ||
    sizeBytes === null ||
    Number.isNaN(sizeBytes)
  ) {
    return '-';
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = sizeBytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[unitIndex]}`;
}

export type PointCloudZipFileListProps = {
  files: PointCloudZipEntry[];
  selectedPath?: string;
  loading?: boolean;
  onSelect: (entry: PointCloudZipEntry) => void;
  onPreview: (entry: PointCloudZipEntry) => void;
};

const DEFAULT_PAGE_SIZE = 10;
/** 表体固定高度，超出部分在列表内滚动 */
const TABLE_BODY_SCROLL_HEIGHT = 200;

const PointCloudZipFileList: React.FC<PointCloudZipFileListProps> = ({
  files,
  selectedPath,
  loading,
  onSelect,
  onPreview,
}) => {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const filteredFiles = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) {
      return files;
    }
    return files.filter(
      (item) =>
        item.path.toLowerCase().includes(normalized) ||
        item.fileName.toLowerCase().includes(normalized),
    );
  }, [files, keyword]);

  const columns: ColumnsType<PointCloudZipEntry> = [
    {
      title: '路径 / 文件名',
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (_, record) => (
        <Tooltip title={record.path}>
          <Typography.Text>{record.path}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '格式',
      dataIndex: 'format',
      key: 'format',
      width: 80,
      render: (format: string) => <Tag>{format}</Tag>,
    },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      key: 'sizeBytes',
      width: 120,
      render: (sizeBytes: number | undefined, record) =>
        formatBytes(sizeBytes ?? record.sizeBytes),
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) =>
        record.previewAllowed ? (
          <a
            onClick={(e) => {
              e.stopPropagation();
              onPreview(record);
            }}
          >
            开始预览
          </a>
        ) : (
          <Tooltip title={record.message || '不可在线预览'}>
            <span style={{ color: 'rgba(0,0,0,0.25)', cursor: 'not-allowed' }}>
              开始预览
            </span>
          </Tooltip>
        ),
    },
  ];

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage(pagination.current ?? 1);
    setPageSize(pagination.pageSize ?? DEFAULT_PAGE_SIZE);
  };

  return (
    <div style={{ marginBottom: 12 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 12,
          marginBottom: 12,
        }}
      >
        <Input.Search
          allowClear
          placeholder="搜索文件名或路径"
          style={{ width: 280 }}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          支持 110MB 以内的点云文件在线预览
        </Typography.Text>
      </div>

      <Table<PointCloudZipEntry>
        size="small"
        rowKey="path"
        loading={loading}
        columns={columns}
        dataSource={filteredFiles}
        scroll={{ y: TABLE_BODY_SCROLL_HEIGHT }}
        pagination={{
          current: page,
          pageSize,
          total: filteredFiles.length,
          showSizeChanger: true,
          pageSizeOptions: ['10', '20', '50'],
          showTotal: (total) => `共 ${total} 条`,
        }}
        onChange={handleTableChange}
        locale={{
          emptyText: loading
            ? '正在加载 zip 内点云文件列表…'
            : keyword
              ? '无匹配文件'
              : 'zip 内未找到点云文件',
        }}
        rowClassName={(record) =>
          record.path === selectedPath ? 'point-cloud-zip-row-selected' : ''
        }
        onRow={(record) => ({
          onClick: () => onSelect(record),
          style: { cursor: 'pointer' },
        })}
      />

      <style>{`
        .point-cloud-zip-row-selected > td {
          background-color: #e6f4ff !important;
        }
        .point-cloud-zip-row-selected:hover > td {
          background-color: #bae0ff !important;
        }
      `}</style>
    </div>
  );
};

export default PointCloudZipFileList;
