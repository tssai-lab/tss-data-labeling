import { DownloadOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
  Image,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  type ConsumerManifestSample,
  downloadMultimodalAnnotationWithBrowser,
  downloadMultimodalDataWithBrowser,
  fetchConsumerManifest,
  fetchMultimodalDataPreview,
  fetchMultimodalSampleDetail,
  fetchMultimodalSamples,
  MULTIMODAL_DATA_TYPE_LABEL,
  type MultimodalSampleDataItem,
  type MultimodalSampleDetail,
  type MultimodalSampleSummary,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import { downloadAuthFile } from '@/utils/authFileDownload';

function normalizeApiPath(url: string): string {
  return url.startsWith('/api') ? url.slice(4) : url;
}

async function fetchBlobFromApiUrl(
  url: string,
  options?: { [key: string]: unknown },
) {
  return request<Blob>(normalizeApiPath(url), {
    method: 'GET',
    responseType: 'blob',
    ...(options || {}),
  });
}

function formatBytes(size?: number): string {
  if (size == null || size < 0) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(2)} MB`;
}

type MultimodalPreviewPanelProps = {
  versionId?: string;
  compact?: boolean;
};

function isTextPreviewable(item: MultimodalSampleDataItem): boolean {
  if (item.dataType === 'TEXT') return true;
  if (item.dataType !== 'OTHER') return false;
  const ct = (item.contentType || '').toLowerCase();
  return (
    ct.startsWith('text/') ||
    ct.includes('json') ||
    ct.includes('xml') ||
    ct === 'application/csv'
  );
}

const MultimodalDataPreview: React.FC<{
  item: MultimodalSampleDataItem;
}> = ({ item }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [textContent, setTextContent] = useState<string | null>(null);
  const urlRef = useRef<string | null>(null);

  const revokeUrl = useCallback(() => {
    if (urlRef.current) {
      URL.revokeObjectURL(urlRef.current);
      urlRef.current = null;
    }
    setPreviewUrl(null);
  }, []);

  useEffect(() => {
    revokeUrl();
    setTextContent(null);
    setError(null);

    if (item.dataType === 'AUDIO') {
      setError('音频暂不支持在线预览，请下载后查看');
      return;
    }
    if (item.dataType === 'POINT_CLOUD') {
      setError('点云数据请使用下载查看');
      return;
    }

    const load = async () => {
      setLoading(true);
      try {
        const blob = item.previewUrl
          ? await fetchBlobFromApiUrl(item.previewUrl, {
              skipErrorHandler: true,
            })
          : await fetchMultimodalDataPreview(item.sampleDataId, {
              skipErrorHandler: true,
            });
        if (item.dataType === 'IMAGE') {
          const url = URL.createObjectURL(blob);
          urlRef.current = url;
          setPreviewUrl(url);
        } else if (item.dataType === 'VIDEO') {
          const url = URL.createObjectURL(blob);
          urlRef.current = url;
          setPreviewUrl(url);
        } else if (isTextPreviewable(item)) {
          const text = await blob.text();
          setTextContent(
            text.length > 200_000
              ? `${text.slice(0, 200_000)}\n…（已截断）`
              : text,
          );
        } else {
          setError('该数据类型暂不支持在线预览');
        }
      } catch (e: unknown) {
        setError(getApiErrorMessage(e));
      } finally {
        setLoading(false);
      }
    };

    void load();
    return () => revokeUrl();
  }, [item, revokeUrl]);

  const handleDownload = async () => {
    try {
      if (item.downloadUrl) {
        await downloadAuthFile({
          url: item.downloadUrl,
          fileName: item.fileName || 'data',
        });
      } else {
        await downloadMultimodalDataWithBrowser(
          item.sampleDataId,
          item.fileName || 'data',
        );
      }
    } catch (e: unknown) {
      setError(getApiErrorMessage(e));
    }
  };

  if (loading) {
    return <Spin />;
  }
  if (error) {
    return (
      <Space direction="vertical">
        <Typography.Text type="danger">{error}</Typography.Text>
        <Button
          size="small"
          icon={<DownloadOutlined />}
          onClick={handleDownload}
        >
          下载
        </Button>
      </Space>
    );
  }
  if (previewUrl && item.dataType === 'IMAGE') {
    return (
      <Image
        src={previewUrl}
        alt={item.fileName || 'image'}
        style={{ maxHeight: 320, objectFit: 'contain' }}
      />
    );
  }
  if (previewUrl && item.dataType === 'VIDEO') {
    return (
      <video
        src={previewUrl}
        controls
        style={{ maxWidth: '100%', maxHeight: 320 }}
      >
        <track kind="captions" />
      </video>
    );
  }
  if (textContent != null) {
    return (
      <Typography.Paragraph
        style={{
          maxHeight: 280,
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
          fontFamily: 'monospace',
          fontSize: 12,
          marginBottom: 0,
          background: '#fafafa',
          padding: 12,
          borderRadius: 4,
        }}
      >
        {textContent}
      </Typography.Paragraph>
    );
  }
  return (
    <Button size="small" icon={<DownloadOutlined />} onClick={handleDownload}>
      下载
    </Button>
  );
};

const MultimodalPreviewPanel: React.FC<MultimodalPreviewPanelProps> = ({
  versionId,
  compact = false,
}) => {
  const [samples, setSamples] = useState<MultimodalSampleSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<MultimodalSampleDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const manifestCacheRef = useRef<Map<string, ConsumerManifestSample>>(
    new Map(),
  );

  const loadSamples = useCallback(
    async (nextPage: number, nextPageSize: number) => {
      if (!versionId) return;
      setListLoading(true);
      setListError(null);
      manifestCacheRef.current.clear();
      try {
        const manifest = await fetchConsumerManifest(
          versionId,
          { page: nextPage, pageSize: nextPageSize },
          { skipErrorHandler: true },
        );
        const manifestSamples = manifest?.samples ?? [];
        manifestSamples.forEach((sample) => {
          manifestCacheRef.current.set(sample.sampleId, sample);
        });
        setSamples(
          manifestSamples.map((sample) => ({
            sampleId: sample.sampleId,
            datasetVersionId: sample.datasetVersionId,
            externalId: sample.externalId,
            sampleIndex: sample.sampleIndex,
            tags: sample.tags,
            metadata: sample.metadata,
            createdAt: sample.createdAt,
          })),
        );
        setTotal(manifest?.totalSamples ?? manifestSamples.length);
        setPage(manifest?.page ?? nextPage);
        setPageSize(manifest?.pageSize ?? nextPageSize);
      } catch {
        try {
          const res = await fetchMultimodalSamples(
            versionId,
            { page: nextPage, pageSize: nextPageSize },
            { skipErrorHandler: true },
          );
          const pageData = res?.data;
          setSamples(pageData?.data ?? []);
          setTotal(pageData?.total ?? 0);
          setPage(pageData?.page ?? nextPage);
          setPageSize(pageData?.pageSize ?? nextPageSize);
        } catch (e: unknown) {
          setSamples([]);
          setTotal(0);
          setListError(getApiErrorMessage(e));
        }
      } finally {
        setListLoading(false);
      }
    },
    [versionId],
  );

  useEffect(() => {
    if (!versionId) {
      setSamples([]);
      setTotal(0);
      return;
    }
    void loadSamples(1, pageSize);
  }, [versionId, loadSamples, pageSize]);

  const openSampleDetail = async (sampleId: string) => {
    setDrawerOpen(true);
    setDetail(null);
    setDetailError(null);
    const cached = manifestCacheRef.current.get(sampleId);
    if (cached) {
      setDetail(cached);
      return;
    }
    setDetailLoading(true);
    try {
      const res = await fetchMultimodalSampleDetail(sampleId, {
        skipErrorHandler: true,
      });
      setDetail(res?.data ?? null);
    } catch (e: unknown) {
      setDetailError(getApiErrorMessage(e));
    } finally {
      setDetailLoading(false);
    }
  };

  const handleTableChange = (pagination: TablePaginationConfig) => {
    void loadSamples(pagination.current ?? 1, pagination.pageSize ?? pageSize);
  };

  const columns: ColumnsType<MultimodalSampleSummary> = [
    {
      title: '序号',
      dataIndex: 'sampleIndex',
      width: 72,
    },
    {
      title: '外部 ID',
      dataIndex: 'externalId',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 160,
      ellipsis: true,
      render: (tags: Record<string, unknown> | undefined) => {
        const entries = Object.entries(tags ?? {}).slice(0, 3);
        if (!entries.length) return '-';
        return (
          <Space size={[4, 4]} wrap>
            {entries.map(([k, val]) => (
              <Tag key={k}>
                {k}: {String(val)}
              </Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Button type="link" onClick={() => openSampleDetail(record.sampleId)}>
          查看
        </Button>
      ),
    },
  ];

  if (!versionId) {
    return <Empty description="请选择准备完成的数据集版本" />;
  }

  return (
    <>
      {listError && (
        <Typography.Paragraph type="danger" style={{ marginBottom: 12 }}>
          {listError}
        </Typography.Paragraph>
      )}
      <Table
        size="small"
        rowKey="sampleId"
        loading={listLoading}
        columns={columns}
        dataSource={samples}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          pageSizeOptions: ['10', '20', '50', '100'],
          showTotal: (t) => `共 ${t} 个样本`,
        }}
        onChange={handleTableChange}
        scroll={{ y: compact ? 280 : 400 }}
        locale={{
          emptyText: listError
            ? '加载失败'
            : '暂无样本（版本须为 READY 且导入成功）',
        }}
      />

      <Drawer
        title={
          detail ? `样本：${detail.externalId || detail.sampleId}` : '样本详情'
        }
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={720}
        destroyOnClose
      >
        {detailLoading && (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin />
          </div>
        )}
        {detailError && (
          <Typography.Text type="danger">{detailError}</Typography.Text>
        )}
        {detail && !detailLoading && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="样本 ID">
                {detail.sampleId}
              </Descriptions.Item>
              <Descriptions.Item label="外部 ID">
                {detail.externalId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="序号">
                {detail.sampleIndex}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {detail.createdAt || '-'}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <Typography.Title level={5}>数据项</Typography.Title>
              {detail.data?.length ? (
                detail.data.map((item) => (
                  <div
                    key={item.sampleDataId}
                    style={{
                      border: '1px solid #f0f0f0',
                      borderRadius: 8,
                      padding: 12,
                      marginBottom: 12,
                    }}
                  >
                    <Space wrap style={{ marginBottom: 8 }}>
                      <Tag color="blue">
                        {MULTIMODAL_DATA_TYPE_LABEL[item.dataType] ??
                          item.dataType}
                      </Tag>
                      {item.sensor && <Tag>{item.sensor}</Tag>}
                      {item.channel && <Tag>{item.channel}</Tag>}
                      <Typography.Text type="secondary">
                        {item.fileName} · {formatBytes(item.sizeBytes)}
                      </Typography.Text>
                    </Space>
                    <MultimodalDataPreview item={item} />
                  </div>
                ))
              ) : (
                <Empty
                  description="无数据项"
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                />
              )}
            </div>

            {!!detail.annotations?.length && (
              <div>
                <Typography.Title level={5}>标注</Typography.Title>
                {detail.annotations.map((ann) => (
                  <div
                    key={ann.annotationId}
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      padding: '8px 0',
                      borderBottom: '1px solid #f0f0f0',
                    }}
                  >
                    <Space>
                      <Tag>{ann.annotationType || '标注'}</Tag>
                      <Typography.Text>
                        {ann.fileName} · {formatBytes(ann.sizeBytes)}
                      </Typography.Text>
                    </Space>
                    <Button
                      size="small"
                      icon={<DownloadOutlined />}
                      onClick={async () => {
                        try {
                          if (ann.downloadUrl) {
                            await downloadAuthFile({
                              url: ann.downloadUrl,
                              fileName: ann.fileName || 'annotation',
                            });
                          } else {
                            await downloadMultimodalAnnotationWithBrowser(
                              ann.annotationId,
                              ann.fileName || 'annotation',
                            );
                          }
                        } catch (e: unknown) {
                          setDetailError(getApiErrorMessage(e));
                        }
                      }}
                    >
                      下载
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </Space>
        )}
      </Drawer>
    </>
  );
};

export default MultimodalPreviewPanel;
