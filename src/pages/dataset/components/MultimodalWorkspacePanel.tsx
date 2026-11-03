import {
  CloseCircleOutlined,
  CloudUploadOutlined,
  ReloadOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import type { UploadFile } from 'antd';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Progress,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import React, { useCallback, useEffect, useState } from 'react';
import MultimodalImportBanner from '@/pages/dataset/components/MultimodalImportBanner';
import type { V2PublishReadiness } from '@/services/datasetV2';
import {
  createDatasetWorkspaceSample,
  deleteDatasetWorkspaceSample,
  extractActiveImportJobId,
  formatPublishBlockers,
  getDatasetWorkspace,
  getDatasetWorkspaceReadiness,
  getDatasetWorkspaceSample,
  listDatasetWorkspaceSamples,
  MULTIMODAL_DATA_TYPE_LABEL,
  type MultimodalSampleDetail,
  type MultimodalSampleGrouping,
  type MultimodalSampleSummary,
  patchDatasetWorkspace,
  patchDatasetWorkspaceSample,
  publishDatasetWorkspace,
  restoreDatasetWorkspaceSample,
  uploadDatasetWorkspaceAppendPackage,
  uploadDatasetWorkspaceFileComponent,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import type { WorkspaceEditableDatasetType } from '@/utils/datasetWorkspace';
import { saveImportJobId } from '@/utils/importJobStorage';
import { formatDisplayDateTime } from '@/utils/formatDateTime';

type MultimodalWorkspacePanelProps = {
  /** V2 工作区 ID */
  workspaceId: string;
  workspaceRevision: number;
  onWorkspaceRevisionChange?: (revision: number) => void;
  /** 用于保留 append 产生的 importJobId */
  datasetId?: string;
  datasetType?: WorkspaceEditableDatasetType;
  draftVersionLabel?: string;
  parentVersionLabel?: string;
  onPublished?: (publishedVersionId?: string) => void;
  onRefresh?: () => void;
  onCancelEdit?: () => void | Promise<void>;
  /** 活动导入句柄发现时回调（写入详情侧 storage / banner） */
  onImportJobDiscovered?: (importJobId: string) => void;
};

type WorkspaceSampleSummary = Omit<MultimodalSampleSummary, 'datasetVersionId'>;

function mapSampleRow(row: Record<string, unknown>): WorkspaceSampleSummary {
  const sampleId = String(row.sampleId || row.id || '');
  return {
    sampleId,
    sampleIndex: Number(row.sampleIndex ?? 0),
    externalId: String(row.externalId || ''),
    deleted: Boolean(row.deleted),
    tags: (row.tags as Record<string, unknown>) || undefined,
  };
}

const MultimodalWorkspacePanel: React.FC<MultimodalWorkspacePanelProps> = ({
  workspaceId,
  workspaceRevision,
  onWorkspaceRevisionChange,
  datasetId,
  datasetType = 'MULTIMODAL',
  draftVersionLabel,
  parentVersionLabel,
  onPublished,
  onRefresh,
  onCancelEdit,
  onImportJobDiscovered,
}) => {
  const isMultimodalDataset = datasetType === 'MULTIMODAL';
  const itemLabel = isMultimodalDataset ? '样本' : '文件';
  const [revision, setRevision] = useState(workspaceRevision);
  const [readiness, setReadiness] = useState<V2PublishReadiness | null>(null);

  const [samples, setSamples] = useState<WorkspaceSampleSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [detail, setDetail] = useState<MultimodalSampleDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [appendOpen, setAppendOpen] = useState(false);
  const [appendForm] = Form.useForm();
  const [appendUploading, setAppendUploading] = useState(false);
  const [appendPercent, setAppendPercent] = useState(0);
  const [appendImportJobId, setAppendImportJobId] = useState<string | null>(
    null,
  );

  const [createSampleOpen, setCreateSampleOpen] = useState(false);
  const [createSampleLoading, setCreateSampleLoading] = useState(false);
  const [createSampleForm] = Form.useForm();
  const [metaOpen, setMetaOpen] = useState(false);
  const [metaLoading, setMetaLoading] = useState(false);
  const [metaForm] = Form.useForm();
  const [fileUploadOpen, setFileUploadOpen] = useState(false);
  const [fileUploadLoading, setFileUploadLoading] = useState(false);
  const [fileUploadPercent, setFileUploadPercent] = useState(0);
  const [fileUploadForm] = Form.useForm();
  const [editTagsOpen, setEditTagsOpen] = useState(false);
  const [editTagsLoading, setEditTagsLoading] = useState(false);
  const [editTagsForm] = Form.useForm();
  const [editTagsSampleId, setEditTagsSampleId] = useState<string | null>(null);

  const [publishing, setPublishing] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  const sampleGrouping = Form.useWatch('sampleGrouping', appendForm) as
    | MultimodalSampleGrouping
    | undefined;

  const bumpRevision = useCallback(
    (next: number) => {
      setRevision(next);
      onWorkspaceRevisionChange?.(next);
    },
    [onWorkspaceRevisionChange],
  );

  useEffect(() => {
    setRevision(workspaceRevision);
  }, [workspaceRevision, workspaceId]);

  const refreshWorkspaceMeta = useCallback(async () => {
    try {
      const ws = await getDatasetWorkspace(workspaceId, {
        skipErrorHandler: true,
      });
      bumpRevision(ws.workspaceRevision);
      setReadiness(ws.publishReadiness ?? null);
      const jobId = extractActiveImportJobId(ws);
      if (jobId) {
        setAppendImportJobId(jobId);
        if (datasetId) {
          saveImportJobId(datasetId, jobId);
        }
        onImportJobDiscovered?.(jobId);
      }
      return ws;
    } catch {
      try {
        const r = await getDatasetWorkspaceReadiness(workspaceId, {
          skipErrorHandler: true,
        });
        setReadiness(r);
      } catch {
        // ignore
      }
      return null;
    }
  }, [bumpRevision, workspaceId, datasetId, onImportJobDiscovered]);

  const loadSamples = useCallback(
    async (p = page, ps = pageSize) => {
      setListLoading(true);
      setListError(null);
      try {
        const res = await listDatasetWorkspaceSamples(
          workspaceId,
          { page: p, pageSize: ps, includeDeleted },
          { skipErrorHandler: true },
        );
        const data = res?.data;
        const rows = (data?.data ?? []).map((row) =>
          mapSampleRow(row as Record<string, unknown>),
        );
        setSamples(rows);
        setTotal(data?.total ?? 0);
        setPage(data?.page ?? p);
        setPageSize(data?.pageSize ?? ps);
      } catch (e: unknown) {
        setListError(getApiErrorMessage(e));
        setSamples([]);
        setTotal(0);
      } finally {
        setListLoading(false);
      }
    },
    [workspaceId, includeDeleted, page, pageSize],
  );

  useEffect(() => {
    void loadSamples(1, pageSize);
    void refreshWorkspaceMeta();
  }, [workspaceId, includeDeleted]);

  const openDetail = async (sampleId: string) => {
    setDrawerOpen(true);
    setDetail(null);
    setDetailLoading(true);
    try {
      const res = await getDatasetWorkspaceSample(workspaceId, sampleId, {
        skipErrorHandler: true,
      });
      const raw = res?.data as MultimodalSampleDetail | null;
      if (raw) {
        setDetail({
          ...raw,
          sampleId: raw.sampleId || sampleId,
        });
      } else {
        setDetail(null);
      }
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      setDrawerOpen(false);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleDelete = async (sampleId: string) => {
    try {
      const result = await deleteDatasetWorkspaceSample(
        workspaceId,
        sampleId,
        revision,
        { skipErrorHandler: true },
      );
      bumpRevision(result.workspaceRevision);
      message.success(`${itemLabel}已标记删除`);
      await loadSamples(page, pageSize);
      void refreshWorkspaceMeta();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    }
  };

  const handleRestore = async (sampleId: string) => {
    try {
      const result = await restoreDatasetWorkspaceSample(
        workspaceId,
        sampleId,
        revision,
        { skipErrorHandler: true },
      );
      bumpRevision(result.workspaceRevision);
      message.success(`${itemLabel}已恢复`);
      await loadSamples(page, pageSize);
      void refreshWorkspaceMeta();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    }
  };

  const handleCreateSample = async () => {
    const values = await createSampleForm.validateFields();
    setCreateSampleLoading(true);
    try {
      let tags: Record<string, unknown> | undefined;
      const tagsRaw = String(values.tagsJson ?? '').trim();
      if (tagsRaw) {
        tags = JSON.parse(tagsRaw) as Record<string, unknown>;
      }
      const result = await createDatasetWorkspaceSample(
        workspaceId,
        {
          expectedWorkspaceRevision: revision,
          externalId: String(values.externalId).trim(),
          ...(tags ? { tags } : {}),
        },
        { skipErrorHandler: true },
      );
      bumpRevision(result.workspaceRevision);
      message.success(`已创建${itemLabel}`);
      setCreateSampleOpen(false);
      createSampleForm.resetFields();
      await loadSamples(page, pageSize);
      void refreshWorkspaceMeta();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setCreateSampleLoading(false);
    }
  };

  const handlePatchMeta = async () => {
    const values = await metaForm.validateFields();
    setMetaLoading(true);
    try {
      const ws = await patchDatasetWorkspace(
        workspaceId,
        {
          expectedWorkspaceRevision: revision,
          description: values.description?.trim() || null,
          changeLog: values.changeLog?.trim() || null,
          cvTaskType: values.cvTaskType?.trim() || null,
          annotationFormat: values.annotationFormat?.trim() || null,
        },
        { skipErrorHandler: true },
      );
      bumpRevision(ws.workspaceRevision);
      message.success('工作区元数据已更新');
      setMetaOpen(false);
      void refreshWorkspaceMeta();
      onRefresh?.();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setMetaLoading(false);
    }
  };

  const handleEditTags = async () => {
    if (!editTagsSampleId) return;
    const values = await editTagsForm.validateFields();
    setEditTagsLoading(true);
    try {
      const tagsRaw = String(values.tagsJson ?? '').trim();
      const tags = tagsRaw
        ? (JSON.parse(tagsRaw) as Record<string, unknown>)
        : {};
      const result = await patchDatasetWorkspaceSample(
        workspaceId,
        editTagsSampleId,
        { expectedWorkspaceRevision: revision, tags },
        { skipErrorHandler: true },
      );
      bumpRevision(result.workspaceRevision);
      message.success('标签已更新');
      setEditTagsOpen(false);
      await loadSamples(page, pageSize);
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setEditTagsLoading(false);
    }
  };

  const handleFileComponentUpload = async () => {
    const values = await fileUploadForm.validateFields();
    const fileList = (values.file ?? []) as UploadFile[];
    const file = fileList.map((f) => f.originFileObj).filter(Boolean)[0] as
      | File
      | undefined;
    if (!file) {
      message.error('请选择文件');
      return;
    }
    setFileUploadLoading(true);
    setFileUploadPercent(0);
    try {
      const res = await uploadDatasetWorkspaceFileComponent(
        workspaceId,
        file,
        {
          expectedWorkspaceRevision: revision,
          targetOperation: values.targetOperation || 'CREATE',
          targetKind: values.targetKind || 'DATA',
          sampleId: String(values.sampleId).trim(),
          resourceId: values.resourceId?.trim() || undefined,
          dataType: values.dataType?.trim() || undefined,
          format: values.format?.trim() || undefined,
          onProgress: setFileUploadPercent,
          onRevision: bumpRevision,
        },
        { skipErrorHandler: true },
      );
      if (typeof res.data.workspaceRevision === 'number') {
        bumpRevision(res.data.workspaceRevision);
      }
      message.success('组件文件上传完成');
      setFileUploadOpen(false);
      fileUploadForm.resetFields();
      await loadSamples(page, pageSize);
      void refreshWorkspaceMeta();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setFileUploadLoading(false);
      setFileUploadPercent(0);
    }
  };

  const handlePublish = async () => {
    setPublishing(true);
    try {
      const ws = await refreshWorkspaceMeta();
      const currentRevision = ws?.workspaceRevision ?? revision;
      const readinessNow = ws?.publishReadiness ?? readiness;
      if (readinessNow && readinessNow.canPublish === false) {
        const reason = formatPublishBlockers(readinessNow);
        throw new Error(reason || '当前工作区不可发布');
      }
      const published = await publishDatasetWorkspace(
        workspaceId,
        currentRevision,
        { skipErrorHandler: true },
      );
      message.success(
        `已发布为新版本 ${published?.currentVersion?.versionLabel || ''}`.trim(),
      );
      onPublished?.(published?.currentVersion?.versionId);
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setPublishing(false);
    }
  };

  const handleCancelEdit = async () => {
    if (!onCancelEdit) return;
    setCancelling(true);
    try {
      await onCancelEdit();
    } finally {
      setCancelling(false);
    }
  };

  const handleAppendSubmit = async () => {
    const values = await appendForm.validateFields();
    const fileList = (values.file ?? []) as UploadFile[];
    const file = fileList.map((f) => f.originFileObj).filter(Boolean)[0] as
      | File
      | undefined;
    if (!file) {
      message.error('请选择 zip 文件');
      return;
    }
    if (!file.name.toLowerCase().endsWith('.zip')) {
      message.error('追加包须为 .zip 格式');
      return;
    }

    setAppendUploading(true);
    setAppendPercent(0);
    setAppendImportJobId(null);
    try {
      const res = await uploadDatasetWorkspaceAppendPackage(
        workspaceId,
        file,
        {
          expectedWorkspaceRevision: revision,
          ...(isMultimodalDataset
            ? {
                sampleGrouping:
                  values.sampleGrouping as MultimodalSampleGrouping,
                manifestPath: values.manifestPath?.trim(),
              }
            : {}),
          onProgress: setAppendPercent,
          onRevision: bumpRevision,
        },
        { skipErrorHandler: true },
      );
      const jobId = res?.data?.importJobId ?? null;
      setAppendImportJobId(jobId);
      // 上传完成响应可能带回 importJobId；失败后不能再从工作区详情重新发现
      if (jobId && datasetId) {
        saveImportJobId(datasetId, jobId);
      }
      message.success(
        jobId
          ? `追加包上传完成，后台正在导入新${itemLabel}`
          : `追加包上传完成，新${itemLabel}已加入草稿`,
      );
      setAppendOpen(false);
      appendForm.resetFields();
      onRefresh?.();
      await loadSamples(page, pageSize);
      void refreshWorkspaceMeta();
    } catch (e: unknown) {
      message.error(getApiErrorMessage(e));
      void refreshWorkspaceMeta();
    } finally {
      setAppendUploading(false);
      setAppendPercent(0);
    }
  };

  const columns: ColumnsType<WorkspaceSampleSummary> = [
    { title: '序号', dataIndex: 'sampleIndex', width: 72 },
    {
      title: isMultimodalDataset ? '外部 ID' : '文件标识',
      dataIndex: 'externalId',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    ...(isMultimodalDataset
      ? [
          {
            title: '标签',
            dataIndex: 'tags',
            width: 160,
            ellipsis: true,
            render: (tags: Record<string, unknown> | undefined) => {
              const entries = Object.entries(tags ?? {}).slice(0, 2);
              if (!entries.length) return '-';
              return entries.map(([k, val]) => (
                <Tag key={k}>
                  {k}: {String(val)}
                </Tag>
              ));
            },
          } as ColumnsType<WorkspaceSampleSummary>[number],
        ]
      : []),
    {
      title: '状态',
      key: 'deleted',
      width: 88,
      render: (_, record) =>
        record.deleted ? (
          <Tag color="default">已删除</Tag>
        ) : (
          <Tag color="success">正常</Tag>
        ),
    },
    {
      title: '操作',
      key: 'action',
      width: 280,
      render: (_, record) => (
        <Space size={0} onClick={(e) => e.stopPropagation()}>
          <Button type="link" onClick={() => openDetail(record.sampleId)}>
            详情
          </Button>
          {!record.deleted && (
            <Button
              type="link"
              onClick={() => {
                setEditTagsSampleId(record.sampleId);
                editTagsForm.setFieldsValue({
                  tagsJson: JSON.stringify(record.tags ?? {}, null, 2),
                });
                setEditTagsOpen(true);
              }}
            >
              编辑标签
            </Button>
          )}
          {record.deleted ? (
            <Button
              type="link"
              icon={<RollbackOutlined />}
              onClick={() => handleRestore(record.sampleId)}
            >
              恢复
            </Button>
          ) : (
            <Popconfirm
              title={`确认从版本工作区中删除该${itemLabel}？发布前可恢复。`}
              onConfirm={() => handleDelete(record.sampleId)}
            >
              <Button type="link" danger>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const handleTableChange = (pagination: TablePaginationConfig) => {
    void loadSamples(pagination.current ?? 1, pagination.pageSize ?? pageSize);
  };

  const blockerText = formatPublishBlockers(readiness);

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="版本工作区（草稿）"
        description={
          <>
            {draftVersionLabel ? (
              <>
                目标版本 <strong>{draftVersionLabel}</strong>（草稿）基于
              </>
            ) : (
              <>基于</>
            )}
            {parentVersionLabel ? `「${parentVersionLabel}」` : '当前正式版本'}
            派生，<strong>尚未生效</strong>
            。请删除/恢复{itemLabel}或追加
            zip；完成后「发布为新版本」，或「放弃工作区」。
            <br />
            并发 revision：{revision}
            {blockerText ? (
              <>
                <br />
                <Typography.Text type="warning">
                  发布阻塞：{blockerText}
                </Typography.Text>
              </>
            ) : null}
          </>
        }
      />

      {appendImportJobId && (
        <MultimodalImportBanner
          importJobId={appendImportJobId}
          datasetId={datasetId}
          workspaceId={workspaceId}
          workspaceRevision={revision}
          onWorkspaceRevisionChange={bumpRevision}
          onImportFinished={() => {
            setAppendImportJobId(null);
            void loadSamples(page, pageSize);
            onRefresh?.();
            void refreshWorkspaceMeta();
          }}
        />
      )}

      <Space wrap style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          icon={<CloudUploadOutlined />}
          onClick={() => setAppendOpen(true)}
        >
          追加{itemLabel}
        </Button>
        <Button onClick={() => setCreateSampleOpen(true)}>
          创建{itemLabel}
        </Button>
        <Button onClick={() => setFileUploadOpen(true)}>上传组件文件</Button>
        <Button
          onClick={() => {
            metaForm.resetFields();
            setMetaOpen(true);
          }}
        >
          编辑版本元数据
        </Button>
        <Popconfirm
          title="确认发布？发布后将变为新的正式版本并对外生效。"
          onConfirm={handlePublish}
        >
          <Button type="primary" loading={publishing}>
            发布为新版本
          </Button>
        </Popconfirm>
        {onCancelEdit && (
          <Popconfirm
            title="放弃本次版本工作区？草稿将被废弃，已删样本不会保存。"
            onConfirm={() => void handleCancelEdit()}
          >
            <Button danger icon={<CloseCircleOutlined />} loading={cancelling}>
              放弃工作区
            </Button>
          </Popconfirm>
        )}
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            void loadSamples(page, pageSize);
            void refreshWorkspaceMeta();
          }}
        >
          刷新
        </Button>
        <Space>
          <Typography.Text>显示已删除</Typography.Text>
          <Switch checked={includeDeleted} onChange={setIncludeDeleted} />
        </Space>
      </Space>

      {listError && (
        <Typography.Paragraph type="danger">{listError}</Typography.Paragraph>
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
          showTotal: (t) => `共 ${t} 个${itemLabel}`,
        }}
        onChange={handleTableChange}
        locale={{
          emptyText: listError ? '加载失败' : `工作区中暂无${itemLabel}`,
        }}
        scroll={{ y: 360 }}
      />

      <Drawer
        title={
          detail
            ? `${itemLabel}：${detail.externalId || detail.sampleId}`
            : `${itemLabel}详情`
        }
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={640}
        destroyOnClose
      >
        {detailLoading && <Typography.Text>加载中…</Typography.Text>}
        {detail && !detailLoading && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="样本 ID">
                {detail.sampleId}
              </Descriptions.Item>
              <Descriptions.Item label="外部 ID">
                {detail.externalId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="工作区 ID">
                {detail.datasetVersionId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="样本序号">
                {detail.sampleIndex ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatDisplayDateTime(detail.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                {detail.deleted ? '已删除' : '正常'}
              </Descriptions.Item>
              <Descriptions.Item label="标签" span={2}>
                {detail.tags && Object.keys(detail.tags).length
                  ? JSON.stringify(detail.tags)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="元数据" span={2}>
                {detail.metadata && Object.keys(detail.metadata).length
                  ? JSON.stringify(detail.metadata)
                  : '-'}
              </Descriptions.Item>
            </Descriptions>
            {Array.isArray(detail.data) && (
              <div>
                <Typography.Title level={5}>数据组件</Typography.Title>
                {detail.data.map((d) => (
                  <Tag key={d.sampleDataId}>
                    {MULTIMODAL_DATA_TYPE_LABEL[d.dataType] ||
                      d.dataType ||
                      'data'}
                    {d.fileName ? `：${d.fileName}` : ''}
                    {!d.fileName && d.format ? `：${d.format}` : ''}
                  </Tag>
                ))}
              </div>
            )}
            {Array.isArray(detail.annotations) && (
              <div>
                <Typography.Title level={5}>标注</Typography.Title>
                {detail.annotations.map((a) => (
                  <Tag key={a.annotationId}>
                    {a.annotationType || '标注'}
                    {a.fileName ? `：${a.fileName}` : ''}
                    {!a.fileName && a.format ? `：${a.format}` : ''}
                  </Tag>
                ))}
              </div>
            )}
          </Space>
        )}
      </Drawer>

      <Modal
        title={`追加上传 ${itemLabel}`}
        open={appendOpen}
        onCancel={() => !appendUploading && setAppendOpen(false)}
        onOk={() => void handleAppendSubmit()}
        confirmLoading={appendUploading}
        destroyOnClose
      >
        <Form form={appendForm} layout="vertical">
          {isMultimodalDataset && (
            <>
              <Form.Item
                name="sampleGrouping"
                label="样本分组"
                initialValue="AUTO_DIRECTORY"
              >
                <Select
                  options={[
                    { value: 'AUTO_DIRECTORY', label: 'AUTO_DIRECTORY' },
                    { value: 'MANIFEST', label: 'MANIFEST' },
                  ]}
                />
              </Form.Item>
              {sampleGrouping === 'MANIFEST' && (
                <Form.Item name="manifestPath" label="Manifest 路径">
                  <Input placeholder="例如 manifest.json" />
                </Form.Item>
              )}
            </>
          )}
          <Form.Item
            name="file"
            label="追加 ZIP"
            valuePropName="fileList"
            getValueFromEvent={(e) => e?.fileList ?? []}
            rules={[{ required: true, message: '请选择 zip' }]}
          >
            <Upload beforeUpload={() => false} maxCount={1} accept=".zip">
              <Button icon={<CloudUploadOutlined />}>选择文件</Button>
            </Upload>
          </Form.Item>
          {appendUploading && <Progress percent={appendPercent} />}
        </Form>
      </Modal>

      <Modal
        title={`创建${itemLabel}`}
        open={createSampleOpen}
        onCancel={() => !createSampleLoading && setCreateSampleOpen(false)}
        onOk={() => void handleCreateSample()}
        confirmLoading={createSampleLoading}
        destroyOnClose
      >
        <Form form={createSampleForm} layout="vertical">
          <Form.Item
            name="externalId"
            label="externalId"
            rules={[{ required: true, message: '必填' }]}
          >
            <Input placeholder="样本外部 ID，创建后不可改" />
          </Form.Item>
          <Form.Item
            name="tagsJson"
            label="tags（JSON，可选）"
            extra='例如 {"scene":"indoor"}'
          >
            <Input.TextArea rows={3} placeholder="{}" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑样本标签"
        open={editTagsOpen}
        onCancel={() => !editTagsLoading && setEditTagsOpen(false)}
        onOk={() => void handleEditTags()}
        confirmLoading={editTagsLoading}
        destroyOnClose
      >
        <Form form={editTagsForm} layout="vertical">
          <Form.Item
            name="tagsJson"
            label="tags（JSON）"
            rules={[
              {
                validator: async (_, value) => {
                  const raw = String(value ?? '').trim();
                  if (!raw) return;
                  JSON.parse(raw);
                },
              },
            ]}
          >
            <Input.TextArea rows={6} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑版本元数据（Merge Patch）"
        open={metaOpen}
        onCancel={() => !metaLoading && setMetaOpen(false)}
        onOk={() => void handlePatchMeta()}
        confirmLoading={metaLoading}
        destroyOnClose
      >
        <Form form={metaForm} layout="vertical">
          <Form.Item name="description" label="description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="changeLog" label="changeLog">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="cvTaskType" label="cvTaskType">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="annotationFormat" label="annotationFormat">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="上传数据/标注组件文件"
        open={fileUploadOpen}
        onCancel={() => !fileUploadLoading && setFileUploadOpen(false)}
        onOk={() => void handleFileComponentUpload()}
        confirmLoading={fileUploadLoading}
        destroyOnClose
        width={560}
      >
        <Form
          form={fileUploadForm}
          layout="vertical"
          initialValues={{ targetOperation: 'CREATE', targetKind: 'DATA' }}
        >
          <Form.Item
            name="sampleId"
            label="样本 ID"
            rules={[{ required: true, message: '必填' }]}
          >
            <Input placeholder="目标 sampleId" />
          </Form.Item>
          <Form.Item
            name="targetOperation"
            label="操作"
            rules={[{ required: true, message: '请选择操作' }]}
            extra="对应后端字段 targetOperation：CREATE / REPLACE"
          >
            <Select
              options={[
                { value: 'CREATE', label: 'CREATE（新建）' },
                { value: 'REPLACE', label: 'REPLACE（替换）' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="targetKind"
            label="目标种类"
            rules={[{ required: true, message: '请选择目标种类' }]}
            extra="对应后端字段 targetKind：DATA=数据组件，ANNOTATION=标注组件"
          >
            <Select
              options={[
                { value: 'DATA', label: 'DATA（数据）' },
                { value: 'ANNOTATION', label: 'ANNOTATION（标注）' },
              ]}
            />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, next) => prev.operation !== next.operation}
          >
            {({ getFieldValue }) =>
              getFieldValue('operation') === 'REPLACE' ? (
                <Form.Item
                  name="resourceId"
                  label="resourceId"
                  rules={[{ required: true, message: 'REPLACE 必填' }]}
                >
                  <Input />
                </Form.Item>
              ) : null
            }
          </Form.Item>
          <Form.Item name="dataType" label="dataType（可选）">
            <Input placeholder="IMAGE / TEXT / ..." />
          </Form.Item>
          <Form.Item name="format" label="format（可选）">
            <Input />
          </Form.Item>
          <Form.Item
            name="file"
            label="文件"
            valuePropName="fileList"
            getValueFromEvent={(e) => e?.fileList ?? []}
            rules={[{ required: true, message: '请选择文件' }]}
          >
            <Upload beforeUpload={() => false} maxCount={1}>
              <Button icon={<CloudUploadOutlined />}>选择文件</Button>
            </Upload>
          </Form.Item>
          {fileUploadLoading && <Progress percent={fileUploadPercent} />}
        </Form>
      </Modal>
    </>
  );
};

export default MultimodalWorkspacePanel;
